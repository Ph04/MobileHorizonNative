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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Selects a separately compiled Android ARM64 Horizon runtime for one exact set of
 * imported native source bundles. A normal runtime is never substituted when this
 * set has no matching build: doing so would silently ignore native mods.
 */
final class HorizonNativeRuntimeManager {
    private static final String REPOSITORY = "Ph04/MobileHorizonNative";
    private static final String RELEASE_PREFIX = "horizon-native-android-";
    private static final String MANIFEST_ASSET = "horizon-native-runtime-manifest.json";
    private static final String ENGINE_ASSET = "horizon-native-runtime-arm64-v8a.so";
    private static final String ENGINE_FILE = "libhorizon_engine.so";
    private static final long MAX_ENGINE_BYTES = 300L * 1024L * 1024L;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final String TRUST_PREFS = "horizon_native_mod_trust";

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

    private static final class NativeFile {
        final File file;
        final HorizonNativeModArchive.Manifest manifest;
        final String sha256;

        NativeFile(File file, HorizonNativeModArchive.Manifest manifest, String sha256) {
            this.file = file;
            this.manifest = manifest;
            this.sha256 = sha256;
        }
    }

    private HorizonNativeRuntimeManager() { }

    static boolean hasNativeMods(File instanceHome) {
        File directory = new File(instanceHome, "horizon-native-mods");
        File[] files = directory.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".hnmod")) return true;
            if (name.endsWith(".hmod")) {
                try {
                    if (HorizonNativeModArchive.readIfPresent(file) != null) return true;
                } catch (Exception ignored) { }
            }
        }
        return false;
    }

    static String buildKey(File instanceHome) throws IOException {
        List<NativeFile> files = nativeFiles(instanceHome);
        if (files.isEmpty()) throw new IOException("Nenhum pacote .hnmod instalado nesta instância.");
        return buildKey(files);
    }

    static boolean isTrusted(Context context, File instanceHome) throws IOException {
        List<NativeFile> files = nativeFiles(instanceHome);
        if (files.isEmpty()) return false;
        android.content.SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE);
        String instanceKey = instanceKey(instanceHome);
        for (NativeFile nativeFile : files) {
            String key = trustKey(instanceKey, nativeFile);
            if (!prefs.getBoolean(key, false)) return false;
        }
        return true;
    }

    static void setTrusted(Context context, File instanceHome, String modId, boolean trusted)
            throws IOException {
        List<NativeFile> files = nativeFiles(instanceHome);
        NativeFile selected = null;
        for (NativeFile nativeFile : files) {
            if (nativeFile.manifest.id.equals(modId)) {
                selected = nativeFile;
                break;
            }
        }
        if (selected == null) {
            throw new IOException("Não foi encontrado o pacote nativo " + modId + " nesta instância.");
        }
        context.getApplicationContext().getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(trustKey(instanceKey(instanceHome), selected), trusted).apply();
    }

    static boolean isTrusted(Context context, File instanceHome, String modId) {
        try {
            List<NativeFile> files = nativeFiles(instanceHome);
            String instanceKey = instanceKey(instanceHome);
            for (NativeFile nativeFile : files) {
                if (nativeFile.manifest.id.equals(modId)) {
                    return context.getApplicationContext()
                            .getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE)
                            .getBoolean(trustKey(instanceKey, nativeFile), false);
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    static PreparedRuntime prepare(Context context, File instanceHome) throws IOException {
        Context app = context.getApplicationContext();
        List<NativeFile> mods = nativeFiles(instanceHome);
        if (mods.isEmpty()) {
            throw new IOException("Nenhum mod nativo está instalado nesta instância.");
        }
        if (!isTrusted(app, instanceHome)) {
            throw new IOException("Os mods nativos ainda não foram autorizados. Abra Biblioteca → Gerenciador de recursos e marque Confiar no código para cada pacote cuja origem você verificou.");
        }

        String key = buildKey(mods);
        String tag = RELEASE_PREFIX + key.substring(0, 16);
        String releaseUrl = "https://api.github.com/repos/" + REPOSITORY
                + "/releases/tags/" + tag;
        JSONObject release;
        try {
            release = new JSONObject(new String(readUrl(releaseUrl, 1024 * 1024),
                    StandardCharsets.UTF_8));
        } catch (IOException missing) {
            throw new IOException("Ainda não existe runtime ARM64 compilada para este conjunto de mods ("
                    + tag + "). Para compilar: adicione os mesmos arquivos originais à pasta native-mods/ "
                    + "do repositório " + REPOSITORY + " na branch feature/fabric-java-bridge-test e "
                    + "faça um commit com [trust-native-mods] na mensagem. Esse marcador confirma que "
                    + "você revisou e confia no código Rust; o workflow então compila e publica a runtime. "
                    + "Acompanhe em https://github.com/" + REPOSITORY
                    + "/actions/workflows/native-mod-android-runtime.yml", missing);
        } catch (org.json.JSONException invalid) {
            throw new IOException("A resposta da release do runtime nativo não contém JSON válido.", invalid);
        }

        if (!tag.equals(release.optString("tag_name", ""))
                || release.optBoolean("draft", true)) {
            throw new IOException("A release do runtime nativo não corresponde ao conjunto de mods desta instância.");
        }
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new IOException("A release do runtime nativo não contém assets.");
        String manifestUrl = assetUrl(assets, MANIFEST_ASSET, tag);
        String engineUrl = assetUrl(assets, ENGINE_ASSET, tag);
        if (manifestUrl == null || engineUrl == null) {
            throw new IOException("A release do runtime nativo não contém o manifesto ou a biblioteca ARM64.");
        }

        JSONObject manifest;
        try {
            manifest = new JSONObject(new String(readUrl(manifestUrl, MAX_MANIFEST_BYTES),
                    StandardCharsets.UTF_8));
        } catch (org.json.JSONException invalid) {
            throw new IOException("O manifesto do runtime nativo é inválido.", invalid);
        }
        validateManifest(manifest, mods, key, tag);

        String expectedSha = manifest.optString("sha256", "").toLowerCase(Locale.ROOT);
        if (!expectedSha.matches("[0-9a-f]{64}")) {
            throw new IOException("O manifesto não contém SHA-256 válido para a biblioteca.");
        }

        File root = new File(app.getFilesDir(), "horizon-native-runtimes").getCanonicalFile();
        ensureDirectory(root);
        // android-bootstrap only accepts downloaded engines under <runtime-root>/versions/<tag>/.
        File versions = new File(root, "versions").getCanonicalFile();
        if (!root.equals(versions.getParentFile())) {
            throw new IOException("A pasta versions do runtime nativo saiu da raiz privada.");
        }
        ensureDirectory(versions);
        File versionDir = new File(versions, tag).getCanonicalFile();
        if (!versions.equals(versionDir.getParentFile())) {
            throw new IOException("O caminho da versão nativa saiu da pasta versions.");
        }
        ensureDirectory(versionDir);
        File library = new File(versionDir, ENGINE_FILE).getCanonicalFile();
        if (!versionDir.equals(library.getParentFile()) || Files.isSymbolicLink(library.toPath())) {
            throw new IOException("O caminho da biblioteca nativa é inseguro.");
        }

        if (!isValidEngine(library, expectedSha)) {
            File partial = new File(versionDir, "." + ENGINE_FILE + ".partial");
            if (Files.isSymbolicLink(partial.toPath())) {
                throw new IOException("O arquivo temporário do runtime não pode ser link simbólico.");
            }
            if (partial.exists() && !partial.delete()) {
                throw new IOException("Não foi possível limpar a transferência anterior do runtime nativo.");
            }
            download(engineUrl, partial);
            if (!isValidEngine(partial, expectedSha)) {
                partial.delete();
                throw new IOException("O runtime baixado não corresponde ao SHA-256/ELF ARM64 do manifesto.");
            }
            makeExecutable(partial);
            try {
                Files.move(partial.toPath(), library.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(partial.toPath(), library.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                if (partial.exists()) partial.delete();
            }
        }
        if (!isValidEngine(library, expectedSha)) {
            throw new IOException("A biblioteca nativa instalada falhou na verificação final.");
        }
        makeExecutable(library);

        File status = new File(versionDir, "loader-status.txt");
        if (status.exists() && !status.delete()) {
            throw new IOException("Não foi possível limpar o estado de inicialização anterior do runtime nativo.");
        }
        return new PreparedRuntime(root, library, status, tag);
    }

    private static List<NativeFile> nativeFiles(File instanceHome) throws IOException {
        File directory = new File(instanceHome, "horizon-native-mods").getCanonicalFile();
        if (!directory.exists()) return new ArrayList<>();
        if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) {
            throw new IOException("A pasta horizon-native-mods não é um diretório seguro.");
        }
        File[] candidates = directory.listFiles();
        List<NativeFile> mods = new ArrayList<>();
        if (candidates == null) throw new IOException("Não foi possível listar os mods nativos da instância.");
        for (File file : candidates) {
            if (!file.isFile() || Files.isSymbolicLink(file.toPath())) continue;
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".hnmod") && !lower.endsWith(".hmod")) continue;
            HorizonNativeModArchive.Manifest manifest;
            if (lower.endsWith(".hnmod")) {
                manifest = HorizonNativeModArchive.read(file);
            } else {
                manifest = HorizonNativeModArchive.readIfPresent(file);
                if (manifest == null) continue;
            }
            if (!OfficialMinecraftInstaller.VERSION.equals(manifest.game)) {
                throw new IOException("O mod nativo " + manifest.id + " foi feito para Minecraft "
                        + manifest.game + ", não para " + OfficialMinecraftInstaller.VERSION + ".");
            }
            mods.add(new NativeFile(file, manifest, sha256(file)));
        }
        Collections.sort(mods, (a, b) -> a.manifest.id.compareTo(b.manifest.id));
        for (int i = 1; i < mods.size(); i++) {
            if (mods.get(i - 1).manifest.id.equals(mods.get(i).manifest.id)) {
                throw new IOException("Há dois pacotes nativos com o mesmo ID: " + mods.get(i).manifest.id);
            }
        }
        return mods;
    }

    private static String buildKey(List<NativeFile> mods) throws IOException {
        MessageDigest digest = digest();
        for (NativeFile mod : mods) {
            digest.update(mod.manifest.id.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(hexBytes(mod.sha256));
        }
        return hex(digest.digest());
    }

    private static void validateManifest(JSONObject manifest, List<NativeFile> mods,
                                         String expectedKey, String expectedTag) throws IOException {
        if (manifest.optInt("format", -1) != 1
                || !expectedTag.equals(manifest.optString("tag", ""))
                || !expectedKey.equals(manifest.optString("mods_sha256", ""))
                || !"arm64-v8a".equals(manifest.optString("abi", ""))
                || !ENGINE_ASSET.equals(manifest.optString("library", ""))) {
            throw new IOException("O manifesto do runtime nativo não corresponde ao conjunto de mods, formato ou ABI desta instalação.");
        }
        String upstream = manifest.optString("upstream_commit", "");
        String mixins = manifest.optString("mixins_revision", "");
        if (!upstream.matches("[0-9a-f]{40}") || !mixins.matches("[0-9a-f]{40}")) {
            throw new IOException("O manifesto do runtime nativo não contém revisões completas e verificáveis.");
        }
        JSONArray bundledMods = manifest.optJSONArray("mods");
        if (bundledMods == null || bundledMods.length() != mods.size()) {
            throw new IOException("A lista de mods compilados não corresponde aos arquivos instalados.");
        }
        for (int i = 0; i < mods.size(); i++) {
            JSONObject item = bundledMods.optJSONObject(i);
            NativeFile local = mods.get(i);
            if (item == null
                    || !local.manifest.id.equals(item.optString("id", ""))
                    || !local.manifest.version.equals(item.optString("version", ""))
                    || !local.sha256.equalsIgnoreCase(item.optString("sha256", ""))) {
                throw new IOException("O runtime publicado não foi compilado com o arquivo local "
                        + local.manifest.id + " desta instância.");
            }
        }
    }

    private static String assetUrl(JSONArray assets, String name, String tag) throws IOException {
        String expected = "https://github.com/" + REPOSITORY
                + "/releases/download/" + tag + "/" + name;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && name.equals(asset.optString("name", ""))) {
                String url = asset.optString("browser_download_url", "");
                if (!expected.equals(url)) {
                    throw new IOException("A URL do asset " + name + " não é a URL fixa esperada.");
                }
                long size = asset.optLong("size", -1);
                long maximum = MANIFEST_ASSET.equals(name) ? MAX_MANIFEST_BYTES : MAX_ENGINE_BYTES;
                if (size <= 0 || size > maximum) {
                    throw new IOException("O tamanho publicado de " + name + " está fora do limite.");
                }
                return url;
            }
        }
        return null;
    }

    private static byte[] readUrl(String url, int limit) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " ao consultar o runtime nativo.");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > limit) {
                        throw new IOException("A resposta do runtime nativo excede o limite.");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        URL parsed = new URL(url);
        if (!"https".equals(parsed.getProtocol())
                || !("api.github.com".equals(parsed.getHost())
                || "github.com".equals(parsed.getHost())
                || "release-assets.githubusercontent.com".equals(parsed.getHost()))) {
            throw new IOException("Host de download não autorizado para o runtime nativo.");
        }
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(90_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative/NativeModRuntime");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        return connection;
    }

    private static void download(String url, File target) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " ao baixar a biblioteca nativa.");
            }
            long declared = connection.getContentLengthLong();
            if (declared <= 0 || declared > MAX_ENGINE_BYTES) {
                throw new IOException("Tamanho declarado da biblioteca nativa inválido.");
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_ENGINE_BYTES || total > declared) {
                        throw new IOException("A biblioteca nativa excedeu o tamanho permitido.");
                    }
                    output.write(buffer, 0, count);
                }
                output.flush();
                output.getFD().sync();
                if (total != declared) throw new IOException("Download incompleto da biblioteca nativa.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isValidEngine(File file, String expectedSha) {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile()
                || file.length() < 4096 || file.length() > MAX_ENGINE_BYTES) return false;
        try {
            if (!expectedSha.equalsIgnoreCase(sha256(file))) return false;
            try (InputStream input = new FileInputStream(file)) {
                byte[] header = new byte[20];
                int count = input.read(header);
                if (count != header.length || header[0] != 0x7f || header[1] != 'E'
                        || header[2] != 'L' || header[3] != 'F'
                        || header[4] != 2 || header[5] != 1) return false;
                int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
                return machine == 183;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String instanceKey(File home) throws IOException {
        return hex(digest().digest(home.getCanonicalPath().getBytes(StandardCharsets.UTF_8)))
                .substring(0, 12);
    }

    private static String trustKey(String instanceKey, NativeFile file) {
        return "trusted_" + instanceKey + "_" + file.manifest.id + "_" + file.sha256.substring(0, 16);
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static byte[] hexBytes(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IOException("SHA-256 do arquivo nativo inválido.");
        }
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value));
        return out.toString();
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory == null || (!directory.isDirectory()
                && !directory.mkdirs() && !directory.isDirectory())) {
            throw new IOException("Não foi possível criar o diretório " + directory);
        }
        if (Files.isSymbolicLink(directory.toPath())) {
            throw new IOException("O diretório de runtime não pode ser link simbólico.");
        }
    }

    private static void makeExecutable(File library) throws IOException {
        if (!library.setReadable(true, true) || !library.setExecutable(true, true)) {
            throw new IOException("Não foi possível definir permissões para a biblioteca nativa.");
        }
    }
}
