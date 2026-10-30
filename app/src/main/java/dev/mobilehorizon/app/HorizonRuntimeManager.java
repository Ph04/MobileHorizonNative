package dev.mobilehorizon.app;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Selects and verifies the downloaded native Horizon engine before NativeActivity starts.
 *
 * The APK contains only the stable libhorizon_android.so bootstrap. Versioned engines are
 * downloaded from verified MobileHorizonNative releases and kept in app-private folders.
 * The last-known-good downloaded engine is retained as a fallback across runtime upgrades.
 */
public final class HorizonRuntimeManager {
    private static final String TAG = "HorizonRuntime";
    private static final String ENGINE_NAME = "libhorizon_engine.so";
    private static final String ACTIVE_NAME = "active.json";
    private static final String PREVIOUS_NAME = "previous.json";
    private static final String STATUS_NAME = "loader-status.txt";
    private static final long MAX_ENGINE_BYTES = 300L * 1024L * 1024L;
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}");

    private HorizonRuntimeManager() {}

    public static final class InstalledVersion {
        public final String version;
        public final String upstreamTag;
        public final String sha256;
        public final long installedAt;
        public final boolean active;
        private InstalledVersion(String version, String tag, String sha, long installedAt,
                                 boolean active) {
            this.version = version;
            this.upstreamTag = tag;
            this.sha256 = sha;
            this.installedAt = installedAt;
            this.active = active;
        }
    }

    public static final class PreparedRuntime {
        public final File root;
        public final File activeLibrary;
        public final File fallbackLibrary;
        public final File statusFile;
        public final String activeVersion;

        private PreparedRuntime(File root, File activeLibrary, File fallbackLibrary,
                                File statusFile, String activeVersion) {
            this.root = root;
            this.activeLibrary = activeLibrary;
            this.fallbackLibrary = fallbackLibrary;
            this.statusFile = statusFile;
            this.activeVersion = activeVersion;
        }
    }

    /**
     * Returns the selected, hash-verified runtime version, or an empty string before the first
     * runtime download. No engine binary is bundled in the APK.
     */
    public static synchronized String currentVersion(Context context) throws IOException {
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), "horizon-runtime").getCanonicalFile();
        Active active = readValidActive(new File(root, "versions"));
        return active == null ? "" : active.version;
    }

    /** Fast UI hint only; full SHA-256/ELF validation is performed before use. */
    public static boolean hasActiveRuntimeMarker(Context context) {
        try {
            Context app = context.getApplicationContext();
            File root = new File(app.getFilesDir(), "horizon-runtime").getCanonicalFile();
            File versions = new File(root, "versions");
            File activeFile = new File(root, ACTIVE_NAME);
            if (!activeFile.isFile() || activeFile.length() <= 0 || activeFile.length() > 16 * 1024) {
                return false;
            }
            try (FileInputStream input = new FileInputStream(activeFile)) {
                byte[] bytes = new byte[(int) activeFile.length()];
                int count = input.read(bytes);
                if (count <= 0) return false;
                JSONObject json = new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
                String version = requireVersion(json.optString("version", ""));
                File library = new File(new File(versions, version), ENGINE_NAME).getCanonicalFile();
                String prefix = versions.getCanonicalPath() + File.separator;
                return library.getPath().startsWith(prefix) && library.isFile()
                        && library.length() >= 4096 && library.length() <= MAX_ENGINE_BYTES;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Selects a downloaded runtime and safely rolls back to a prior downloaded version. */
    public static synchronized PreparedRuntime prepare(Context context) throws IOException {
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), "horizon-runtime").getCanonicalFile();
        File versions = new File(root, "versions");
        if (!versions.isDirectory() && !versions.mkdirs()) {
            throw new IOException("Não foi possível criar " + versions);
        }

        try (FileChannel lockChannel = FileChannel.open(
                     new File(root, ".runtime-manager.lock").toPath(),
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignoredLock = lockChannel.lock()) {
            File status = new File(root, STATUS_NAME);
            String previousStatus = readSmallText(status);
            boolean previousLaunchFailed = previousStatus.startsWith("FALLBACK:")
                    || previousStatus.startsWith("ERROR:")
                    || previousStatus.startsWith("STARTING:");

            Active active = readValidActive(versions);
            Active previous = readValidSelection(versions, PREVIOUS_NAME);
            if (previousLaunchFailed) {
                if (previous != null && (active == null || !previous.version.equals(active.version))) {
                    writeActive(root, previous.version, previous.sha256);
                    active = previous;
                    previous = null;
                    deleteSelection(root, PREVIOUS_NAME);
                    Log.e(TAG, "A runtime atual falhou; revertendo para a última runtime baixada "
                            + "e verificada " + active.version + ": " + previousStatus);
                } else {
                    if (active != null) deleteSelection(root, ACTIVE_NAME);
                    if (previous != null) deleteSelection(root, PREVIOUS_NAME);
                    active = null;
                    previous = null;
                    Log.e(TAG, "A runtime falhou e não há uma versão anterior disponível: "
                            + previousStatus);
                }
            } else if (active == null && previous != null) {
                writeActive(root, previous.version, previous.sha256);
                active = previous;
                previous = null;
                deleteSelection(root, PREVIOUS_NAME);
                Log.w(TAG, "Recuperando a última runtime baixada e verificada " + active.version);
            }

            if (status.exists() && !status.delete()) {
                Log.w(TAG, "Não foi possível remover o marcador nativo antigo: " + status);
            }
            if (active == null) {
                throw new IOException("Nenhuma runtime Horizon instalada. Volte à launcher para "
                        + "baixar e verificar a runtime Android mais recente.");
            }
            if (previous != null && previous.version.equals(active.version)) previous = null;
            verifyEngine(active.library, active.sha256);
            File fallbackLibrary = previous == null ? null : previous.library.getCanonicalFile();
            return new PreparedRuntime(root, active.library.getCanonicalFile(), fallbackLibrary,
                    status.getCanonicalFile(), active.version);
        }
    }

    /**
     * Installs a downloaded ARM64 engine after checking its SHA-256 and ELF machine type,
     * then atomically selects it for the next NativeActivity launch. The caller must obtain
     * expectedSha256 from a trusted, authenticated update manifest.
     */
    public static synchronized void installAndActivate(
            Context context, File downloadedLibrary, String version, String expectedSha256)
            throws IOException {
        installAndActivate(context, downloadedLibrary, version, expectedSha256, "");
    }

    public static synchronized void installAndActivate(
            Context context, File downloadedLibrary, String version, String expectedSha256,
            String upstreamTag) throws IOException {
        Context app = context.getApplicationContext();
        String safeVersion = requireVersion(version);
        String sha = requireSha256(expectedSha256);
        File root = new File(app.getFilesDir(), "horizon-runtime").getCanonicalFile();
        File versions = new File(root, "versions");
        if (!versions.isDirectory() && !versions.mkdirs()) {
            throw new IOException("Não foi possível criar " + versions);
        }
        if (downloadedLibrary == null || !downloadedLibrary.isFile()) {
            throw new IOException("O arquivo do runtime baixado não existe.");
        }

        try (FileChannel lockChannel = FileChannel.open(
                     new File(root, ".runtime-manager.lock").toPath(),
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignoredLock = lockChannel.lock()) {
        File versionDir = new File(versions, safeVersion);
        File target = new File(versionDir, ENGINE_NAME);
        Active previousActive = readValidActive(versions);
        if (target.isFile()) {
            try {
                verifyEngine(target, sha);
                makeReadOnly(target);
            } catch (IOException invalidExistingBuild) {
                File quarantined = new File(versions,
                        ".corrupt-" + safeVersion + "-" + System.currentTimeMillis());
                if (!versionDir.renameTo(quarantined)) {
                    throw new IOException("A runtime existente está inválida e não pôde ser isolada: "
                            + target, invalidExistingBuild);
                }
            }
        }
        target = new File(versionDir, ENGINE_NAME);
        if (!target.isFile()) {
            if (!versionDir.isDirectory() && !versionDir.mkdirs()) {
                throw new IOException("Não foi possível criar " + versionDir);
            }
            File temporary = new File(versionDir, ENGINE_NAME + ".partial");
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("Não foi possível limpar o arquivo temporário anterior.");
            }
            copy(downloadedLibrary, temporary);
            try {
                verifyEngine(temporary, sha);
                makeReadOnly(temporary);
                if (!temporary.renameTo(target)) {
                    throw new IOException("Não foi possível finalizar o arquivo do runtime.");
                }
            } finally {
                if (temporary.exists() && !temporary.delete()) {
                    Log.w(TAG, "Não foi possível remover arquivo temporário: " + temporary);
                }
            }
        }

        writeRuntimeMetadata(versionDir, safeVersion, sha, upstreamTag);
        if (previousActive != null && !previousActive.version.equals(safeVersion)) {
            writeSelection(root, PREVIOUS_NAME, previousActive.version, previousActive.sha256);
        }
        writeActive(root, safeVersion, sha);
        }
    }

    /** Returns true only when the stored metadata and ARM64 ELF still verify. */
    public static synchronized boolean hasInstalledVersion(Context context, String version) {
        try {
            readInstalledVersion(context, version);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Lists installed versions only after validating each stored SHA-256 and ELF header. */
    public static synchronized List<InstalledVersion> listInstalledVersions(Context context)
            throws IOException {
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), "horizon-runtime").getCanonicalFile();
        File versions = new File(root, "versions");
        if (!versions.isDirectory()) return Collections.emptyList();
        File[] directories = versions.listFiles();
        if (directories == null) throw new IOException("Não foi possível listar as runtimes instaladas.");
        Active active = readValidActive(versions);
        ArrayList<InstalledVersion> result = new ArrayList<>();
        for (File directory : directories) {
            if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) continue;
            String version = directory.getName();
            if (!VERSION_PATTERN.matcher(version).matches() || version.contains("..")) continue;
            try {
                InstalledVersion installed = readInstalledVersion(context, version);
                result.add(new InstalledVersion(installed.version, installed.upstreamTag,
                        installed.sha256, installed.installedAt,
                        active != null && active.version.equals(installed.version)));
            } catch (Exception invalid) {
                Log.w(TAG, "Ignorando runtime local inválida " + version + ": " + invalid.getMessage());
            }
        }
        result.sort(Comparator.comparingLong((InstalledVersion item) -> item.installedAt).reversed());
        return Collections.unmodifiableList(result);
    }

    /** Activates an installed version without downloading or modifying its binary. */
    public static synchronized void activateInstalledVersion(Context context, String version)
            throws IOException {
        InstalledVersion installed = readInstalledVersion(context, version);
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), "horizon-runtime").getCanonicalFile();
        File versions = new File(root, "versions");
        try (FileChannel lockChannel = FileChannel.open(
                     new File(root, ".runtime-manager.lock").toPath(),
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignoredLock = lockChannel.lock()) {
            Active current = readValidActive(versions);
            if (current != null && current.version.equals(installed.version)) return;
            if (current != null) {
                writeSelection(root, PREVIOUS_NAME, current.version, current.sha256);
            }
            writeActive(root, installed.version, installed.sha256);
        }
    }

    private static InstalledVersion readInstalledVersion(Context context, String version)
            throws IOException {
        String safeVersion = requireVersion(version);
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), "horizon-runtime").getCanonicalFile();
        File versions = new File(root, "versions").getCanonicalFile();
        File directory = new File(versions, safeVersion).getCanonicalFile();
        if (!directory.getPath().startsWith(versions.getPath() + File.separator)
                || Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory()) {
            throw new IOException("Runtime não instalada: " + safeVersion);
        }
        File metadata = new File(directory, "runtime.json");
        if (!metadata.isFile()) {
            // Older releases only stored the hash in active.json/previous.json. Migrate those
            // verified selections forward so they remain visible and pinnable after an app update.
            Active legacy = readValidSelection(versions, ACTIVE_NAME);
            if (legacy == null || !safeVersion.equals(legacy.version)) {
                legacy = readValidSelection(versions, PREVIOUS_NAME);
            }
            if (legacy == null || !safeVersion.equals(legacy.version)) {
                throw new IOException("Metadados da runtime ausentes para " + safeVersion);
            }
            writeRuntimeMetadata(directory, safeVersion, legacy.sha256,
                    HorizonRuntimeUpdateService.upstreamTagFromRuntimeVersion(safeVersion));
        }
        if (!metadata.isFile() || metadata.length() <= 0 || metadata.length() > 16 * 1024
                || Files.isSymbolicLink(metadata.toPath())) {
            throw new IOException("Metadados da runtime ausentes para " + safeVersion);
        }
        try (FileInputStream input = new FileInputStream(metadata)) {
            byte[] bytes = new byte[(int) metadata.length()];
            int count = input.read(bytes);
            if (count <= 0) throw new IOException("Metadados da runtime vazios.");
            JSONObject json = new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
            String recordedVersion = requireVersion(json.optString("version", ""));
            String sha = requireSha256(json.optString("sha256", ""));
            String tag = json.optString("upstream_tag", "");
            if (!safeVersion.equals(recordedVersion)
                    || (!tag.isEmpty() && !HorizonRuntimeUpdateService.isValidUpstreamTag(tag))) {
                throw new IOException("Metadados da runtime não correspondem ao diretório.");
            }
            File library = new File(directory, ENGINE_NAME).getCanonicalFile();
            if (!library.getPath().startsWith(directory.getPath() + File.separator)) {
                throw new IOException("Biblioteca saiu do diretório da runtime.");
            }
            verifyEngine(library, sha);
            return new InstalledVersion(safeVersion, tag, sha,
                    Math.max(0L, json.optLong("installed_at", directory.lastModified())), false);
        } catch (org.json.JSONException error) {
            throw new IOException("Metadados JSON da runtime inválidos.", error);
        }
    }

    private static void writeRuntimeMetadata(File versionDirectory, String version, String sha,
                                             String upstreamTag) throws IOException {
        String tag = upstreamTag == null ? "" : upstreamTag.trim();
        if (!tag.isEmpty() && !HorizonRuntimeUpdateService.isValidUpstreamTag(tag)) {
            throw new IOException("Tag oficial inválida nos metadados da runtime.");
        }
        JSONObject json = new JSONObject();
        try {
            json.put("format", 1);
            json.put("version", version);
            json.put("sha256", sha);
            json.put("upstream_tag", tag);
            json.put("installed_at", System.currentTimeMillis());
        } catch (org.json.JSONException error) {
            throw new IOException("Não foi possível montar os metadados da runtime.", error);
        }
        File temporary = new File(versionDirectory, "runtime.json.partial");
        File target = new File(versionDirectory, "runtime.json");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write((json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        try {
            Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
        } catch (android.system.ErrnoException error) {
            temporary.delete();
            throw new IOException("Não foi possível salvar os metadados da runtime.", error);
        }
    }

    private static final class Active {
        final String version;
        final String sha256;
        final File library;
        Active(String version, String sha256, File library) {
            this.version = version;
            this.sha256 = sha256;
            this.library = library;
        }
    }

    private static Active readValidActive(File versions) {
        return readValidSelection(versions, ACTIVE_NAME);
    }

    private static Active readValidSelection(File versions, String selectionName) {
        File root = versions.getParentFile();
        File selectionFile = new File(root, selectionName);
        if (!selectionFile.isFile() || selectionFile.length() <= 0
                || selectionFile.length() > 16 * 1024) return null;
        try (FileInputStream input = new FileInputStream(selectionFile)) {
            byte[] bytes = new byte[(int) selectionFile.length()];
            int count = input.read(bytes);
            if (count <= 0) return null;
            JSONObject json = new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
            String version = requireVersion(json.optString("version", ""));
            String sha = requireSha256(json.optString("sha256", ""));
            File library = new File(new File(versions, version), ENGINE_NAME).getCanonicalFile();
            String versionsPath = versions.getCanonicalPath() + File.separator;
            if (!library.getPath().startsWith(versionsPath)) return null;
            verifyEngine(library, sha);
            makeReadOnly(library);
            return new Active(version, sha, library);
        } catch (Exception error) {
            Log.w(TAG, "Registro de runtime " + selectionName + " inválido: " + error.getMessage());
            return null;
        }
    }

    private static void writeActive(File root, String version, String sha) throws IOException {
        writeSelection(root, ACTIVE_NAME, version, sha);
    }

    private static void writeSelection(File root, String selectionName,
                                       String version, String sha) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("version", version);
            json.put("sha256", sha);
        } catch (org.json.JSONException impossible) {
            throw new IOException("Não foi possível montar o registro de runtime.", impossible);
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Não foi possível criar " + root);
        }
        File temporary = new File(root, selectionName + ".partial");
        File target = new File(root, selectionName);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write((json.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
        } catch (android.system.ErrnoException error) {
            temporary.delete();
            throw new IOException("Não foi possível atualizar atomicamente a seleção de runtime.", error);
        }
    }

    private static void deleteSelection(File root, String selectionName) throws IOException {
        File target = new File(root, selectionName);
        if (target.exists() && !target.delete()) {
            throw new IOException("Não foi possível remover a seleção de runtime " + selectionName);
        }
    }

    private static void copy(File source, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("Não foi possível limpar o runtime temporário anterior.");
        }
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            long written = 0;
            while ((count = input.read(buffer)) != -1) {
                written += count;
                if (written > MAX_ENGINE_BYTES) {
                    throw new IOException("A biblioteca Horizon excede o limite de tamanho.");
                }
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
    }

    private static void verifyEngine(File file, String expectedSha) throws IOException {
        if (!file.isFile() || file.length() < 4096 || file.length() > MAX_ENGINE_BYTES) {
            throw new IOException("Arquivo de runtime ausente ou com tamanho inválido: " + file);
        }
        byte[] header = new byte[20];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) throw new IOException("Cabeçalho ELF incompleto: " + file);
                offset += count;
            }
        }
        // ELF64, little-endian, e_machine == EM_AARCH64 (183).
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || header[4] != 2 || header[5] != 1
                || (header[18] & 0xff) != 183 || (header[19] & 0xff) != 0) {
            throw new IOException("O runtime não é uma biblioteca ELF64 Android ARM64.");
        }
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expectedSha)) {
            throw new IOException("SHA-256 do runtime não confere. Esperado "
                    + expectedSha + ", recebido " + actual);
        }
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 não está disponível.", impossible);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }

    private static void makeReadOnly(File file) throws IOException {
        try {
            // Required by Android's dynamic-code-loading protections: engine files are immutable
            // before dlopen. New versions are installed into a new version directory.
            Os.chmod(file.getAbsolutePath(), 0500);
        } catch (android.system.ErrnoException error) {
            throw new IOException("Não foi possível tornar o runtime somente leitura.", error);
        }
    }

    private static String requireVersion(String value) throws IOException {
        if (value == null || value.contains("..") || !VERSION_PATTERN.matcher(value).matches()) {
            throw new IOException("Identificador de versão do runtime inválido.");
        }
        return value;
    }

    private static String requireSha256(String value) throws IOException {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("SHA-256 do runtime inválido.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String readSmallText(File file) {
        if (!file.isFile() || file.length() > 8192) return "";
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (IOException ignored) {
            return "";
        }
    }
}
