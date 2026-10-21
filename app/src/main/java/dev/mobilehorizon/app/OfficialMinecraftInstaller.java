package dev.mobilehorizon.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Official Mojang file downloader for the engine snapshot currently supported by this app.
 * It downloads binaries from Mojang-owned HTTPS endpoints, verifies SHA-1 and size from
 * Mojang metadata (and pinned game-jar hashes), and atomically promotes each verified file.
 *
 * This is not account authentication and does not bypass Minecraft ownership checks.
 */
final class OfficialMinecraftInstaller {
    static final String VERSION = "26.3";
    static final String CLIENT_SHA1 = "e877b6a07acd633fb3bb475002175cec036e7b87";
    static final String SERVER_SHA1 = "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c";
    private static final String VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String VERIFIED_VERSION_RECEIPT_MAGIC = "mobilehorizon-version-json-sha1-v1";
    private static final Set<String> ACCEPTED_VERSION_JSON_SHA1 = new HashSet<>();

    static {
        ACCEPTED_VERSION_JSON_SHA1.add("11f1aefe473eefba0af62e3286c33b692807b2eb");
        ACCEPTED_VERSION_JSON_SHA1.add("4fe1aa1ef8da1cb95c5bad1fb98890ca56dd8ca3");
        ACCEPTED_VERSION_JSON_SHA1.add("702fe59163c6ee6578607daa85811d9bc9c7cc40");
        // Updated official manifests are accepted through the downloader receipt,
        // which binds the exact JSON digest to Mojang metadata and the pinned JARs.
    }

    static boolean isAcceptedVersionJsonSha1(String value) {
        return value != null && ACCEPTED_VERSION_JSON_SHA1.contains(value.toLowerCase(Locale.ROOT));
    }
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_JSON_BYTES = 64 * 1024 * 1024;
    private static final int ASSET_THREADS = 3;
    private static final int BUFFER_SIZE = 128 * 1024;

    interface Listener {
        void onProgress(Progress progress);
    }

    static final class Progress {
        final String stage;
        final String item;
        final int completed;
        final int total;
        final long itemBytes;
        final long itemTotalBytes;
        final long completedBytes;
        final long totalBytes;

        Progress(String stage, String item, int completed, int total,
                 long itemBytes, long itemTotalBytes, long completedBytes, long totalBytes) {
            this.stage = stage;
            this.item = item;
            this.completed = completed;
            this.total = total;
            this.itemBytes = itemBytes;
            this.itemTotalBytes = itemTotalBytes;
            this.completedBytes = completedBytes;
            this.totalBytes = totalBytes;
        }

        float fraction() {
            if (total > 0) return Math.max(0f, Math.min(1f, (float) completed / (float) total));
            if (itemTotalBytes > 0) return Math.max(0f, Math.min(1f, (float) itemBytes / (float) itemTotalBytes));
            return -1f;
        }
    }

    static final class Result {
        final int librariesDownloadedOrVerified;
        final String assetIndexId;
        final String versionJsonSha1;
        final int uniqueAssetCount;
        final long uniqueAssetBytes;

        Result(int libraries, String assetIndexId, String jsonSha1, int assets, long assetBytes) {
            this.librariesDownloadedOrVerified = libraries;
            this.assetIndexId = assetIndexId;
            this.versionJsonSha1 = jsonSha1;
            this.uniqueAssetCount = assets;
            this.uniqueAssetBytes = assetBytes;
        }
    }

    private static final class Asset {
        final String hash;
        final long size;
        Asset(String hash, long size) { this.hash = hash; this.size = size; }
    }

    private static final class Totals {
        final AtomicLong bytesProcessed = new AtomicLong();
    }

    private OfficialMinecraftInstaller() {}

    static Result installBase(File minecraftRoot, AtomicBoolean cancelled, Listener listener) throws Exception {
        checkCancelled(cancelled);
        if (minecraftRoot == null) throw new IllegalArgumentException("Pasta de instalação inválida.");
        if (!minecraftRoot.isDirectory() && !minecraftRoot.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar a pasta privada do Minecraft.");
        }

        notify(listener, "Consultando o manifesto oficial", "version_manifest_v2.json", 0, 5, 0, -1, 0, -1);
        byte[] globalBytes = readUrl(VERSION_MANIFEST_URL, MAX_JSON_BYTES, cancelled);
        JSONObject global = new JSONObject(new String(globalBytes, StandardCharsets.UTF_8));
        JSONArray versions = global.optJSONArray("versions");
        if (versions == null) throw new IllegalStateException("O manifesto oficial não contém a lista de versões.");

        String versionUrl = null;
        String advertisedVersionSha1 = null;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject item = versions.optJSONObject(i);
            if (item != null && VERSION.equals(item.optString("id", ""))) {
                versionUrl = item.optString("url", "");
                advertisedVersionSha1 = item.optString("sha1", "");
                break;
            }
        }
        requireOfficialUrl(versionUrl, "URL do manifesto da versão");
        if (!isSha1(normalizeSha1(advertisedVersionSha1))) {
            throw new IllegalStateException(
                    "O manifesto global oficial não anuncia um SHA-1 válido para Minecraft " + VERSION + ".");
        }

        notify(listener, "Validando o manifesto da versão", VERSION + ".json", 1, 5, 0, -1, 0, -1);
        byte[] versionBytes = readUrl(versionUrl, MAX_JSON_BYTES, cancelled);
        String versionSha1 = sha1(versionBytes);
        if (!matchesAdvertisedVersionSha1(advertisedVersionSha1, versionSha1)) {
            throw new IllegalStateException(
                    "O SHA-1 do manifesto de Minecraft " + VERSION
                            + " não confere com o valor anunciado pelo manifesto global oficial. Esperado: "
                            + normalizeSha1(advertisedVersionSha1) + "; recebido: " + versionSha1
                            + ". Os arquivos não serão ativados.");
        }

        JSONObject version = new JSONObject(new String(versionBytes, StandardCharsets.UTF_8));
        if (!VERSION.equals(version.optString("id", ""))) {
            throw new IllegalStateException("O JSON obtido não descreve Minecraft " + VERSION + ".");
        }
        JSONObject javaVersion = version.optJSONObject("javaVersion");
        if (javaVersion == null || javaVersion.optInt("majorVersion", -1) != 25) {
            throw new IllegalStateException("O motor atual exige o manifesto de Minecraft " + VERSION + " com Java 25.");
        }
        JSONObject downloads = version.optJSONObject("downloads");
        JSONObject client = downloads == null ? null : downloads.optJSONObject("client");
        JSONObject server = downloads == null ? null : downloads.optJSONObject("server");
        if (client == null || server == null) throw new IllegalStateException("O JSON oficial não anuncia cliente e servidor.");
        if (!CLIENT_SHA1.equalsIgnoreCase(client.optString("sha1", ""))) {
            throw new IllegalStateException("O manifesto não corresponde ao SHA-1 fixado para o cliente " + VERSION + ".");
        }
        if (!SERVER_SHA1.equalsIgnoreCase(server.optString("sha1", ""))) {
            throw new IllegalStateException("O manifesto não corresponde ao SHA-1 fixado para o servidor " + VERSION + ".");
        }

        File versionDir = safeFile(minecraftRoot, "versions/" + VERSION);
        ensureDirectory(versionDir);
        File versionJsonFile = new File(versionDir, VERSION + ".json");
        writeAtomically(versionBytes, versionJsonFile);
        String verifiedReceipt = VERIFIED_VERSION_RECEIPT_MAGIC + "\n" + VERSION + "\n" + versionSha1 + "\n";
        writeAtomically(verifiedReceipt.getBytes(StandardCharsets.UTF_8),
                new File(versionDir, VERSION + ".json.verified-sha1"));

        notify(listener, "Baixando/verificando cliente", VERSION + ".jar", 2, 5, 0,
                client.optLong("size", -1), 0, -1);
        downloadArtifact(client, new File(versionDir, VERSION + ".jar"), CLIENT_SHA1,
                "cliente " + VERSION, cancelled,
                (bytes, expected) -> notify(listener, "Baixando/verificando cliente", VERSION + ".jar",
                        2, 5, bytes, expected, bytes, expected));

        notify(listener, "Baixando/verificando servidor", "server.jar", 3, 5, 0,
                server.optLong("size", -1), 0, -1);
        downloadArtifact(server, new File(minecraftRoot, "server.jar"), SERVER_SHA1,
                "servidor " + VERSION, cancelled,
                (bytes, expected) -> notify(listener, "Baixando/verificando servidor", "server.jar",
                        3, 5, bytes, expected, bytes, expected));

        JSONObject assetIndex = version.optJSONObject("assetIndex");
        if (assetIndex == null) throw new IllegalStateException("O manifesto não contém assetIndex.");
        String assetIndexId = assetIndex.optString("id", "");
        if (!isSafeSegment(assetIndexId)) throw new IllegalStateException("ID do índice de assets inválido.");
        File assetIndexFile = safeFile(minecraftRoot, "assets/indexes/" + assetIndexId + ".json");
        downloadArtifact(assetIndex, assetIndexFile, assetIndex.optString("sha1", ""),
                "índice de assets", cancelled,
                (bytes, expected) -> notify(listener, "Baixando índice de assets", assetIndexId + ".json",
                        4, 5, bytes, expected, bytes, expected));

        JSONArray libraries = version.optJSONArray("libraries");
        if (libraries == null) throw new IllegalStateException("O manifesto oficial não contém libraries.");
        List<String> allNames = new ArrayList<>();
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library != null) allNames.add(library.optString("name", ""));
        }

        List<JSONObject> artifacts = new ArrayList<>();
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null || !libraryAllowedForAndroid(library)) continue;
            if (!nativeLibraryMatchesForAndroid(library.optString("name", ""), allNames)) continue;
            JSONObject libDownloads = library.optJSONObject("downloads");
            JSONObject artifact = libDownloads == null ? null : libDownloads.optJSONObject("artifact");
            if (artifact == null) continue;
            String relative = artifact.optString("path", "");
            if (!isSafeRelativePath(relative)) {
                throw new IllegalStateException("O manifesto contém um caminho de biblioteca inseguro.");
            }
            String url = artifact.optString("url", "");
            requireOfficialUrl(url, "URL da biblioteca " + relative);
            artifacts.add(artifact);
        }

        int totalItems = artifacts.size();
        notify(listener, "Baixando bibliotecas", "0/" + totalItems, 0, Math.max(1, totalItems),
                0, -1, 0, -1);
        final AtomicInteger completeCounter = new AtomicInteger(0);
        for (JSONObject artifact : artifacts) {
            checkCancelled(cancelled);
            String relative = artifact.optString("path", "");
            File destination = safeFile(minecraftRoot, "libraries/" + relative);
            downloadArtifact(artifact, destination, artifact.optString("sha1", ""),
                    "biblioteca " + relative, cancelled,
                    (bytes, expected) -> notify(listener, "Baixando biblioteca", relative,
                            completeCounter.get(), Math.max(1, totalItems), bytes, expected, bytes, expected));
            int complete = completeCounter.incrementAndGet();
            notify(listener, "Bibliotecas verificadas", complete + "/" + totalItems, complete,
                    Math.max(1, totalItems), 0, -1, 0, -1);
        }

        notify(listener, "Arquivos base validados", "Minecraft " + VERSION, 5, 5, 0, -1, 0, -1);
        return new Result(artifacts.size(), assetIndexId, versionSha1, 0, 0);
    }

    /** Downloads every unique asset object from the official index; this can require hundreds of MB. */
    static int downloadAllAssets(File minecraftRoot, AtomicBoolean cancelled, Listener listener) throws Exception {
        File versionFile = new File(new File(new File(minecraftRoot, "versions"), VERSION), VERSION + ".json");
        if (!versionFile.isFile()) throw new IllegalStateException("Baixe primeiro os arquivos base do Minecraft " + VERSION + ".");
        JSONObject version = new JSONObject(readFileText(versionFile, MAX_JSON_BYTES));
        JSONObject indexMetadata = version.optJSONObject("assetIndex");
        if (indexMetadata == null) throw new IllegalStateException("Falta assetIndex no JSON da versão.");
        String indexId = indexMetadata.optString("id", "");
        if (!isSafeSegment(indexId)) throw new IllegalStateException("ID do índice de assets inválido.");
        File indexFile = safeFile(minecraftRoot, "assets/indexes/" + indexId + ".json");
        String expectedIndexSha1 = indexMetadata.optString("sha1", "");
        if (!indexFile.isFile() || !expectedIndexSha1.equalsIgnoreCase(sha1(indexFile))) {
            downloadArtifact(indexMetadata, indexFile, expectedIndexSha1, "índice de assets", cancelled,
                    (bytes, expected) -> notify(listener, "Atualizando índice de assets", indexId + ".json",
                            0, 1, bytes, expected, bytes, expected));
        }

        JSONObject index = new JSONObject(readFileText(indexFile, MAX_JSON_BYTES));
        JSONObject objects = index.optJSONObject("objects");
        if (objects == null) throw new IllegalStateException("O índice oficial não contém a tabela objects.");
        Set<String> unique = new HashSet<>();
        List<Asset> assets = new ArrayList<>();
        Iterator<String> names = objects.keys();
        long totalBytes = 0;
        while (names.hasNext()) {
            checkCancelled(cancelled);
            String name = names.next();
            JSONObject item = objects.optJSONObject(name);
            if (item == null) continue;
            String hash = item.optString("hash", "").toLowerCase(Locale.ROOT);
            long size = item.optLong("size", -1);
            if (!isSha1(hash) || size < 0) throw new IllegalStateException("Entrada inválida no índice de assets: " + name);
            if (unique.add(hash)) {
                assets.add(new Asset(hash, size));
                totalBytes += size;
                if (totalBytes > 4L * 1024L * 1024L * 1024L) {
                    throw new IllegalStateException("O índice de assets excede o limite de segurança de 4 GiB.");
                }
            }
        }
        if (assets.isEmpty()) throw new IllegalStateException("O índice oficial não lista assets.");
        final long totalAssetBytes = totalBytes;
        File objectsRoot = safeFile(minecraftRoot, "assets/objects");
        ensureDirectory(objectsRoot);
        Totals totals = new Totals();
        AtomicInteger completed = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(ASSET_THREADS);
        CompletionService<Void> service = new ExecutorCompletionService<>(pool);
        List<Future<Void>> futures = new ArrayList<>(assets.size());
        try {
            for (Asset asset : assets) {
                checkCancelled(cancelled);
                futures.add(service.submit(new Callable<Void>() {
                    @Override public Void call() throws Exception {
                        checkCancelled(cancelled);
                        File dest = safeFile(objectsRoot, asset.hash.substring(0, 2) + "/" + asset.hash);
                        String url = "https://resources.download.minecraft.net/" + asset.hash.substring(0, 2) + "/" + asset.hash;
                        AtomicLong itemProcessedBytes = new AtomicLong(0);
                        downloadVerified(url, dest, asset.hash, asset.size, "asset " + asset.hash,
                                cancelled, (bytes, expected) -> {
                                    long previous = itemProcessedBytes.getAndSet(bytes);
                                    long aggregate = totals.bytesProcessed.addAndGet(bytes - previous);
                                    OfficialMinecraftInstaller.notify(listener, "Baixando assets oficiais",
                                            "Asset " + asset.hash + " · " + formatBytes(bytes),
                                            completed.get(), assets.size(), bytes, expected,
                                            aggregate, totalAssetBytes);
                                });
                        int done = completed.incrementAndGet();
                        if (done == 1 || done == assets.size() || done % 10 == 0) {
                            OfficialMinecraftInstaller.notify(listener, "Assets oficiais verificados",
                                    done + "/" + assets.size(), done, assets.size(),
                                    0, -1, totals.bytesProcessed.get(), totalAssetBytes);
                        }
                        return null;
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                checkCancelled(cancelled);
                try {
                    service.take().get();
                } catch (ExecutionException error) {
                    for (Future<Void> future : futures) future.cancel(true);
                    Throwable cause = error.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new IllegalStateException("Falha ao baixar assets oficiais.", cause);
                }
            }
        } finally {
            for (Future<Void> future : futures) if (!future.isDone()) future.cancel(true);
            pool.shutdownNow();
        }
        notify(listener, "Assets oficiais concluídos", assets.size() + " arquivos únicos",
                assets.size(), assets.size(), totalBytes, totalBytes, totalBytes, totalBytes);
        return assets.size();
    }

    private interface ByteProgress {
        void onBytes(long currentFileBytes, long expectedFileBytes);
    }

    private static void downloadArtifact(JSONObject artifact, File destination, String pinnedSha1,
                                         String label, AtomicBoolean cancelled, ByteProgress progress) throws Exception {
        String url = artifact.optString("url", "");
        requireOfficialUrl(url, "URL de " + label);
        if (pinnedSha1 == null || !isSha1(pinnedSha1.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("O SHA-1 está ausente ou inválido no manifesto para " + label + ".");
        }
        long size = artifact.optLong("size", -1);
        if (size < 0) throw new IllegalStateException("O tamanho está ausente no manifesto para " + label + ".");
        downloadVerified(url, destination, pinnedSha1, size, label, cancelled, progress);
    }

    private static void downloadVerified(String url, File destination, String expectedSha1, long expectedSize,
                                         String label, AtomicBoolean cancelled, ByteProgress progress) throws Exception {
        checkCancelled(cancelled);
        if (destination.isFile() && expectedSize >= 0 && destination.length() == expectedSize
                && expectedSha1.equalsIgnoreCase(sha1(destination))) {
            if (progress != null) progress.onBytes(expectedSize, expectedSize);
            return;
        }
        ensureDirectory(destination.getParentFile());
        File partial = new File(destination.getParentFile(), destination.getName() + ".part");
        if (partial.exists() && !partial.delete()) throw new IllegalStateException("Não foi possível limpar o download parcial " + partial.getName() + ".");
        HttpURLConnection connection = null;
        try {
            URL parsed = new URL(url);
            requireOfficialUrl(url, "URL de " + label);
            URLConnection raw = parsed.openConnection();
            if (!(raw instanceof HttpURLConnection)) throw new IllegalStateException("O download não é HTTP(S).");
            connection = (HttpURLConnection) raw;
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "MobileHorizonNative/0.2 (official Minecraft artifact downloader)");
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IllegalStateException("Download de " + label + " falhou (HTTP " + response + ").");
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            long bytes = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    bytes += read;
                    if (expectedSize >= 0 && bytes > expectedSize) {
                        throw new IllegalStateException("O download de " + label + " excedeu o tamanho anunciado.");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    if (progress != null) progress.onBytes(bytes, expectedSize);
                }
                output.flush();
            }
            checkCancelled(cancelled);
            String actualSha1 = hex(digest.digest());
            if (expectedSize >= 0 && bytes != expectedSize) {
                throw new IllegalStateException("Tamanho incorreto em " + label + ": esperado " + expectedSize + " bytes, recebido " + bytes + ".");
            }
            if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
                throw new IllegalStateException("SHA-1 incorreto em " + label + ": esperado " + expectedSha1 + ", recebido " + actualSha1 + ".");
            }
            if (destination.exists() && !destination.delete()) throw new IllegalStateException("Não foi possível substituir " + destination.getName() + ".");
            if (!partial.renameTo(destination)) throw new IllegalStateException("Não foi possível ativar o arquivo verificado " + destination.getName() + ".");
        } catch (Exception error) {
            if (partial.exists()) partial.delete();
            throw error;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readUrl(String url, int limit, AtomicBoolean cancelled) throws Exception {
        checkCancelled(cancelled);
        requireOfficialUrl(url, "URL de metadados");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative/0.2 (official Minecraft file installer)");
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IllegalStateException("Metadados oficiais falharam (HTTP " + response + ").");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    if (output.size() + read > limit) throw new IllegalStateException("O JSON oficial excede o limite de tamanho.");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    static boolean libraryAllowedForAndroid(JSONObject library) {
        JSONArray rules = library.optJSONArray("rules");
        if (rules == null || rules.length() == 0) return true;
        boolean allowed = false;
        String release = System.getProperty("os.version", "");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            JSONObject os = rule.optJSONObject("os");
            if (os != null && !"linux".equals(os.optString("name", "linux"))) continue;
            if (os != null && os.has("arch")) {
                String archRegex = os.optString("arch", "");
                if (!regexMatch(archRegex, "aarch64") && !regexMatch(archRegex, "arm64")) continue;
            }
            if (os != null && os.has("version") && !regexMatch(os.optString("version", ""), release)) continue;
            JSONObject features = rule.optJSONObject("features");
            if (features != null && features.length() > 0) continue;
            if (rule.has("features") && features == null) continue;
            allowed = "allow".equals(rule.optString("action", ""));
        }
        return allowed;
    }

    static boolean nativeLibraryMatchesForAndroid(String name, List<String> allNames) {
        String[] parts = name.split(":", -1);
        String classifier = parts.length > 3 ? parts[3] : "";
        if (classifierEndsWith(classifier, "arm64") || classifierEndsWith(classifier, "aarch64")
                || classifierEndsWith(classifier, "aarch_64")) return true;
        if (classifierEndsWith(classifier, "x86_64") || classifierEndsWith(classifier, "x64")) return false;
        if (classifier.startsWith("natives-")) {
            return !allNames.contains(name + "-arm64") && !allNames.contains(name + "-aarch64");
        }
        return true;
    }

    private static boolean classifierEndsWith(String value, String suffix) {
        return value.equals(suffix) || (value.endsWith(suffix) && value.length() > suffix.length()
                && value.charAt(value.length() - suffix.length() - 1) == '-');
    }

    private static boolean regexMatch(String regex, String value) {
        try { return Pattern.compile(regex).matcher(value).matches(); }
        catch (RuntimeException error) { return false; }
    }

    static boolean isSafeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static boolean isSafeSegment(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,100}")
                && !".".equals(value) && !"..".equals(value);
    }

    static boolean isSha1(String value) {
        return value != null && value.matches("[0-9a-f]{40}");
    }

    static boolean hasVerifiedVersionJsonSha1(File versionJsonFile) {
        if (versionJsonFile == null || !versionJsonFile.isFile()) return false;
        try {
            File receipt = new File(versionJsonFile.getParentFile(),
                    versionJsonFile.getName() + ".verified-sha1");
            if (!receipt.isFile()) return false;
            String[] fields = readFileText(receipt, 256).trim().split("\\R");
            if (fields.length != 3 || !VERIFIED_VERSION_RECEIPT_MAGIC.equals(fields[0])
                    || !VERSION.equals(fields[1])) return false;
            return matchesAdvertisedVersionSha1(fields[2], sha1(versionJsonFile));
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean matchesAdvertisedVersionSha1(String advertised, String actual) {
        String expectedNormalized = normalizeSha1(advertised);
        String actualNormalized = normalizeSha1(actual);
        return isSha1(expectedNormalized) && isSha1(actualNormalized)
                && expectedNormalized.equals(actualNormalized);
    }

    private static String normalizeSha1(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static File safeFile(File root, String relative) throws Exception {
        if (!isSafeRelativePath(relative)) throw new IllegalArgumentException("Caminho de arquivo inseguro no manifesto.");
        File canonicalRoot = root.getCanonicalFile();
        File target = new File(canonicalRoot, relative).getCanonicalFile();
        if (!target.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IllegalArgumentException("Um caminho do manifesto escapou da pasta privada.");
        }
        return target;
    }

    private static void requireOfficialUrl(String value, String label) throws Exception {
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(label + " está vazia.");
        URL url = new URL(value);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        boolean official = host.equals("mojang.com") || host.endsWith(".mojang.com")
                || host.equals("minecraft.net") || host.endsWith(".minecraft.net");
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !official || url.getUserInfo() != null) {
            throw new IllegalStateException(label + " não aponta para um domínio HTTPS oficial reconhecido.");
        }
    }

    private static void writeAtomically(byte[] bytes, File destination) throws Exception {
        ensureDirectory(destination.getParentFile());
        File partial = new File(destination.getParentFile(), destination.getName() + ".part");
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            output.write(bytes);
            output.flush();
        }
        if (destination.exists() && !destination.delete()) throw new IllegalStateException("Não foi possível substituir " + destination.getName() + ".");
        if (!partial.renameTo(destination)) throw new IllegalStateException("Não foi possível gravar " + destination.getName() + ".");
    }

    private static String readFileText(File file, int limit) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > limit) throw new IllegalStateException("O JSON excede o limite permitido: " + file.getName());
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String sha1(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return output.toString();
    }

    private static void ensureDirectory(File directory) {
        if (directory == null) throw new IllegalStateException("Diretório de destino inválido.");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Não foi possível criar " + directory.getName() + ".");
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if ((cancelled != null && cancelled.get()) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Download cancelado.");
        }
    }

    private static void notify(Listener listener, String stage, String item, int completed, int total,
                               long itemBytes, long itemTotalBytes, long completedBytes, long totalBytes) {
        if (listener != null) listener.onProgress(new Progress(stage, item, completed, total,
                itemBytes, itemTotalBytes, completedBytes, totalBytes));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
