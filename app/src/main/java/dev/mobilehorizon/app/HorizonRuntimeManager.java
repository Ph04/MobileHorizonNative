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
import java.util.regex.Pattern;

/**
 * Selects and verifies the native Horizon engine before NativeActivity starts.
 *
 * The APK contains a small, stable libhorizon_android.so bootstrap plus a signed-build
 * baseline engine asset. Downloaded engines are installed into app-private version folders,
 * never over a library that might already be mapped by the current process.
 */
public final class HorizonRuntimeManager {
    private static final String TAG = "HorizonRuntime";
    private static final String ASSET_ROOT = "horizon-runtime/";
    private static final String ENGINE_NAME = "libhorizon_engine.so";
    private static final String MANIFEST_NAME = "manifest.json";
    private static final String ACTIVE_NAME = "active.json";
    private static final String STATUS_NAME = "loader-status.txt";
    private static final long MAX_ENGINE_BYTES = 300L * 1024L * 1024L;
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}");

    private HorizonRuntimeManager() {}

    public static final class PreparedRuntime {
        public final File root;
        public final File activeLibrary;
        public final File bundledLibrary;
        public final File statusFile;
        public final String activeVersion;
        public final String bundledVersion;

        private PreparedRuntime(File root, File activeLibrary, File bundledLibrary,
                                File statusFile, String activeVersion, String bundledVersion) {
            this.root = root;
            this.activeLibrary = activeLibrary;
            this.bundledLibrary = bundledLibrary;
            this.statusFile = statusFile;
            this.activeVersion = activeVersion;
            this.bundledVersion = bundledVersion;
        }
    }

    /** Read the selected version without copying assets; safe for the updater's background check. */
    public static synchronized String currentVersion(Context context) throws IOException {
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), "horizon-runtime").getCanonicalFile();
        File versions = new File(root, "versions");
        Active active = readValidActive(versions);
        if (active != null) return active.version;
        JSONObject manifest = readBundledManifest(app);
        return requireVersion(manifest.optString("version", ""));
    }

    /** Ensures the bundled baseline exists, repairs a failed previous switch, and selects a runtime. */
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
        JSONObject manifest = readBundledManifest(app);
        String bundledVersion = requireVersion(manifest.optString("version", ""));
        String bundledSha = requireSha256(manifest.optString("sha256", ""));
        String assetName = manifest.optString("library", ENGINE_NAME);
        if (!ENGINE_NAME.equals(assetName)) {
            throw new IOException("O manifesto aponta para uma biblioteca inesperada: " + assetName);
        }

        File bundledLibrary = installBundledBaseline(app, versions, bundledVersion, bundledSha);
        File status = new File(root, STATUS_NAME);
        String previousStatus = readSmallText(status);
        if (previousStatus.startsWith("FALLBACK:") || previousStatus.startsWith("ERROR:")\n                || previousStatus.startsWith("STARTING:")) {
            // The native bootstrap could not start the selected engine on the prior attempt.
            // Return to the APK-provided baseline before another game launch.
            writeActive(root, bundledVersion, bundledSha);
            Log.e(TAG, "Runtime anterior falhou ao carregar; revertendo para " + bundledVersion
                    + ": " + previousStatus);
        }
        if (status.exists() && !status.delete()) {
            Log.w(TAG, "Não foi possível remover o marcador nativo antigo: " + status);
        }

        Active active = readValidActive(versions);
        if (active == null) {
            writeActive(root, bundledVersion, bundledSha);
            active = new Active(bundledVersion, bundledSha,
                    new File(new File(versions, bundledVersion), ENGINE_NAME));
        }

        // Recheck the selected binary before passing its path into native code.
        verifyEngine(active.library, active.sha256);
        return new PreparedRuntime(root, active.library.getCanonicalFile(),
                bundledLibrary.getCanonicalFile(), status.getCanonicalFile(),
                active.version, bundledVersion);
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
        if (target.isFile()) {
            try {
                verifyEngine(target, sha);
                makeReadOnly(target);
            } catch (IOException wrongExistingBuild) {
                throw new IOException("Já existe outra biblioteca para a versão " + safeVersion
                        + "; use uma versão/commit único para cada binário.", wrongExistingBuild);
            }
        } else {
            if (!versionDir.isDirectory() && !versionDir.mkdirs()) {
                throw new IOException("Não foi possível criar " + versionDir);
            }
            File temporary = new File(versionDir, ENGINE_NAME + ".partial");
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

        writeActive(root, safeVersion, sha);
        }
    }

    /** Force the next launch to use the engine shipped in the APK. */
    public static synchronized void rollbackToBundled(Context context) throws IOException {
        Context app = context.getApplicationContext();
        JSONObject manifest = readBundledManifest(app);
        String version = requireVersion(manifest.optString("version", ""));
        String sha = requireSha256(manifest.optString("sha256", ""));
        File root = new File(app.getFilesDir(), "horizon-runtime").getCanonicalFile();
        File versions = new File(root, "versions");
        if (!versions.isDirectory() && !versions.mkdirs()) {
            throw new IOException("Não foi possível criar " + versions);
        }
        try (FileChannel lockChannel = FileChannel.open(
                     new File(root, ".runtime-manager.lock").toPath(),
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignoredLock = lockChannel.lock()) {
            installBundledBaseline(app, versions, version, sha);
            writeActive(root, version, sha);
        }
    }

    private static JSONObject readBundledManifest(Context context) throws IOException {
        try (InputStream input = context.getAssets().open(ASSET_ROOT + MANIFEST_NAME)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 64 * 1024) {
                    throw new IOException("Manifesto do runtime excede o tamanho permitido.");
                }
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } catch (org.json.JSONException error) {
            throw new IOException("Manifesto do runtime inválido.", error);
        }
    }

    private static File installBundledBaseline(Context context, File versions,
                                                String version, String sha) throws IOException {
        File versionDir = new File(versions, version);
        File target = new File(versionDir, ENGINE_NAME);
        if (target.isFile()) {
            try {
                verifyEngine(target, sha);
                makeReadOnly(target);
                return target;
            } catch (IOException invalidExisting) {
                // Do not overwrite an existing path: a previous process may still have it mapped.
                File quarantined = new File(versions,
                        ".corrupt-" + version + "-" + System.currentTimeMillis());
                if (!versionDir.renameTo(quarantined)) {
                    throw new IOException("A biblioteca base existente está inválida e não pôde "
                            + "ser isolada: " + target, invalidExisting);
                }
            }
        }
        if (!versionDir.isDirectory() && !versionDir.mkdirs()) {
            throw new IOException("Não foi possível criar " + versionDir);
        }
        File temporary = new File(versionDir, ENGINE_NAME + ".partial");
        try (InputStream input = context.getAssets().open(ASSET_ROOT + ENGINE_NAME);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        } catch (IOException error) {
            if (temporary.exists()) temporary.delete();
            throw error;
        }
        try {
            verifyEngine(temporary, sha);
            makeReadOnly(temporary);
            if (!temporary.renameTo(target)) {
                throw new IOException("Não foi possível finalizar o runtime base.");
            }
            return target;
        } finally {
            if (temporary.exists()) temporary.delete();
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
        File root = versions.getParentFile();
        File activeFile = new File(root, ACTIVE_NAME);
        if (!activeFile.isFile()) return null;
        try (FileInputStream input = new FileInputStream(activeFile)) {
            byte[] bytes = new byte[(int) Math.min(activeFile.length(), 16 * 1024)];
            int count = input.read(bytes);
            if (count <= 0) return null;
            JSONObject json = new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
            String version = requireVersion(json.optString("version", ""));
            String sha = requireSha256(json.optString("sha256", ""));
            File library = new File(new File(versions, version), ENGINE_NAME).getCanonicalFile();
            String versionsPath = versions.getCanonicalPath() + File.separator;
            if (!library.getPath().startsWith(versionsPath)) return null;
            verifyEngine(library, sha);
            return new Active(version, sha, library);
        } catch (Exception error) {
            Log.w(TAG, "Registro de runtime ativo inválido; usando o runtime base.", error);
            return null;
        }
    }

    private static void writeActive(File root, String version, String sha) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("version", version);
            json.put("sha256", sha);
        } catch (org.json.JSONException impossible) {
            throw new IOException("Não foi possível montar o registro de runtime.", impossible);
        }
        File temporary = new File(root, ACTIVE_NAME + ".partial");
        File target = new File(root, ACTIVE_NAME);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Não foi possível criar " + root);
        }
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write((json.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
        } catch (android.system.ErrnoException error) {
            temporary.delete();
            throw new IOException("Não foi possível ativar atomicamente o runtime.", error);
        }
    }

    private static void copy(File source, File target) throws IOException {
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
            if (input.read(header) != header.length) {
                throw new IOException("Cabeçalho ELF incompleto: " + file);
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
