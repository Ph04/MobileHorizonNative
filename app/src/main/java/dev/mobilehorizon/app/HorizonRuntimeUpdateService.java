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

    private static void checkAndInstall(Context context, SharedPreferences preferences) throws Exception {
        String currentVersion = HorizonRuntimeManager.currentVersion(context);
        Release latest = latestRuntimeRelease();
        if (latest == null) {
            setStatus(preferences, "Nenhum release de runtime Android foi encontrado.");
            return;
        }
        if (latest.version.equals(currentVersion)) {
            setStatus(preferences, "Horizon está atualizado (" + currentVersion + ").");
            return;
        }

        Log.i(TAG, "Nova versão do motor Horizon: " + latest.version
                + "; atual: " + currentVersion);
        File manifestDownload = new File(context.getCacheDir(),
                "horizon-runtime-" + latest.version + ".manifest.part");
        File download = new File(context.getCacheDir(),
                "horizon-runtime-" + latest.version + ".part");
        try {
            download(latest.manifestUrl, latest.manifestSize, latest.manifestSha256, manifestDownload);
            JSONObject manifest = new JSONObject(readSmallFile(manifestDownload, 64 * 1024));
            String upstreamCommit = manifest.optString("upstream_commit", "");
            String overlay = manifest.optString("overlay", "");
            String expectedVersion = upstreamCommit.matches("(?i)[0-9a-f]{40}")
                    && overlay.matches("(?i)[0-9a-f]{8}")
                    ? "horizon-" + upstreamCommit.substring(0, 12) + "-o" + overlay : "";
            if (manifest.optInt("format", -1) != 1
                    || !latest.version.equals(manifest.optString("version", ""))
                    || !latest.version.equals(expectedVersion)
                    || !"arm64-v8a".equals(manifest.optString("abi", ""))
                    || !ENGINE_ASSET.equals(manifest.optString("library", ""))
                    || !latest.sha256.equalsIgnoreCase(manifest.optString("sha256", ""))) {
                throw new IOException("O manifesto do runtime não corresponde aos metadados do release.");
            }
            download(latest.url, latest.size, latest.sha256, download);
            HorizonRuntimeManager.installAndActivate(context, download, latest.version, latest.sha256);
            setStatus(preferences, "Horizon " + latest.version
                    + " foi verificado e será ativado na próxima inicialização do jogo.");
            Log.i(TAG, "Runtime instalado e ativado para a próxima inicialização: " + latest.version);
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
        JSONObject newest = null;
        String newestVersion = null;
        String newestDate = "";

        // Pick the newest published runtime release first. If its asset/checksum is broken,
        // fail closed rather than silently selecting an older runtime and downgrading the engine.
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft", false)
                    || release.optBoolean("prerelease", false)) continue;
            String tag = release.optString("tag_name", "");
            if (!tag.startsWith(TAG_PREFIX)) continue;
            String version = tag.substring(TAG_PREFIX.length());
            if (!version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}") || version.contains("..")) {
                Log.w(TAG, "Release de runtime com tag inválida ignorado: " + tag);
                continue;
            }
            String publishedAt = release.optString("published_at", "");
            if (newest == null || publishedAt.compareTo(newestDate) > 0) {
                newest = release;
                newestVersion = version;
                newestDate = publishedAt;
            }
        }
        if (newest == null) return null;

        JSONArray assets = newest.optJSONArray("assets");
        if (assets == null) throw new IOException("O release mais recente não contém assets.");
        JSONObject engine = null;
        JSONObject manifestAsset = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (ENGINE_ASSET.equals(name)) engine = asset;
            if ("horizon-runtime-manifest.json".equals(name)) manifestAsset = asset;
        }
        if (engine == null || manifestAsset == null) {
            throw new IOException("O release mais recente precisa conter o motor ARM64 e o manifesto.");
        }

        String digest = requireDigest(engine.optString("digest", ""), "motor");
        String manifestDigest = requireDigest(manifestAsset.optString("digest", ""), "manifesto");
        String url = requireReleaseAssetUrl(engine.optString("browser_download_url", ""));
        String manifestUrl = requireReleaseAssetUrl(manifestAsset.optString("browser_download_url", ""));
        long size = engine.optLong("size", -1);
        long manifestSize = manifestAsset.optLong("size", -1);
        if (size <= 0 || size > MAX_DOWNLOAD_BYTES || manifestSize <= 0 || manifestSize > 64 * 1024) {
            throw new IOException("Tamanho inválido nos assets do runtime " + newestVersion + ".");
        }
        return new Release(newestVersion, digest, url, size, newestDate,
                manifestUrl, manifestDigest, manifestSize);
    }

    private static String requireDigest(String value, String label) throws IOException {
        if (!value.matches("(?i)sha256:[0-9a-f]{64}")) {
            throw new IOException("O GitHub não forneceu SHA-256 confiável para o " + label + ".");
        }
        return value.substring("sha256:".length()).toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireReleaseAssetUrl(String url) throws IOException {
        if (!url.startsWith("https://github.com/Ph04/MobileHorizonNative/releases/download/")) {
            throw new IOException("URL de asset fora dos releases esperados do MobileHorizonNative.");
        }
        return url;
    }

    private static String readSmallFile(File file, int maximumBytes) throws IOException {
        if (!file.isFile() || file.length() > maximumBytes) {
            throw new IOException("Manifesto baixado ausente ou grande demais.");
        }
        try (InputStream input = new java.io.FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximumBytes) {
                    throw new IOException("Manifesto baixado excedeu o limite.");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
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
        final String sha256;
        final String url;
        final long size;
        final String publishedAt;
        final String manifestUrl;
        final String manifestSha256;
        final long manifestSize;

        Release(String version, String sha256, String url, long size, String publishedAt,
                String manifestUrl, String manifestSha256, long manifestSize) {
            this.version = version;
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
