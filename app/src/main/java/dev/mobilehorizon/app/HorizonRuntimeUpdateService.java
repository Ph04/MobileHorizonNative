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
        Context app = context.getApplicationContext();
        SharedPreferences preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (!preferences.getBoolean("enabled", true)) return;
        long now = System.currentTimeMillis();
        long previous = preferences.getLong(LAST_CHECK, 0L);
        if (now - previous < CHECK_INTERVAL_MS || !IN_FLIGHT.compareAndSet(false, true)) return;
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
            }
        });
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
        File download = new File(context.getCacheDir(),
                "horizon-runtime-" + latest.version + ".part");
        try {
            download(latest.url, latest.size, download);
            HorizonRuntimeManager.installAndActivate(context, download, latest.version, latest.sha256);
            setStatus(preferences, "Horizon " + latest.version
                    + " foi verificado e será ativado na próxima inicialização do jogo.");
            Log.i(TAG, "Runtime instalado e ativado para a próxima inicialização: " + latest.version);
        } finally {
            if (download.exists() && !download.delete()) {
                Log.w(TAG, "Não foi possível apagar o download temporário " + download);
            }
        }
    }

    private static Release latestRuntimeRelease() throws Exception {
        JSONArray releases = new JSONArray(readUrl(RELEASES_URL, 4 * 1024 * 1024));
        Release latest = null;
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

            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null || !ENGINE_ASSET.equals(asset.optString("name", ""))) continue;
                String digest = asset.optString("digest", "");
                if (!digest.matches("(?i)sha256:[0-9a-f]{64}")) {
                    Log.w(TAG, "Release ignorado porque o GitHub não informou SHA-256: " + tag);
                    continue;
                }
                String url = asset.optString("browser_download_url", "");
                if (!url.startsWith("https://github.com/Ph04/MobileHorizonNative/releases/download/")) {
                    Log.w(TAG, "URL de runtime fora do repositório esperado: " + url);
                    continue;
                }
                long size = asset.optLong("size", -1);
                if (size <= 0 || size > MAX_DOWNLOAD_BYTES) continue;
                String publishedAt = release.optString("published_at", "");
                if (latest == null || publishedAt.compareTo(latest.publishedAt) > 0) {
                    latest = new Release(version, digest.substring("sha256:".length()), url,
                            size, publishedAt);
                }
            }
        }
        return latest;
    }

    private static void download(String address, long expectedSize, File destination) throws IOException {
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

        Release(String version, String sha256, String url, long size, String publishedAt) {
            this.version = version;
            this.sha256 = sha256;
            this.url = url;
            this.size = size;
            this.publishedAt = publishedAt;
        }
    }
}
