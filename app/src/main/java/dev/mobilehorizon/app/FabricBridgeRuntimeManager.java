package dev.mobilehorizon.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Separate, opt-in runtime channel for iaxivers/horizon's Fabric Java bridge.
 * Vanilla continues to use HorizonRuntimeManager and its normal updater.
 */
final class FabricBridgeRuntimeManager {
    static final String LOADER_VERSION = "0.20.1";
    static final String UPSTREAM_COMMIT = "4bd3fee1b05ab89c047b8212d314b4de0912c077";
    static final String RELEASE_TAG = "fabric-java-bridge-4bd3fee1";
    static final String ENGINE_ASSET = "fabric-horizon-runtime-arm64-v8a.so";
    static final String MANIFEST_ASSET = "fabric-horizon-runtime-manifest.json";

    private static final String API_URL = "https://api.github.com/repos/Ph04/MobileHorizonNative/releases/tags/"
            + RELEASE_TAG;
    private static final String RELEASE_DOWNLOAD_PREFIX =
            "https://github.com/Ph04/MobileHorizonNative/releases/download/" + RELEASE_TAG + "/";
    private static final long MAX_ENGINE_BYTES = 300L * 1024L * 1024L;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int BUFFER_SIZE = 128 * 1024;

    interface ProgressListener {
        void onProgress(String stage, long bytes, long total);
    }

    static final class PreparedRuntime {
        final File root;
        final File activeLibrary;
        final File statusFile;
        final String activeVersion;

        PreparedRuntime(File root, File activeLibrary, File statusFile, String activeVersion) {
            this.root = root;
            this.activeLibrary = activeLibrary;
            this.statusFile = statusFile;
            this.activeVersion = activeVersion;
        }
    }

    private FabricBridgeRuntimeManager() { }

    static boolean isInstalled(Context context) {
        try {
            activeRuntime(context);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static String installedVersion(Context context) {
        try {
            return activeRuntime(context).activeVersion;
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Downloads and atomically activates the separately versioned Fabric engine. */
    static synchronized PreparedRuntime install(Context context, ProgressListener listener)
            throws Exception {
        Context app = context.getApplicationContext();
        File root = root(app);
        ensureDirectory(root);
        publish(listener, "Consultando o runtime Fabric Java Bridge…", 0L, -1L);

        JSONObject release = new JSONObject(new String(
                readUrl(API_URL, 1024 * 1024), StandardCharsets.UTF_8));
        if (!RELEASE_TAG.equals(release.optString("tag_name", ""))
                || release.optBoolean("draft", true)) {
            throw new IOException("A release de teste do Fabric Java Bridge não está publicada.");
        }
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new IOException("A release Fabric não contém arquivos.");

        String manifestUrl = findAssetUrl(assets, MANIFEST_ASSET);
        if (manifestUrl == null) throw new IOException("O manifesto do runtime Fabric está ausente.");
        publish(listener, "Baixando manifesto do Fabric Java Bridge…", 0L, -1L);
        byte[] manifestBytes = readUrl(manifestUrl, MAX_MANIFEST_BYTES);
        JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        String version = requireVersion(manifest.optString("version", ""));
        String upstreamCommit = manifest.optString("upstream_commit", "");
        String bridgeCommit = manifest.optString("bridge_commit", "");
        String abi = manifest.optString("abi", "");
        String library = manifest.optString("library", "");
        String digest = manifest.optString("sha256", "").toLowerCase(java.util.Locale.ROOT);
        String overlay = manifest.optString("overlay", "");
        if (manifest.optInt("format", -1) != 1
                || !UPSTREAM_COMMIT.equals(upstreamCommit)
                || !UPSTREAM_COMMIT.equals(bridgeCommit)
                || !"iaxivers/horizon".equals(manifest.optString("upstream_repository", ""))
                || !"java-bridge".equals(manifest.optString("upstream_ref", ""))
                || !"26.3".equals(manifest.optString("minecraft_version", ""))
                || !LOADER_VERSION.equals(manifest.optString("fabric_loader_version", ""))
                || !"0.165.0+26.3".equals(manifest.optString("fabric_api_version", ""))
                || !"arm64-v8a".equals(abi)
                || !ENGINE_ASSET.equals(library)
                || !overlay.matches("[0-9a-f]{8}")
                || !digest.matches("[0-9a-f]{64}")
                || !version.startsWith("fabric-java-bridge-" + UPSTREAM_COMMIT.substring(0, 8) + "-o")) {
            throw new IOException("O manifesto não corresponde ao bridge Fabric Java esperado.");
        }

        String engineUrl = findAssetUrl(assets, ENGINE_ASSET);
        if (engineUrl == null) throw new IOException("O motor Android ARM64 do Fabric está ausente.");
        File versions = new File(root, "versions");
        ensureDirectory(versions);
        File activeDirectory = new File(versions, version);
        File activeLibrary = new File(activeDirectory, ENGINE_ASSET);
        if (activeLibrary.isFile() && digest.equals(sha256(activeLibrary))
                && isArm64Elf(activeLibrary)) {
            writeActive(root, version, digest);
            return activeRuntime(app);
        }

        File stage = new File(root, "." + version + ".installing");
        deleteRecursively(stage);
        ensureDirectory(stage);
        File partial = new File(stage, ENGINE_ASSET + ".part");
        try {
            publish(listener, "Baixando motor Fabric Java Bridge…", 0L, -1L);
            download(engineUrl, partial, digest, listener);
            if (!isArm64Elf(partial)) {
                throw new IOException("O arquivo baixado não é uma biblioteca Android ARM64 válida.");
            }
            File stagedLibrary = new File(stage, ENGINE_ASSET);
            if (!partial.renameTo(stagedLibrary)) {
                copyFile(partial, stagedLibrary);
                if (!partial.delete()) partial.deleteOnExit();
            }
            writeText(new File(stage, "manifest.json"),
                    new String(manifestBytes, StandardCharsets.UTF_8));

            File previous = new File(root, "." + version + ".previous");
            deleteRecursively(previous);
            if (activeDirectory.exists() && !activeDirectory.renameTo(previous)) {
                throw new IOException("Não foi possível guardar o runtime Fabric anterior.");
            }
            if (!stage.renameTo(activeDirectory)) {
                if (previous.exists()) previous.renameTo(activeDirectory);
                throw new IOException("Não foi possível ativar o runtime Fabric.");
            }
            try {
                if (!digest.equals(sha256(activeLibrary)) || !isArm64Elf(activeLibrary)) {
                    throw new IOException("A validação do runtime Fabric após ativação falhou.");
                }
                writeActive(root, version, digest);
            } catch (Exception error) {
                deleteRecursively(activeDirectory);
                if (previous.exists()) previous.renameTo(activeDirectory);
                throw error;
            }
            deleteRecursively(previous);
            publish(listener, "Runtime Fabric Java Bridge instalado e verificado.", 1L, 1L);
            return activeRuntime(app);
        } catch (Exception error) {
            deleteRecursively(stage);
            throw error;
        }
    }

    /** Prepares an already installed Fabric runtime; game startup never downloads files. */
    static PreparedRuntime prepare(Context context) throws IOException {
        return activeRuntime(context.getApplicationContext());
    }

    private static PreparedRuntime activeRuntime(Context context) throws IOException {
        File root = root(context);
        File active = new File(root, "active.json");
        if (Files.isSymbolicLink(active.toPath()) || !active.isFile()
                || active.length() <= 0 || active.length() > 16 * 1024) {
            throw new IOException("O runtime Fabric Java Bridge ainda não foi instalado. Abra Módulos de compatibilidade e instale-o antes de iniciar esta instância.");
        }
        JSONObject json = new JSONObject(readSmallFile(active, 16 * 1024));
        String version = requireVersion(json.optString("version", ""));
        String digest = json.optString("sha256", "").toLowerCase(java.util.Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IOException("O marcador do runtime Fabric não contém SHA-256 válido.");
        }
        File versions = new File(root, "versions").getCanonicalFile();
        File directory = new File(versions, version).getCanonicalFile();
        if (!versions.equals(directory.getParentFile())) {
            throw new IOException("O runtime Fabric saiu do diretório privado esperado.");
        }
        File library = new File(directory, ENGINE_ASSET);
        if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(library.toPath())
                || !library.isFile() || library.length() < 4096
                || library.length() > MAX_ENGINE_BYTES
                || !digest.equals(sha256(library)) || !isArm64Elf(library)) {
            throw new IOException("O runtime Fabric instalado não passou na validação SHA-256/ELF.");
        }
        File status = new File(root, "loader-status.txt");
        return new PreparedRuntime(root, library, status, version);
    }

    private static File root(Context context) throws IOException {
        File files = context.getFilesDir().getCanonicalFile();
        File root = new File(files, "fabric-horizon-runtime");
        if (!files.equals(root.getCanonicalFile().getParentFile())) {
            throw new IOException("Diretório privado do runtime Fabric inválido.");
        }
        return root;
    }

    private static String findAssetUrl(JSONArray assets, String name) {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !name.equals(asset.optString("name", ""))) continue;
            String url = asset.optString("browser_download_url", "");
            if (url.startsWith(RELEASE_DOWNLOAD_PREFIX) && url.length() <= 2048) return url;
            return null;
        }
        return null;
    }

    private static byte[] readUrl(String address, int limit) throws IOException {
        URL url = checkedUrl(address);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative-FabricBridge/1.0");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode() + " ao consultar o runtime Fabric.");
            }
            requireReleaseHost(connection.getURL());
            long length = connection.getContentLengthLong();
            if (length > limit) throw new IOException("Arquivo do runtime Fabric excede o limite permitido.");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > limit) throw new IOException("Arquivo do runtime Fabric excede o limite permitido.");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static URL checkedUrl(String address) throws IOException {
        URL url = new URL(address);
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !(host.equals("api.github.com") || host.equals("github.com"))) {
            throw new IOException("Host não permitido para o runtime Fabric.");
        }
        return url;
    }

    private static void requireReleaseHost(URL url) throws IOException {
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !(host.equals("api.github.com") || host.equals("github.com")
                || host.equals("release-assets.githubusercontent.com")
                || host.equals("objects.githubusercontent.com"))) {
            throw new IOException("O redirecionamento do download Fabric usou um host não permitido.");
        }
    }

    private static void download(String address, File target, String expectedSha,
                                 ProgressListener listener) throws Exception {
        URL url = checkedUrl(address);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative-FabricBridge/1.0");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " ao baixar o motor Fabric.");
            }
            requireReleaseHost(connection.getURL());
            long total = connection.getContentLengthLong();
            if (total <= 0 || total > MAX_ENGINE_BYTES) {
                throw new IOException("Tamanho do motor Fabric inválido.");
            }
            ensureDirectory(target.getParentFile());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long received = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    received += count;
                    if (received > MAX_ENGINE_BYTES || received > total) {
                        throw new IOException("Download do motor Fabric excedeu o tamanho anunciado.");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    publish(listener, "Baixando motor Fabric Java Bridge…", received, total);
                }
                output.flush();
                output.getFD().sync();
            }
            if (received != total || !expectedSha.equals(hex(digest.digest()))) {
                throw new IOException("SHA-256/tamanho do motor Fabric não corresponde ao manifesto.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isArm64Elf(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] header = new byte[20];
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) return false;
                offset += count;
            }
            return (header[0] & 0xff) == 0x7f && header[1] == 'E' && header[2] == 'L'
                    && header[3] == 'F' && header[4] == 2 && header[5] == 1
                    && (header[18] & 0xff) == 183 && header[19] == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private static String readSmallFile(File file, int limit) throws IOException {
        if (file.length() <= 0 || file.length() > limit) throw new IOException("Arquivo JSON inválido.");
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Arquivo JSON excede o limite.");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeActive(File root, String version, String digest) throws IOException {
        JSONObject active = new JSONObject();
        try {
            active.put("format", 1);
            active.put("version", version);
            active.put("sha256", digest);
            writeText(new File(root, "active.json"), active.toString(2) + "\n");
        } catch (org.json.JSONException impossible) {
            throw new IOException("Não foi possível gravar o marcador do runtime Fabric.", impossible);
        }
    }

    private static void writeText(File target, String value) throws IOException {
        ensureDirectory(target.getParentFile());
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
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

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Não foi possível criar " + directory);
        }
        if (Files.isSymbolicLink(directory.toPath())) {
            throw new IOException("Diretório do runtime Fabric não pode ser link simbólico.");
        }
    }

    private static void deleteRecursively(File path) {
        if (path == null || !path.exists()) return;
        if (path.isDirectory() && !Files.isSymbolicLink(path.toPath())) {
            File[] children = path.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        path.delete();
    }

    private static String requireVersion(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}")) {
            throw new IOException("Versão do runtime Fabric inválida.");
        }
        return value;
    }

    private static void publish(ProgressListener listener, String stage, long bytes, long total) {
        if (listener != null) listener.onProgress(stage, bytes, total);
    }
}
