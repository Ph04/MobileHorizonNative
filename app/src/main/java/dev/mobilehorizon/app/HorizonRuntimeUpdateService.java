package dev.mobilehorizon.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the project's published ARM64 Horizon-engine releases on startup and every 30 minutes
 * while MainActivity is visible. The APK is not replaced; only a verified versioned engine is
 * installed, and it takes effect on the next game launch.
 */
public final class HorizonRuntimeUpdateService {
    private static final String TAG = "HorizonRuntimeUpdate";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/Ph04/MobileHorizonNative/releases?per_page=100";
    private static final String TAG_PREFIX = "horizon-runtime-";
    private static final String ENGINE_ASSET = "horizon-runtime-arm64-v8a.so";
    private static final String TEST_FIXTURE_TAG = "horizon-runtime-update-test";
    private static final String TEST_FIXTURE_URL = "https://api.github.com/repos/Ph04/MobileHorizonNative/releases/tags/" + TEST_FIXTURE_TAG;
    private static final String PC_RELEASE_TAG = "pc_release_tag";
    private static final String TEST_SEED_DONE = "test_old_runtime_seed_done";
    private static final String PREFERENCES = "horizon_runtime_updates";
    private static final String LAST_CHECK = "last_check_ms";
    private static final String LAST_STATUS = "last_status";
    private static final long CHECK_INTERVAL_MS = 30L * 60L * 1000L;
    private static final long MAX_DOWNLOAD_BYTES = 300L * 1024L * 1024L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private HorizonRuntimeUpdateService() {}

    public static void checkInBackground(Context context) {
        checkInBackground(context, null, false);
    }

    /** Checks immediately when the player requests it, bypassing only the normal cooldown. */
    public static void checkNow(Context context, Runnable onFinished) {
        checkInBackground(context, onFinished, true);
    }

    public static void checkInBackground(Context context, Runnable onFinished) {
        checkInBackground(context, onFinished, false);
    }

    private static void checkInBackground(Context context, Runnable onFinished, boolean force) {
        Context app = context.getApplicationContext();
        SharedPreferences preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (!preferences.getBoolean("enabled", true)) {
            postFinished(onFinished);
            return;
        }
        long now = System.currentTimeMillis();
        long previous = preferences.getLong(LAST_CHECK, 0L);
        if ((!force && now - previous < CHECK_INTERVAL_MS)
                || !IN_FLIGHT.compareAndSet(false, true)) {
            postFinished(onFinished);
            return;
        }
        preferences.edit().putLong(LAST_CHECK, now).apply();

        EXECUTOR.execute(() -> {
            try {
                checkAndInstall(app, preferences);
            } catch (Exception error) {
                String message = "Falha ao verificar atualizações do Horizon: " + error.getMessage();
                preferences.edit().putString(LAST_STATUS, message).apply();
                Log.w(TAG, message, error);
            } finally {
                IN_FLIGHT.set(false);
                postFinished(onFinished);
            }
        });
    }

    private static void postFinished(Runnable callback) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
    }

    /** Enables/disables automatic runtime checks. A disable setting never removes installed versions. */
    public static void setEnabled(Context context, boolean enabled) {
        context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).apply();
    }

    public static String lastStatus(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(LAST_STATUS, "Ainda não foi verificada uma atualização do Horizon.");
    }

    /** The PC release tag represented by the selected runtime, for the launcher UI. */
    public static String pcReleaseLabel(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String tag = preferences.getString(PC_RELEASE_TAG, BuildConfig.HORIZON_PC_RELEASE_TAG);
        return tag == null || tag.trim().isEmpty() ? BuildConfig.HORIZON_PC_RELEASE_TAG : tag;
    }

    private static void checkAndInstall(Context context, SharedPreferences preferences) throws Exception {
        if (BuildConfig.HORIZON_TEST_OLD_RUNTIME
                && !preferences.getBoolean(TEST_SEED_DONE, false)) {
            Release previous = testFixtureRuntime(BuildConfig.HORIZON_TEST_OLD_RELEASE_TAG);
            installRelease(context, preferences, previous);
            preferences.edit().putBoolean(TEST_SEED_DONE, true).apply();
            setStatus(preferences, "Teste do atualizador: baixada a versão anterior do Horizon PC "
                    + previous.upstreamTag + ". A próxima verificação atualizará para "
                    + BuildConfig.HORIZON_PC_RELEASE_TAG + ".");
            return;
        }

        String currentVersion = HorizonRuntimeManager.currentVersion(context);
        Release latest = BuildConfig.HORIZON_TEST_OLD_RUNTIME
                ? testFixtureRuntime(BuildConfig.HORIZON_PC_RELEASE_TAG)
                : latestRuntimeRelease();
        if (latest == null) {
            setStatus(preferences, "Nenhum release Android do Horizon foi encontrado.");
            return;
        }
        if (latest.version.equals(currentVersion)) {
            preferences.edit().putString(PC_RELEASE_TAG, latest.upstreamTag).apply();
            setStatus(preferences, "Horizon está atualizado — release PC " + latest.upstreamTag
                    + " (" + currentVersion + ").");
            return;
        }

        Log.i(TAG, "Atualização de motor encontrada: PC " + latest.upstreamTag
                + ", motor " + latest.version + "; atual " + currentVersion);
        installRelease(context, preferences, latest);
        setStatus(preferences, "Motor Horizon PC " + latest.upstreamTag
                + " baixado e verificado; será ativado na próxima inicialização do jogo.");
    }

    private static void installRelease(Context context, SharedPreferences preferences,
                                       Release release) throws Exception {
        File manifestDownload = new File(context.getCacheDir(),
                "horizon-runtime-" + release.version + ".manifest.part");
        File download = new File(context.getCacheDir(),
                "horizon-runtime-" + release.version + ".part");
        try {
            download(release.manifestUrl, release.manifestSize, release.manifestSha256, manifestDownload);
            JSONObject manifest = new JSONObject(readSmallFile(manifestDownload, 64 * 1024));
            String upstreamCommit = manifest.optString("upstream_commit", "");
            String upstreamTag = manifest.optString("upstream_tag", "");
            String overlay = manifest.optString("overlay", "");
            String expectedVersion = upstreamCommit.matches("(?i)[0-9a-f]{40}")
                    && overlay.matches("(?i)[0-9a-f]{8}")
                    && upstreamTag.matches("v[0-9]+\\\\.[0-9]+\\\\.[0-9]+")
                    ? "horizon-" + upstreamTag + "-c" + upstreamCommit.substring(0, 8)
                        + "-o" + overlay : "";
            if (manifest.optInt("format", -1) != 1
                    || !release.version.equals(manifest.optString("version", ""))
                    || !release.upstreamTag.equals(upstreamTag)
                    || !release.version.equals(expectedVersion)
                    || !"arm64-v8a".equals(manifest.optString("abi", ""))
                    || !release.engineAssetName.equals(manifest.optString("library", ""))
                    || !release.sha256.equalsIgnoreCase(manifest.optString("sha256", ""))) {
                throw new IOException("O manifesto do runtime não corresponde à versão PC "
                        + release.upstreamTag + " e aos metadados do release.");
            }
            download(release.url, release.size, release.sha256, download);
            HorizonRuntimeManager.installAndActivate(context, download, release.version, release.sha256);
            preferences.edit().putString(PC_RELEASE_TAG, release.upstreamTag).apply();
            Log.i(TAG, "Runtime ativado para a próxima inicialização: PC "
                    + release.upstreamTag + ", motor " + release.version);
        } finally {
            if (manifestDownload.exists() && !manifestDownload.delete()) {
                Log.w(TAG, "Não foi possível apagar o manifesto temporário " + manifestDownload);
            }
            if (download.exists() && !download.delete()) {
                Log.w(TAG, "Não foi possível apagar o download temporário " + download);
            }
        }
    }

    private static Release latestRuntimeRelease() throws Exception {
        JSONArray releases = new JSONArray(readUrl(RELEASES_URL, 4 * 1024 * 1024));
        Release latest = null;
        String latestPublishedAt = "";
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft", false)
                    || release.optBoolean("prerelease", false)) continue;
            String tag = release.optString("tag_name", "");
            if (!tag.startsWith(TAG_PREFIX)) continue;
            String version = tag.substring(TAG_PREFIX.length());
            String upstreamTag = upstreamTagFromRuntimeVersion(version);
            if (upstreamTag.isEmpty()) continue;
            Release candidate = releaseFromAssets(release, version, upstreamTag,
                    ENGINE_ASSET, "horizon-runtime-manifest.json");
            if (latest == null || comparePcReleaseTags(candidate.upstreamTag, latest.upstreamTag) > 0
                    || (comparePcReleaseTags(candidate.upstreamTag, latest.upstreamTag) == 0
                        && candidate.publishedAt.compareTo(latestPublishedAt) > 0)) {
                latest = candidate;
                latestPublishedAt = candidate.publishedAt;
            }
        }
        return latest;
    }

    /** Test APK only: the prerelease contains a real old Android engine and the current engine. */
    private static Release testFixtureRuntime(String wantedPcTag) throws Exception {
        JSONObject release = new JSONObject(readUrl(TEST_FIXTURE_URL, 4 * 1024 * 1024));
        if (!TEST_FIXTURE_TAG.equals(release.optString("tag_name", ""))
                || !release.optBoolean("prerelease", false)
                || release.optBoolean("draft", false)) {
            throw new IOException("O pacote de teste do atualizador não está publicado como prerelease.");
        }
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new IOException("O pacote de teste não contém assets.");
        String version = null;
        String engineName = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String prefix = "horizon-runtime-horizon-";
            String suffix = "-arm64-v8a.so";
            if (!name.startsWith(prefix) || !name.endsWith(suffix)) continue;
            String candidateVersion = name.substring("horizon-runtime-".length(),
                    name.length() - suffix.length());
            if (wantedPcTag.equals(upstreamTagFromRuntimeVersion(candidateVersion))) {
                version = candidateVersion;
                engineName = name;
                break;
            }
        }
        if (version == null || engineName == null) {
            throw new IOException("O pacote de teste não contém o motor Android para PC "
                    + wantedPcTag + ".");
        }
        String manifestName = "horizon-runtime-" + version + "-manifest.json";
        return releaseFromAssets(release, version, wantedPcTag, engineName, manifestName);
    }

    private static Release releaseFromAssets(JSONObject release, String version,
                                             String upstreamTag, String engineName,
                                             String manifestName) throws Exception {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new IOException("O release não contém uma lista de assets.");
        JSONObject engine = null;
        JSONObject manifest = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (engineName.equals(name)) engine = asset;
            if (manifestName.equals(name)) manifest = asset;
        }
        if (engine == null || manifest == null) {
            throw new IOException("O release " + upstreamTag + " está sem o binário ARM64 ou manifesto.");
        }
        String digest = requireDigest(engine.optString("digest", ""), "motor");
        String manifestDigest = requireDigest(manifest.optString("digest", ""), "manifesto");
        String url = requireReleaseAssetUrl(engine.optString("browser_download_url", ""));
        String manifestUrl = requireReleaseAssetUrl(manifest.optString("browser_download_url", ""));
        long size = engine.optLong("size", -1);
        long manifestSize = manifest.optLong("size", -1);
        if (size <= 0 || size > MAX_DOWNLOAD_BYTES || manifestSize <= 0 || manifestSize > 64 * 1024) {
            throw new IOException("Tamanho inválido nos assets do runtime " + upstreamTag + ".");
        }
        return new Release(version, upstreamTag, engineName, digest, url, size,
                release.optString("published_at", ""), manifestUrl, manifestDigest, manifestSize);
    }

    private static String upstreamTagFromRuntimeVersion(String version) {
        if (version == null || !version.startsWith("horizon-")) return "";
        int separator = version.indexOf("-c", "horizon-".length());
        if (separator < 0) return "";
        String tag = version.substring("horizon-".length(), separator);
        return tag.matches("v[0-9]+\\\\.[0-9]+\\\\.[0-9]+") ? tag : "";
    }

    private static int comparePcReleaseTags(String left, String right) {
        Pattern semantic = Pattern.compile("v([0-9]+)\\\\.([0-9]+)\\\\.([0-9]+)");
        Matcher a = semantic.matcher(left == null ? "" : left);
        Matcher b = semantic.matcher(right == null ? "" : right);
        if (a.matches() && b.matches()) {
            for (int i = 1; i <= 3; i++) {
                int comparison = Long.compare(Long.parseLong(a.group(i)), Long.parseLong(b.group(i)));
                if (comparison != 0) return comparison;
            }
        }
        return (left == null ? "" : left).compareTo(right == null ? "" : right);
    }

    private static void download(String address, long expectedSize, String expectedSha256,
                                 File destination) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative-RuntimeUpdater/1.0");
        connection.setRequestProperty("Accept", "application/octet-stream");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download do runtime respondeu HTTP " + code);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_DOWNLOAD_BYTES || (contentLength > 0 && contentLength != expectedSize)) {
                throw new IOException("Tamanho do runtime não corresponde aos metadados do release.");
            }
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Não foi possível criar o diretório de download.");
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > MAX_DOWNLOAD_BYTES || written > expectedSize) {
                        throw new IOException("O download excedeu o tamanho esperado.");
                    }
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
                if (written != expectedSize) {
                    throw new IOException("Download incompleto: recebido " + written
                            + " bytes, esperado " + expectedSize + ".");
                }
            }
            String downloadedDigest = sha256(destination);
            if (!downloadedDigest.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("SHA-256 do asset baixado não confere.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readUrl(String address, int maximumBytes) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative-RuntimeUpdater/1.0");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub Releases respondeu HTTP " + code);
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maximumBytes) {
                        throw new IOException("Resposta do GitHub excedeu o limite.");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String sha256(File file) throws IOException {
        final java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 não está disponível.", impossible);
        }
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT,
                "%02x", value & 0xff));
        return text.toString();
    }

    private static void setStatus(SharedPreferences preferences, String status) {
        preferences.edit().putString(LAST_STATUS, status).apply();
        Log.i(TAG, status);
    }

    private static final class Release {
        final String version;
        final String upstreamTag;
        final String engineAssetName;
        final String sha256;
        final String url;
        final long size;
        final String publishedAt;
        final String manifestUrl;
        final String manifestSha256;
        final long manifestSize;

        Release(String version, String upstreamTag, String engineAssetName, String sha256,
                String url, long size, String publishedAt, String manifestUrl,
                String manifestSha256, long manifestSize) {
            this.version = version;
            this.upstreamTag = upstreamTag;
            this.engineAssetName = engineAssetName;
            this.sha256 = sha256;
            this.url = url;
            this.size = size;
            this.publishedAt = publishedAt;
            this.manifestUrl = manifestUrl;
            this.manifestSha256 = manifestSha256;
            this.manifestSize = manifestSize;
        }
    }
}
