package dev.mobilehorizon.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Per-instance store and selector for Horizon's source-only native .hnmod bundles.
 *
 * Importing a bundle never executes it. A native engine is produced remotely by the
 * no-secrets GitHub Actions workflow, then installed per instance after validating
 * its base runtime, module list, SHA-256 and ARM64 ELF header.
 */
final class HorizonNativeModManager {
    private static final String TAG = "HorizonNativeMods";
    private static final String MODS_DIR = "horizon-native-mods";
    private static final String SELECTION_FILE = "horizon-native-selection.json";
    private static final String RUNTIME_DIR = "horizon-native-runtime";
    private static final String ACTIVE_FILE = "active.json";
    private static final String MANIFEST_FILE = "horizon-native-runtime-manifest.json";
    private static final String LIBRARY_NAME = "libhorizon_engine.so";
    private static final String STATUS_FILE = "loader-status.txt";
    private static final String REJECTED_FILE = "rejected.json";
    private static final int MAX_INSTALLED_MODS = 24;
    private static final long MAX_TOTAL_MOD_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_RUNTIME_BYTES = 300L * 1024L * 1024L;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,95}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BASE_RUNTIME_VERSION =
            Pattern.compile("-c([0-9a-f]{8,12})-o([0-9a-f]{8})$");

    static final class InstalledMod {
        final HorizonNativeModArchive.Descriptor descriptor;
        final boolean enabled;
        InstalledMod(HorizonNativeModArchive.Descriptor descriptor, boolean enabled) {
            this.descriptor = descriptor;
            this.enabled = enabled;
        }
    }

    static final class PreparedRuntime {
        final File root;
        final File activeLibrary;
        final File fallbackLibrary;
        final File statusFile;
        final String activeVersion;
        final int modCount;

        PreparedRuntime(File root, File activeLibrary, File fallbackLibrary,
                        File statusFile, String activeVersion, int modCount) {
            this.root = root;
            this.activeLibrary = activeLibrary;
            this.fallbackLibrary = fallbackLibrary;
            this.statusFile = statusFile;
            this.activeVersion = activeVersion;
            this.modCount = modCount;
        }
    }

    private HorizonNativeModManager() { }

    static File modsDirectory(Context context, String instanceId) throws IOException {
        File home = GameInstanceManager.getInstanceHome(context, instanceId).getCanonicalFile();
        return safeChildDirectory(home, MODS_DIR);
    }

    static List<InstalledMod> list(Context context, String instanceId) throws IOException {
        File directory = modsDirectory(context, instanceId);
        Set<String> enabled = readEnabled(directory);
        File[] files = directory.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".hnmod"));
        enforceModArchiveBudget(files, 0L);
        ArrayList<InstalledMod> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (Files.isSymbolicLink(file.toPath())) {
                    throw new IOException("Um pacote nativo não pode ser um link simbólico: " + file.getName());
                }
                HorizonNativeModArchive.Descriptor descriptor = HorizonNativeModArchive.read(file);
                result.add(new InstalledMod(descriptor, enabled.contains(descriptor.id)));
            }
        }
        result.sort(Comparator.comparing(item -> item.descriptor.id));
        return Collections.unmodifiableList(result);
    }

    static HorizonNativeModArchive.Descriptor importArchive(
            Context context, String instanceId, File source) throws IOException {
        HorizonNativeModArchive.Descriptor descriptor = HorizonNativeModArchive.read(source);
        File directory = modsDirectory(context, instanceId);
        File target = new File(directory, descriptor.id + ".hnmod").getCanonicalFile();
        ensureInside(directory, target);
        if (target.exists()) {
            throw new IOException("O módulo " + descriptor.id + " já está importado nesta instância.");
        }
        File[] existing = directory.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".hnmod"));
        enforceModArchiveBudget(existing, source.length());
        File temporary = new File(directory,
                "." + descriptor.id + "." + android.os.Process.myPid() + ".partial");
        if (Files.isSymbolicLink(temporary.toPath())) {
            throw new IOException("Arquivo temporário inseguro para importação .hnmod.");
        }
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Não foi possível remover a importação temporária anterior.");
        }
        try {
            copy(source, temporary);
            HorizonNativeModArchive.Descriptor copied = HorizonNativeModArchive.read(temporary);
            if (!descriptor.sha256.equals(copied.sha256)) {
                throw new IOException("O .hnmod foi alterado durante a importação.");
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), target.toPath());
        } finally {
            if (temporary.exists()) temporary.delete();
        }
        return descriptor;
    }

    static void setEnabled(Context context, String instanceId, String id, boolean enabled)
            throws IOException {
        if (id == null || !id.matches("[a-z0-9_]{1,64}")) {
            throw new IOException("ID de Native Mod inválido.");
        }
        File directory = modsDirectory(context, instanceId);
        File archive = new File(directory, id + ".hnmod");
        HorizonNativeModArchive.read(archive);
        TreeSet<String> selection = readEnabled(directory);
        if (enabled) selection.add(id); else selection.remove(id);
        writeEnabled(directory, selection);
    }

    static void remove(Context context, String instanceId, String id) throws IOException {
        if (id == null || !id.matches("[a-z0-9_]{1,64}")) {
            throw new IOException("ID de Native Mod inválido.");
        }
        File directory = modsDirectory(context, instanceId);
        File archive = new File(directory, id + ".hnmod");
        if (Files.isSymbolicLink(archive.toPath())) {
            throw new IOException("O pacote nativo não pode ser um link simbólico.");
        }
        File tombstone = new File(directory, "." + id + ".removing");
        if (Files.isSymbolicLink(tombstone.toPath())) {
            throw new IOException("O arquivo temporário de remoção não é seguro.");
        }
        if (tombstone.exists() && !tombstone.delete()) {
            throw new IOException("Não foi possível limpar a remoção anterior.");
        }
        boolean moved = archive.exists();
        if (moved && !archive.renameTo(tombstone)) {
            throw new IOException("Não foi possível preparar a remoção do pacote " + id + ".");
        }
        TreeSet<String> selection = readEnabled(directory);
        selection.remove(id);
        try {
            writeEnabled(directory, selection);
        } catch (IOException error) {
            if (moved && tombstone.exists() && !tombstone.renameTo(archive)) {
                error.addSuppressed(new IOException("Falha ao restaurar o pacote após a remoção abortada."));
            }
            throw error;
        }
        if (tombstone.exists() && !tombstone.delete()) {
            Log.w(TAG, "Pacote removido da seleção; arquivo de limpeza pendente: " + tombstone);
        }
    }

    static TreeSet<String> selectedIds(Context context, String instanceId) throws IOException {
        return readEnabled(modsDirectory(context, instanceId));
    }

    static String baseRuntimeReleaseTag(String activeRuntimeVersion) {
        if (activeRuntimeVersion == null || activeRuntimeVersion.isEmpty()) return "";
        return "horizon-runtime-" + activeRuntimeVersion;
    }

    /**
     * Installs the ZIP downloaded from the GitHub Actions artifact. The artifact must
     * contain the selected .hnmod set and target the exact clean base runtime selected
     * by this instance. A stale build is never activated.
     */
    static String installBuildArtifact(Context context, String instanceId, File artifact,
                                       String activeBaseRuntimeVersion) throws IOException {
        if (artifact == null || !artifact.isFile() || Files.isSymbolicLink(artifact.toPath())) {
            throw new IOException("O arquivo do build .hnmod não foi encontrado.");
        }
        if (artifact.length() <= 0 || artifact.length() > MAX_RUNTIME_BYTES + MAX_MANIFEST_BYTES) {
            throw new IOException("O pacote do runtime nativo excede o limite permitido.");
        }
        List<InstalledMod> selected = selectedMods(context, instanceId);
        if (selected.isEmpty()) {
            throw new IOException("Ative pelo menos um .hnmod antes de instalar um runtime nativo.");
        }

        JSONObject manifest;
        byte[] engine;
        try (ZipFile zip = new ZipFile(artifact)) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST_FILE);
            ZipEntry engineEntry = zip.getEntry(LIBRARY_NAME);
            if (manifestEntry == null || engineEntry == null
                    || manifestEntry.isDirectory() || engineEntry.isDirectory()
                    || manifestEntry.getSize() <= 0 || manifestEntry.getSize() > MAX_MANIFEST_BYTES
                    || engineEntry.getSize() <= 0 || engineEntry.getSize() > MAX_RUNTIME_BYTES) {
                throw new IOException("O artefato precisa conter o manifesto e libhorizon_engine.so ARM64.");
            }
            try {
                manifest = new JSONObject(new String(
                        readBounded(zip.getInputStream(manifestEntry), MAX_MANIFEST_BYTES),
                        StandardCharsets.UTF_8));
            } catch (JSONException error) {
                throw new IOException("Manifesto do runtime nativo inválido.", error);
            }
            engine = readBounded(zip.getInputStream(engineEntry), MAX_RUNTIME_BYTES);
        }

        String format = manifest.optString("format", "");
        String version = manifest.optString("version", "");
        String baseVersion = manifest.optString("base_runtime_version", "");
        String baseCommit = manifest.optString("base_commit", "");
        String mixinsCommit = manifest.optString("mixins_commit", "");
        int hooksApi = manifest.optInt("hooks_api", 0);
        String overlay = manifest.optString("overlay", "");
        String abi = manifest.optString("abi", "");
        String library = manifest.optString("library", "");
        String expectedHash = manifest.optString("sha256", "").toLowerCase(Locale.ROOT);
        String game = manifest.optString("game", "");
        if (!"mobilehorizon-native-mod-runtime".equals(format)
                || !SAFE_VERSION.matcher(version).matches()
                || !activeBaseRuntimeVersion.equals(baseVersion)
                || !baseCommit.matches("[0-9a-f]{40}")
                || !mixinsCommit.matches("[0-9a-f]{40}")
                || hooksApi < 0 || hooksApi > 10000
                || !overlay.matches("[0-9a-f]{8}")
                || !baseRuntimeIdentityMatches(baseVersion, baseCommit, overlay)
                || !"26.3".equals(game)
                || !"arm64-v8a".equals(abi)
                || !LIBRARY_NAME.equals(library)
                || !SHA256.matcher(expectedHash).matches()) {
            throw new IOException("O runtime não corresponde à versão base Horizon, revisão, ABI ou manifesto esperados.");
        }

        JSONArray recordedMods = manifest.optJSONArray("mods");
        if (!modsMatch(selected, recordedMods)
                || !modsCompatible(selected, game, baseCommit, mixinsCommit, hooksApi)) {
            throw new IOException("O runtime foi compilado para outro conjunto de .hnmod ou outra revisão Horizon. Gere um build com a seleção atual.");
        }
        String actualHash = sha256(engine);
        if (!actualHash.equals(expectedHash)) {
            throw new IOException("SHA-256 da biblioteca compilada não confere com o manifesto.");
        }
        verifyAarch64Elf(engine);

        File home = GameInstanceManager.getInstanceHome(context, instanceId).getCanonicalFile();
        File runtimeRoot = safeChildDirectory(home, RUNTIME_DIR);
        File versions = safeChildDirectory(runtimeRoot, "versions");
        File versionDirectory = safeChildDirectory(versions, version);
        File targetLibrary = new File(versionDirectory, LIBRARY_NAME).getCanonicalFile();
        ensureInside(versionDirectory, targetLibrary);
        writeAtomic(new File(versionDirectory, MANIFEST_FILE), manifest.toString().getBytes(StandardCharsets.UTF_8));
        File temporary = new File(versionDirectory, LIBRARY_NAME + ".partial");
        if (Files.isSymbolicLink(temporary.toPath())) throw new IOException("Arquivo parcial inseguro.");
        if (temporary.exists() && !temporary.delete()) throw new IOException("Não foi possível limpar a biblioteca parcial.");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(engine);
            output.getFD().sync();
        }
        try {
            if (!sha256(temporary).equals(expectedHash)) {
                throw new IOException("SHA-256 mudou durante a instalação do runtime.");
            }
            verifyAarch64Elf(temporary);
            Files.move(temporary.toPath(), targetLibrary.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), targetLibrary.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
        JSONObject active = new JSONObject();
        try {
            active.put("version", version);
            active.put("sha256", expectedHash);
            active.put("base_runtime_version", baseVersion);
        } catch (JSONException impossible) {
            throw new IOException("Não foi possível registrar o runtime nativo.", impossible);
        }
        // A manual reinstall is an explicit recovery action for a previously rejected build.
        clearRuntimeMarker(new File(runtimeRoot, STATUS_FILE));
        clearRuntimeMarker(new File(runtimeRoot, REJECTED_FILE));
        writeAtomic(new File(runtimeRoot, ACTIVE_FILE), active.toString().getBytes(StandardCharsets.UTF_8));
        return version;
    }

    /** Returns a matching per-instance runtime, or null to use the shared clean Horizon engine. */
    static PreparedRuntime prepareForLaunch(Context context, String instanceId,
                                            String baseRuntimeVersion, File cleanEngine)
            throws IOException {
        List<InstalledMod> selected = selectedMods(context, instanceId);
        if (selected.isEmpty()) return null;

        File home = GameInstanceManager.getInstanceHome(context, instanceId).getCanonicalFile();
        File runtimeRoot = safeChildDirectory(home, RUNTIME_DIR);
        File activeFile = new File(runtimeRoot, ACTIVE_FILE);
        if (!activeFile.isFile() || Files.isSymbolicLink(activeFile.toPath())) return null;

        JSONObject active = readJson(activeFile, 16 * 1024);
        String version = active.optString("version", "");
        String activeHash = active.optString("sha256", "").toLowerCase(Locale.ROOT);
        if (!SAFE_VERSION.matcher(version).matches() || !SHA256.matcher(activeHash).matches()
                || !baseRuntimeVersion.equals(active.optString("base_runtime_version", ""))) {
            return null;
        }

        File versionDirectory = safeChildDirectory(safeChildDirectory(runtimeRoot, "versions"), version);
        File manifestFile = new File(versionDirectory, MANIFEST_FILE);
        File library = new File(versionDirectory, LIBRARY_NAME).getCanonicalFile();
        if (!manifestFile.isFile() || !library.isFile() || Files.isSymbolicLink(library.toPath())) return null;
        JSONObject manifest = readJson(manifestFile, MAX_MANIFEST_BYTES);
        String baseCommit = manifest.optString("base_commit", "");
        String mixinsCommit = manifest.optString("mixins_commit", "");
        String game = manifest.optString("game", "");
        int hooksApi = manifest.optInt("hooks_api", 0);
        if (!version.equals(manifest.optString("version", ""))
                || !baseRuntimeVersion.equals(manifest.optString("base_runtime_version", ""))
                || !activeHash.equals(manifest.optString("sha256", "").toLowerCase(Locale.ROOT))
                || !modsMatch(selected, manifest.optJSONArray("mods"))
                || !modsCompatible(selected, game, baseCommit, mixinsCommit, hooksApi)
                || !baseRuntimeIdentityMatches(baseRuntimeVersion, baseCommit,
                        manifest.optString("overlay", ""))) {
            return null;
        }
        ensureInside(versionDirectory, library);
        if (!sha256(library).equals(activeHash)) return null;
        verifyAarch64Elf(library);

        File status = new File(runtimeRoot, STATUS_FILE);
        File rejected = new File(runtimeRoot, REJECTED_FILE);
        String previousStatus = readSmallText(status);
        if (previousStatus.startsWith("RUNNING:")) {
            // android_main reached the engine: retire an old startup marker/rejection.
            clearRuntimeMarker(status);
            clearRuntimeMarker(rejected);
        } else if (previousStatus.startsWith("STARTING:")
                || previousStatus.startsWith("FALLBACK:")
                || previousStatus.startsWith("ERROR:")
                || previousStatus.startsWith("FAIL:")) {
            writeRejectedMarker(rejected, version, activeHash, baseRuntimeVersion);
            clearRuntimeMarker(status);
            Log.e(TAG, "A inicialização anterior do Native Mod não chegou a android_main. Usando Horizon limpo nesta instância.");
            return null;
        }
        if (isRejectedBuild(rejected, version, activeHash, baseRuntimeVersion)) {
            Log.w(TAG, "Runtime .hnmod previamente rejeitada; usando Horizon limpo.");
            return null;
        }
        return new PreparedRuntime(runtimeRoot, library, cleanEngine.getCanonicalFile(),
                status, version, selected.size());
    }

    static boolean hasBuildForSelection(Context context, String instanceId,
                                        String baseRuntimeVersion) {
        try {
            List<InstalledMod> selected = selectedMods(context, instanceId);
            if (selected.isEmpty()) return false;
            File home = GameInstanceManager.getInstanceHome(context, instanceId).getCanonicalFile();
            File runtimeRoot = safeChildDirectory(home, RUNTIME_DIR);
            JSONObject active = readJson(new File(runtimeRoot, ACTIVE_FILE), 16 * 1024);
            String version = active.optString("version", "");
            String hash = active.optString("sha256", "").toLowerCase(Locale.ROOT);
            if (!baseRuntimeVersion.equals(active.optString("base_runtime_version", ""))
                    || !SAFE_VERSION.matcher(version).matches() || !SHA256.matcher(hash).matches()) {
                return false;
            }
            File versionDirectory = safeChildDirectory(safeChildDirectory(runtimeRoot, "versions"), version);
            File library = new File(versionDirectory, LIBRARY_NAME).getCanonicalFile();
            if (isRejectedBuild(new File(runtimeRoot, REJECTED_FILE),
                    version, hash, baseRuntimeVersion)) {
                return false;
            }
            JSONObject manifest = readJson(new File(versionDirectory, MANIFEST_FILE), MAX_MANIFEST_BYTES);
            String baseCommit = manifest.optString("base_commit", "");
            String mixinsCommit = manifest.optString("mixins_commit", "");
            String game = manifest.optString("game", "");
            int hooksApi = manifest.optInt("hooks_api", 0);
            return hash.equals(manifest.optString("sha256", "").toLowerCase(Locale.ROOT))
                    && baseRuntimeVersion.equals(manifest.optString("base_runtime_version", ""))
                    && modsMatch(selected, manifest.optJSONArray("mods"))
                    && modsCompatible(selected, game, baseCommit, mixinsCommit, hooksApi)
                    && baseRuntimeIdentityMatches(baseRuntimeVersion, baseCommit,
                            manifest.optString("overlay", ""))
                    && library.isFile() && sha256(library).equals(hash);
        } catch (Exception error) {
            return false;
        }
    }

    private static void enforceModArchiveBudget(File[] files, long additionalBytes) throws IOException {
        int count = files == null ? 0 : files.length;
        if (count + (additionalBytes > 0 ? 1 : 0) > MAX_INSTALLED_MODS) {
            throw new IOException("Uma instância pode manter no máximo " + MAX_INSTALLED_MODS + " módulos nativos.");
        }
        long total = additionalBytes;
        if (files != null) {
            for (File file : files) {
                if (Files.isSymbolicLink(file.toPath()) || !file.isFile()) {
                    throw new IOException("Arquivo .hnmod inválido na pasta de módulos: " + file.getName());
                }
                total += file.length();
                if (total > MAX_TOTAL_MOD_BYTES) {
                    throw new IOException("Os módulos desta instância excedem 256 MiB no total.");
                }
            }
        }
    }

    private static String readSmallText(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            throw new IOException("Marcador da runtime nativa não pode ser um link simbólico.");
        }
        if (!file.isFile()) return "";
        if (file.length() > 4096) {
            throw new IOException("Marcador da runtime nativa excede o limite permitido.");
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void clearRuntimeMarker(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            throw new IOException("Marcador da runtime nativa não pode ser um link simbólico.");
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Não foi possível limpar o marcador da runtime nativa: " + file.getName());
        }
    }

    private static void writeRejectedMarker(File file, String version, String hash,
                                            String baseRuntimeVersion) throws IOException {
        JSONObject rejected = new JSONObject();
        try {
            rejected.put("version", version);
            rejected.put("sha256", hash);
            rejected.put("base_runtime_version", baseRuntimeVersion);
        } catch (JSONException impossible) {
            throw new IOException("Não foi possível registrar a runtime nativa rejeitada.", impossible);
        }
        writeAtomic(file, rejected.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isRejectedBuild(File file, String version, String hash,
                                           String baseRuntimeVersion) throws IOException {
        if (!file.isFile()) return false;
        JSONObject rejected = readJson(file, 16 * 1024);
        return version.equals(rejected.optString("version", ""))
                && hash.equals(rejected.optString("sha256", "").toLowerCase(Locale.ROOT))
                && baseRuntimeVersion.equals(rejected.optString("base_runtime_version", ""));
    }

    private static List<InstalledMod> selectedMods(Context context, String instanceId) throws IOException {
        ArrayList<InstalledMod> result = new ArrayList<>();
        for (InstalledMod mod : list(context, instanceId)) if (mod.enabled) result.add(mod);
        return result;
    }

    static boolean baseRuntimeIdentityMatches(
            String runtimeVersion, String baseCommit, String overlay) {
        java.util.regex.Matcher matcher = BASE_RUNTIME_VERSION
                .matcher(runtimeVersion == null ? "" : runtimeVersion);
        return matcher.find() && baseCommit != null
                && baseCommit.startsWith(matcher.group(1))
                && overlay != null && overlay.equals(matcher.group(2));
    }

    /**
     * The release tag carries a short prefix of the exact Horizon commit. A mismatch is
     * definitive; a match is only a preflight hint because the builder checks the full SHA.
     */
    static boolean matchesBaseRuntimeRevision(String modHorizonRevision, String runtimeVersion) {
        if (modHorizonRevision == null || !modHorizonRevision.matches("[0-9a-f]{40}")) {
            return false;
        }
        java.util.regex.Matcher matcher = BASE_RUNTIME_VERSION
                .matcher(runtimeVersion == null ? "" : runtimeVersion);
        return matcher.find() && modHorizonRevision.startsWith(matcher.group(1));
    }

    /**
     * A native bundle without a hooks declaration pins one exact Horizon commit. A bundle
     * with hooks=N follows the append-only native hook API and can target a later commit
     * exposing API N or newer, just as the PC launcher does.
     */
    static boolean isNativeModCompatible(
            String game, String testedHorizon, Integer requiredHooks,
            String targetCommit, int availableHooksApi) {
        if (!"26.3".equals(game) || targetCommit == null
                || !targetCommit.matches("[0-9a-f]{40}")) return false;
        if (requiredHooks == null) {
            return targetCommit.equals(testedHorizon);
        }
        return requiredHooks > 0 && availableHooksApi >= requiredHooks;
    }

    /** UI preflight: hook-API bundles are checked against the exact source by the CI builder. */
    static boolean isPotentiallyCompatible(
            HorizonNativeModArchive.Descriptor descriptor, String runtimeVersion) {
        if (descriptor == null) return false;
        java.util.regex.Matcher matcher = BASE_RUNTIME_VERSION
                .matcher(runtimeVersion == null ? "" : runtimeVersion);
        if (!matcher.find()) return false;
        if (descriptor.hooks != null) {
            return descriptor.hooks > 0 && "26.3".equals(descriptor.game);
        }
        return descriptor.horizonRevision.startsWith(matcher.group(1));
    }

    private static boolean modsCompatible(List<InstalledMod> selected, String game,
                                          String baseCommit, String mixinsCommit, int hooksApi) {
        if (selected.isEmpty()) return false;
        for (InstalledMod mod : selected) {
            HorizonNativeModArchive.Descriptor descriptor = mod.descriptor;
            if (!isNativeModCompatible(descriptor.game, descriptor.horizonRevision,
                    descriptor.hooks, baseCommit, hooksApi)
                    || !game.equals(descriptor.game)
                    || !mixinsCommit.equals(descriptor.mixinsRevision)) {
                return false;
            }
        }
        return true;
    }

    private static boolean modsMatch(List<InstalledMod> selected, JSONArray recorded) {
        if (recorded == null || recorded.length() != selected.size()) return false;
        try {
            ArrayList<String> expected = new ArrayList<>();
            for (InstalledMod mod : selected) {
                expected.add(mod.descriptor.id + "|" + mod.descriptor.version + "|" + mod.descriptor.sha256);
            }
            ArrayList<String> actual = new ArrayList<>();
            for (int i = 0; i < recorded.length(); i++) {
                JSONObject mod = recorded.getJSONObject(i);
                actual.add(mod.optString("id", "") + "|" + mod.optString("version", "") + "|"
                        + mod.optString("sha256", "").toLowerCase(Locale.ROOT));
            }
            Collections.sort(expected);
            Collections.sort(actual);
            return expected.equals(actual);
        } catch (JSONException error) {
            return false;
        }
    }

    private static TreeSet<String> readEnabled(File directory) throws IOException {
        TreeSet<String> result = new TreeSet<>();
        File file = new File(directory, SELECTION_FILE);
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("A seleção de Native Mods não pode ser um link simbólico.");
        if (!file.isFile()) return result;
        JSONObject json = readJson(file, 64 * 1024);
        JSONArray array = json.optJSONArray("enabled");
        if (array == null) throw new IOException("A seleção de Native Mods está corrompida.");
        for (int i = 0; i < array.length(); i++) {
            String id = array.optString(i, "");
            if (id.matches("[a-z0-9_]{1,64}")) result.add(id);
        }
        return result;
    }

    private static void writeEnabled(File directory, Set<String> enabled) throws IOException {
        JSONArray array = new JSONArray();
        for (String id : new TreeSet<>(enabled)) array.put(id);
        JSONObject json = new JSONObject();
        try {
            json.put("format", 1);
            json.put("enabled", array);
        } catch (JSONException impossible) {
            throw new IOException("Não foi possível salvar a seleção de Native Mods.", impossible);
        }
        writeAtomic(new File(directory, SELECTION_FILE), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static File safeChildDirectory(File parent, String name) throws IOException {
        File root = parent.getCanonicalFile();
        if (Files.isSymbolicLink(root.toPath())) throw new IOException("Diretório da instância não pode ser link simbólico.");
        File child = new File(root, name).getCanonicalFile();
        ensureInside(root, child);
        if (Files.isSymbolicLink(child.toPath())) throw new IOException("Diretório Native Mods não pode ser link simbólico.");
        if (!child.isDirectory() && !child.mkdirs()) throw new IOException("Não foi possível criar " + child);
        return child;
    }

    private static void ensureInside(File root, File child) throws IOException {
        String prefix = root.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(prefix)) {
            throw new IOException("O caminho do Native Mod saiu da pasta privada da instância.");
        }
    }

    private static JSONObject readJson(File file, int maxBytes) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile()
                || file.length() <= 0 || file.length() > maxBytes) {
            throw new IOException("Manifesto Native Mod ausente ou inválido.");
        }
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new IOException("JSON do Native Mod inválido.", error);
        }
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File parent = target.getCanonicalFile().getParentFile();
        if (parent == null || Files.isSymbolicLink(parent.toPath())) throw new IOException("Pasta de destino insegura.");
        File temporary = new File(parent, "." + target.getName() + ".partial");
        if (Files.isSymbolicLink(temporary.toPath())) throw new IOException("Arquivo temporário inseguro.");
        if (temporary.exists() && !temporary.delete()) throw new IOException("Não foi possível limpar o arquivo temporário.");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            output.getFD().sync();
        }
    }

    private static byte[] readBounded(InputStream input, long max) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > max) throw new IOException("Entrada excede o limite permitido.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : hash) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IOException("SHA-256 indisponível.", error);
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IOException("SHA-256 indisponível.", error);
        }
    }

    private static void verifyAarch64Elf(File file) throws IOException {
        if (file.length() < 20 || file.length() > MAX_RUNTIME_BYTES) {
            throw new IOException("A biblioteca nativa tem tamanho inválido.");
        }
        byte[] header = new byte[20];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) throw new IOException("Cabeçalho ELF incompleto.");
                offset += read;
            }
        }
        verifyAarch64Elf(header);
    }

    private static void verifyAarch64Elf(byte[] header) throws IOException {
        if (header == null || header.length < 20
                || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || header[4] != 2 || header[5] != 1
                || ByteBuffer.wrap(header, 18, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() != 183) {
            throw new IOException("A biblioteca do Native Mod não é ELF ARM64 (AArch64).");
        }
    }
}
