package dev.mobilehorizon.app;

import android.content.Context;
import android.content.Intent;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the project's published ARM64 Horizon-engine releases on startup and every five minutes
 * while MainActivity is visible. The APK is not replaced; only a verified versioned engine is
 * installed, and it takes effect on the next game launch.
 */
public final class HorizonRuntimeUpdateService {
    private static final String TAG = "HorizonRuntimeUpdate";
    static final String ACTION_PROGRESS = "dev.mobilehorizon.app.RUNTIME_UPDATE_PROGRESS";
    private static final String PROGRESS_ACTIVE = "progress_active";
    private static final String PROGRESS_PHASE = "progress_phase";
    private static final String PROGRESS_MESSAGE = "progress_message";
    private static final String PROGRESS_BYTES = "progress_bytes";
    private static final String PROGRESS_TOTAL = "progress_total";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/Ph04/MobileHorizonNative/releases?per_page=100";
    private static final String OFFICIAL_NIGHTLY_RELEASE_URL =
            "https://api.github.com/repos/horizon-rust/horizon/releases/tags/nightly";
    private static final String OFFICIAL_NIGHTLY_TAG = "nightly";
    private static final String TAG_PREFIX = "horizon-runtime-";
    private static final String ENGINE_ASSET = "horizon-runtime-arm64-v8a.so";
    private static final String TEST_FIXTURE_TAG = "horizon-runtime-update-test";
    private static final String TEST_FIXTURE_URL = "https://api.github.com/repos/Ph04/MobileHorizonNative/releases/tags/" + TEST_FIXTURE_TAG;
    private static final String PC_RELEASE_TAG = "pc_release_tag";
    private static final String OFFICIAL_LATEST_TAG = "official_latest_tag";
    private static final String OFFICIAL_CHECKED_AT = "official_checked_at_ms";
    private static final String TEST_SEED_DONE = "test_old_runtime_seed_done";
    private static final String PREFERENCES = "horizon_runtime_updates";
    private static final String LAST_CHECK = "last_check_ms";
    private static final String LAST_AUTOMATIC_VERSION = "last_automatic_runtime_version";
    private static final String LAST_STATUS = "last_status";
    private static final long CHECK_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long MAX_DOWNLOAD_BYTES = 300L * 1024L * 1024L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    // Forces one latest-runtime check when this app process first initializes. Activity resumes
    // still use the normal cooldown, so rotating/recreating the UI cannot cause repeated requests.
    private static final AtomicBoolean STARTUP_CHECK_REQUESTED = new AtomicBoolean(false);
    private static final Object CHECK_LOCK = new Object();
    private static final List<Runnable> PENDING_CALLBACKS = new ArrayList<>();

    private HorizonRuntimeUpdateService() {}

    public static void checkInBackground(Context context) {
        checkInBackground(context, null, false);
    }

    /** Checks immediately when the player requests it, bypassing only the normal cooldown. */
    public static void checkNow(Context context, Runnable onFinished) {
        checkInBackground(context, onFinished, true);
    }

    /** Forces a freshness check once per app process, bypassing the prior-process cooldown. */
    public static void checkAtStartup(Context context, Runnable onFinished) {
        boolean force = STARTUP_CHECK_REQUESTED.compareAndSet(false, true);
        checkInBackground(context, onFinished, force);
    }

    public static void checkInBackground(Context context, Runnable onFinished) {
        checkInBackground(context, onFinished, false);
    }

    private static void checkInBackground(Context context, Runnable onFinished, boolean force) {
        Context app = context.getApplicationContext();
        SharedPreferences preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long previous = preferences.getLong(LAST_CHECK, 0L);
        boolean startCheck = false;
        boolean finishImmediately = false;

        synchronized (CHECK_LOCK) {
            // Join an existing check instead of reporting completion before download/install ends.
            if (IN_FLIGHT.get()) {
                if (onFinished != null) PENDING_CALLBACKS.add(onFinished);
                return;
            }
            // Initial installation is mandatory even if the user disabled routine auto-updates.
            // After one verified runtime exists, the setting controls periodic update checks.
            boolean firstInstallRequired = !HorizonRuntimeManager.hasActiveRuntimeMarker(app);
            boolean updatesEnabled = preferences.getBoolean("enabled", true);
            if (!updatesEnabled && !firstInstallRequired) {
                finishImmediately = true;
            } else if (!force && !firstInstallRequired && now - previous < CHECK_INTERVAL_MS) {
                finishImmediately = true;
            } else {
                IN_FLIGHT.set(true);
                if (onFinished != null) PENDING_CALLBACKS.add(onFinished);
                startCheck = true;
            }
        }

        if (finishImmediately) {
            postFinished(onFinished);
            return;
        }
        if (!startCheck) return;
        preferences.edit().putLong(LAST_CHECK, now).apply();
        publishProgress(app, preferences, "checking",
                HorizonRuntimeManager.hasActiveRuntimeMarker(app)
                        ? "Verificando releases e atualizações da runtime Horizon…"
                        : "Procurando e baixando a runtime Horizon para Android ARM64…",
                0L, -1L);

        EXECUTOR.execute(() -> {
            try {
                checkAndInstall(app, preferences);
            } catch (Exception error) {
                String message = "Falha ao verificar atualizações do Horizon: " + error.getMessage();
                preferences.edit().putString(LAST_STATUS, message).apply();
                Log.w(TAG, message, error);
            } finally {
                finishProgress(app, preferences);
                List<Runnable> callbacks;
                synchronized (CHECK_LOCK) {
                    IN_FLIGHT.set(false);
                    callbacks = new ArrayList<>(PENDING_CALLBACKS);
                    PENDING_CALLBACKS.clear();
                    CHECK_LOCK.notifyAll();
                }
                for (Runnable callback : callbacks) postFinished(callback);
            }
        });
    }

    private static void postFinished(Runnable callback) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
    }

    /** Whether periodic automatic runtime checks are enabled. */
    public static boolean isEnabled(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean("enabled", true);
    }

    /** Enables/disables automatic runtime checks. A disable setting never removes installed versions. */
    public static void setEnabled(Context context, boolean enabled) {
        context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).apply();
    }

    public static String lastStatus(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (preferences.getBoolean(PROGRESS_ACTIVE, false)) {
            String progress = preferences.getString(PROGRESS_MESSAGE, "");
            if (progress != null && !progress.trim().isEmpty()) return progress;
        }
        return preferences.getString(LAST_STATUS, "Ainda não foi verificada uma atualização do Horizon.");
    }

    public static boolean isProgressActive(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(PROGRESS_ACTIVE, false);
    }

    public static String progressPhase(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(PROGRESS_PHASE, "");
    }

    public static long progressBytes(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getLong(PROGRESS_BYTES, 0L);
    }

    public static long progressTotal(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getLong(PROGRESS_TOTAL, -1L);
    }

    private static void publishProgress(Context context, SharedPreferences preferences,
                                        String phase, String message, long bytes, long total) {
        preferences.edit().putBoolean(PROGRESS_ACTIVE, true)
                .putString(PROGRESS_PHASE, phase)
                .putString(PROGRESS_MESSAGE, message)
                .putLong(PROGRESS_BYTES, bytes)
                .putLong(PROGRESS_TOTAL, total)
                .apply();
        Intent event = new Intent(ACTION_PROGRESS).setPackage(context.getPackageName());
        event.putExtra("active", true);
        event.putExtra("phase", phase);
        event.putExtra("message", message);
        event.putExtra("bytes", bytes);
        event.putExtra("total", total);
        context.sendBroadcast(event);
    }

    private static void finishProgress(Context context, SharedPreferences preferences) {
        String message = preferences.getString(LAST_STATUS, "Verificação concluída.");
        preferences.edit().putBoolean(PROGRESS_ACTIVE, false)
                .putString(PROGRESS_PHASE, "finished")
                .putString(PROGRESS_MESSAGE, message)
                .putLong(PROGRESS_BYTES, 0L)
                .putLong(PROGRESS_TOTAL, -1L)
                .apply();
        Intent event = new Intent(ACTION_PROGRESS).setPackage(context.getPackageName());
        event.putExtra("active", false);
        event.putExtra("phase", "finished");
        event.putExtra("message", message);
        event.putExtra("bytes", 0L);
        event.putExtra("total", -1L);
        context.sendBroadcast(event);
    }

    /** The PC release tag represented by the installed runtime, for the launcher UI. */
    public static String pcReleaseLabel(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String defaultTag = BuildConfig.HORIZON_TEST_OLD_RUNTIME
                ? BuildConfig.HORIZON_PC_RELEASE_TAG : "";
        String tag = preferences.getString(PC_RELEASE_TAG, defaultTag);
        if (tag == null || tag.trim().isEmpty()) {
            return BuildConfig.HORIZON_TEST_OLD_RUNTIME
                    ? BuildConfig.HORIZON_PC_RELEASE_TAG : "runtime Android ainda não instalada";
        }
        return OFFICIAL_NIGHTLY_TAG.equals(tag) ? "Nightly" : tag;
    }

    /** Last official Horizon release successfully checked against upstream GitHub. */
    public static String latestOfficialReleaseLabel(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String tag = preferences.getString(OFFICIAL_LATEST_TAG, "");
        if (tag == null || tag.trim().isEmpty()) {
            return "Último release oficial verificado: ainda não consultado";
        }
        return "Último release oficial verificado: "
                + (OFFICIAL_NIGHTLY_TAG.equals(tag) ? "Nightly" : tag);
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

        // The official Horizon release and the Android-native runtime are different deliverables.
        // Read upstream directly so a new PC release is visible even before its Android runtime
        // has been built and published in this repository.
        OfficialRelease official = null;
        if (!BuildConfig.HORIZON_TEST_OLD_RUNTIME) {
            publishProgress(context, preferences, "checking-official",
                    "Consultando o release oficial mais recente do Horizon…", 0L, -1L);
            try {
                official = latestOfficialRelease();
                Log.i(TAG, "Release oficial do Horizon detectada: " + official.tag
                        + " (publicada em " + official.publishedAt + ").");
                preferences.edit()
                        .putString(OFFICIAL_LATEST_TAG, official.tag)
                        .putLong(OFFICIAL_CHECKED_AT, System.currentTimeMillis())
                        .apply();
            } catch (Exception error) {
                Log.w(TAG, "Não foi possível consultar o release oficial do Horizon em "
                        + OFFICIAL_NIGHTLY_RELEASE_URL + "; a próxima tentativa automática será em até cinco minutos.", error);
            }
        }

        publishProgress(context, preferences, "checking-runtime",
                "Procurando a runtime Android ARM64 mais recente publicada…", 0L, -1L);
        String currentVersion = HorizonRuntimeManager.currentVersion(context);
        Release latest;
        Release latestPublished = null;
        if (BuildConfig.HORIZON_TEST_OLD_RUNTIME) {
            latest = testFixtureRuntime(BuildConfig.HORIZON_PC_RELEASE_TAG);
        } else if (official != null) {
            // The Nightly runtime must follow the exact current Horizon channel, not merely the newest
            // Android artifact. Otherwise the launcher can install an older engine while Horizon is on
            // v0.2.66 and then report success without ever catching up to the official release.
            latest = latestRuntimeRelease(official.tag);
            if (latest == null) {
                latestPublished = latestRuntimeRelease();
                if (!HorizonRuntimeManager.hasActiveRuntimeMarker(context)
                        && latestPublished != null) {
                    // A first launch still needs a runnable engine while the newest runtime builds.
                    latest = latestPublished;
                } else {
                    String available = latestPublished == null ? ""
                            : " A runtime Android publicada mais recente é para "
                                + latestPublished.upstreamTag + ".";
                    setStatus(preferences, "Release oficial " + official.tag
                            + " detectada, mas a runtime Android ARM64 correspondente ainda não "
                            + "foi publicada." + available
                            + " A versão instalada será mantida e o launcher continuará verificando "
                            + "automaticamente até encontrar a runtime exata.");
                    return;
                }
            }
        } else {
            // If GitHub's official release endpoint is temporarily unavailable, still permit a
            // verified runtime check. As soon as the official endpoint returns, exact-tag matching
            // resumes and prevents an older runtime from being mistaken for the current release.
            latest = latestRuntimeRelease();
        }
        if (latest == null) {
            if (official != null) {
                setStatus(preferences, "Release oficial " + official.tag
                        + " detectada, mas nenhum pacote Android ARM64 verificado está disponível. "
                        + "O launcher continuará verificando automaticamente.");
            } else {
                setStatus(preferences, "Nenhum release Android do Horizon foi encontrado; "
                        + "a consulta à versão oficial também não foi concluída.");
            }
            return;
        }

        preferences.edit().putString(LAST_AUTOMATIC_VERSION, latest.version).apply();
        boolean installNeeded = !latest.version.equals(currentVersion);
        if (installNeeded) {
            Log.i(TAG, "Atualização de motor encontrada: PC " + latest.upstreamTag
                    + ", motor " + latest.version + "; atual " + currentVersion);
            installRelease(context, preferences, latest);
        } else {
            preferences.edit().putString(PC_RELEASE_TAG, latest.upstreamTag).apply();
        }

        if (BuildConfig.HORIZON_TEST_OLD_RUNTIME) {
            setStatus(preferences, "Horizon está atualizado — release PC " + latest.upstreamTag
                    + " (" + latest.version + ").");
            return;
        }

        if (official == null) {
            String runtimeMessage = installNeeded
                    ? "O runtime Android para Horizon PC " + latest.upstreamTag
                        + " foi baixado e verificado; será ativado na próxima abertura do jogo."
                    : "O runtime Android instalado corresponde à release PC " + latest.upstreamTag + ".";
            setStatus(preferences, runtimeMessage
                    + " Não foi possível consultar agora a release oficial mais recente do Horizon.");
            return;
        }

        if (!official.tag.equals(latest.upstreamTag)) {
            String status = "Release oficial " + official.tag
                    + " detectada, mas o pacote Android ARM64 mais recente publicado é para "
                    + latest.upstreamTag + ". ";
            if (installNeeded) {
                status += "Esse runtime foi baixado e verificado e será ativado na próxima "
                        + "abertura do jogo. O pacote Android da release oficial mais nova ainda "
                        + "não está disponível; o aplicativo continuará verificando automaticamente.";
            } else {
                status += "O runtime instalado permanece na versão " + latest.upstreamTag
                        + " até que um pacote Android correspondente à release " + official.tag
                        + " seja publicado. O aplicativo continuará verificando automaticamente.";
            }
            setStatus(preferences, status);
            return;
        }

        if (installNeeded) {
            setStatus(preferences, "Runtime Android verificado para o release oficial "
                    + official.tag + "; será ativado na próxima inicialização do jogo.");
        } else {
            setStatus(preferences, "Horizon está atualizado — release oficial "
                    + official.tag + " e runtime Android " + latest.version + " instalado.");
        }
    }

    private static OfficialRelease latestOfficialRelease() throws Exception {
        // Since 2026-10-28 Horizon uses the moving "nightly" pre-release as its only
        // player-facing game channel. The previous vX.Y.Z release remains GitHub's
        // stable/latest release, so scanning stable releases silently misses every update.
        JSONObject release = new JSONObject(readUrl(
                OFFICIAL_NIGHTLY_RELEASE_URL, 1024 * 1024));
        String tag = release.optString("tag_name", "");
        String publishedAt = release.optString("published_at", "");
        if (release.optBoolean("draft", false)
                || !release.optBoolean("prerelease", false)
                || !OFFICIAL_NIGHTLY_TAG.equals(tag)
                || publishedAt.isEmpty()) {
            throw new IOException("A release Nightly oficial do Horizon não está publicada ou é inválida.");
        }
        return new OfficialRelease(tag, publishedAt);
    }

    private static void installRelease(Context context, SharedPreferences preferences,
                                       Release release) throws Exception {
        installRelease(context, preferences, release, true);
    }

    private static void installRelease(Context context, SharedPreferences preferences,
                                       Release release, boolean updateDisplayedReleaseTag)
            throws Exception {
        File manifestDownload = new File(context.getCacheDir(),
                "horizon-runtime-" + release.version + ".manifest.part");
        File download = new File(context.getCacheDir(),
                "horizon-runtime-" + release.version + ".part");
        try {
            publishProgress(context, preferences, "manifest",
                    "Baixando manifesto e metadados da runtime…", 0L, release.manifestSize);
            download(context, preferences, "manifest", "Baixando manifesto da runtime…",
                    release.manifestUrl, release.manifestSize, release.manifestSha256, manifestDownload);
            publishProgress(context, preferences, "verify-manifest",
                    "Validando o manifesto e a versão da runtime…",
                    manifestDownload.length(), Math.max(1L, manifestDownload.length()));
            JSONObject manifest = new JSONObject(readSmallFile(manifestDownload, 64 * 1024));
            String upstreamCommit = manifest.optString("upstream_commit", "");
            String upstreamTag = manifest.optString("upstream_tag", "");
            String overlay = manifest.optString("overlay", "");
            String expectedVersion = upstreamCommit.matches("(?i)[0-9a-f]{40}")
                    && overlay.matches("(?i)[0-9a-f]{8}")
                    && isValidUpstreamTag(upstreamTag)
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
            publishProgress(context, preferences, "runtime-download",
                    "Baixando a runtime Horizon Android ARM64…", 0L, release.size);
            download(context, preferences, "runtime-download", "Baixando runtime Horizon…",
                    release.url, release.size, release.sha256, download);
            publishProgress(context, preferences, "runtime-install",
                    "Download concluído. Instalando a runtime verificada…",
                    release.size, release.size);
            HorizonRuntimeManager.installAndActivate(context, download, release.version,
                    release.sha256, release.upstreamTag);
            if (updateDisplayedReleaseTag) {
                preferences.edit().putString(PC_RELEASE_TAG, release.upstreamTag).apply();
            }
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
        return latestRuntimeRelease("");
    }

    /** Finds the most recent verified Android runtime for the required official Horizon tag. */
    private static Release latestRuntimeRelease(String requiredUpstreamTag) throws Exception {
        Release latest = null;
        String requiredTag = requiredUpstreamTag == null ? "" : requiredUpstreamTag.trim();
        if (!requiredTag.isEmpty() && !isValidUpstreamTag(requiredTag)) {
            throw new IOException("A tag oficial solicitada para a runtime não é válida.");
        }
        // GitHub orders this endpoint newest-first. Usually page 1 is enough, but look farther
        // back if the repository has many app releases and no runtime release is on page 1.
        for (int page = 1; page <= 10; page++) {
            JSONArray releases = new JSONArray(readUrl(RELEASES_URL + "&page=" + page,
                    4 * 1024 * 1024));
            if (releases.length() == 0) break;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || release.optBoolean("draft", false)
                        || release.optBoolean("prerelease", false)) continue;
                String tag = release.optString("tag_name", "");
                if (!tag.startsWith(TAG_PREFIX)) continue;
                String version = tag.substring(TAG_PREFIX.length());
                String upstreamTag = upstreamTagFromRuntimeVersion(version);
                if (upstreamTag.isEmpty()) {
                    Log.w(TAG, "Ignorando release com versão de runtime inválida: " + tag);
                    continue;
                }
                if (!requiredTag.isEmpty()
                        && !runtimeVersionMatchesRelease(version, requiredTag)) {
                    continue;
                }
                try {
                    Release candidate = releaseFromAssets(release, version, upstreamTag,
                            ENGINE_ASSET, "horizon-runtime-manifest.json");
                    if (latest == null
                            || comparePublicationDates(candidate.publishedAt, latest.publishedAt) > 0) {
                        latest = candidate;
                    }
                } catch (Exception invalidRelease) {
                    // A draft-like/incomplete/old release must not hide later valid runtime releases.
                    Log.w(TAG, "Ignorando release Android incompleto: " + tag, invalidRelease);
                }
            }
            // Results are newest-first. If we found a valid release on this page, older pages
            // cannot provide a more recent runtime build under the normal GitHub release ordering.
            if (latest != null || releases.length() < 100) break;
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
        String publishedAt = release.optString("published_at", "");
        if (publishedAt.isEmpty()) publishedAt = release.optString("created_at", "");
        return new Release(version, upstreamTag, engineName, digest, url, size,
                publishedAt, manifestUrl, manifestDigest, manifestSize);
    }

    static boolean isValidUpstreamTag(String tag) {
        // "nightly" is the moving official channel; older installed runtimes keep their
        // immutable semantic/date-based v-tags for rollback and version identification.
        return OFFICIAL_NIGHTLY_TAG.equals(tag)
                || (tag != null && tag.matches("v[0-9][A-Za-z0-9._+-]{0,50}"));
    }

    static String upstreamTagFromRuntimeVersion(String version) {
        if (version == null || !version.startsWith("horizon-")) return "";
        String body = version.substring("horizon-".length());
        Matcher matcher = Pattern.compile("^(.+)-c[0-9a-fA-F]{8}-o[0-9a-fA-F]{8}$")
                .matcher(body);
        if (!matcher.matches()) return "";
        String tag = matcher.group(1);
        return isValidUpstreamTag(tag) ? tag : "";
    }

    static boolean runtimeVersionMatchesRelease(String runtimeVersion, String officialTag) {
        return isValidUpstreamTag(officialTag)
                && officialTag.equals(upstreamTagFromRuntimeVersion(runtimeVersion));
    }

    static int comparePublicationDates(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        // GitHub published_at values are RFC 3339 strings, whose normalized UTC form sorts
        // chronologically. Do not compare version text: upstream can change tag schemes.
        return a.compareTo(b);
    }

    private static void download(Context context, SharedPreferences preferences,
                                 String phase, String progressLabel, String address,
                                 long expectedSize, String expectedSha256, File destination) throws IOException {
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
                long lastReportedBytes = 0L;
                long lastReportedAt = android.os.SystemClock.elapsedRealtime();
                int count;
                publishProgress(context, preferences, phase, progressLabel, 0L, expectedSize);
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > MAX_DOWNLOAD_BYTES || written > expectedSize) {
                        throw new IOException("O download excedeu o tamanho esperado.");
                    }
                    output.write(buffer, 0, count);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastReportedAt >= 250L || written - lastReportedBytes >= 1024L * 1024L) {
                        publishProgress(context, preferences, phase, progressLabel, written, expectedSize);
                        lastReportedAt = now;
                        lastReportedBytes = written;
                    }
                }
                output.getFD().sync();
                if (written != expectedSize) {
                    throw new IOException("Download incompleto: recebido " + written
                            + " bytes, esperado " + expectedSize + ".");
                }
                publishProgress(context, preferences, "verify-" + phase,
                        "Download concluído. Verificando SHA-256…", written, expectedSize);
            }
            String downloadedDigest = sha256(destination);
            if (!downloadedDigest.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("SHA-256 do asset baixado não confere.");
            }
            publishProgress(context, preferences, phase,
                    "Arquivo baixado e SHA-256 verificado.", expectedSize, expectedSize);
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

    private static String requireDigest(String value, String label) throws IOException {
        if (value == null || !value.matches("(?i)sha256:[0-9a-f]{64}")) {
            throw new IOException("O GitHub não forneceu SHA-256 válido para o " + label + ".");
        }
        return value.substring("sha256:".length()).toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireReleaseAssetUrl(String url) throws IOException {
        if (url == null || !url.startsWith(
                "https://github.com/Ph04/MobileHorizonNative/releases/download/")) {
            throw new IOException("URL de asset fora dos releases esperados do MobileHorizonNative.");
        }
        return url;
    }

    private static String readSmallFile(File file, int maximumBytes) throws IOException {
        if (file == null || !file.isFile() || file.length() > maximumBytes) {
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

    private static final class OfficialRelease {
    public static final class PublishedRuntime {
        public final String version;
        public final String upstreamTag;
        public final String publishedAt;
        public final long sizeBytes;
        private PublishedRuntime(Release release) {
            this.version = release.version;
            this.upstreamTag = release.upstreamTag;
            this.publishedAt = release.publishedAt;
            this.sizeBytes = release.size;
        }
    }

    /** Returns validated Android ARM64 releases, newest first. This performs network I/O. */
    public static List<PublishedRuntime> listPublishedRuntimes() throws Exception {
        LinkedHashMap<String, Release> found = new LinkedHashMap<>();
        for (int page = 1; page <= 10 && found.size() < 60; page++) {
            JSONArray releases = new JSONArray(readUrl(RELEASES_URL + "&page=" + page,
                    4 * 1024 * 1024));
            if (releases.length() == 0) break;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject item = releases.optJSONObject(i);
                if (item == null || item.optBoolean("draft", false)
                        || item.optBoolean("prerelease", false)) continue;
                String tag = item.optString("tag_name", "");
                if (!tag.startsWith(TAG_PREFIX)) continue;
                String version = tag.substring(TAG_PREFIX.length());
                String upstream = upstreamTagFromRuntimeVersion(version);
                if (upstream.isEmpty() || found.containsKey(version)) continue;
                try {
                    Release candidate = releaseFromAssets(item, version, upstream,
                            ENGINE_ASSET, "horizon-runtime-manifest.json");
                    found.put(version, candidate);
                } catch (Exception invalid) {
                    Log.w(TAG, "Ignorando runtime inválida no catálogo: " + tag, invalid);
                }
            }
            if (releases.length() < 100) break;
        }
        ArrayList<Release> ordered = new ArrayList<>(found.values());
        ordered.sort((a, b) -> comparePublicationDates(b.publishedAt, a.publishedAt));
        ArrayList<PublishedRuntime> result = new ArrayList<>();
        for (Release release : ordered) result.add(new PublishedRuntime(release));
        return Collections.unmodifiableList(result);
    }

    /** Downloads one exact published version using the same signed-by-checksum manifest path. */
    public static void installSpecificRuntime(Context context, String requestedVersion,
                                              boolean setAsAutomaticDefault) throws Exception {
        if (requestedVersion == null || !requestedVersion.matches(
                "horizon-[A-Za-z0-9._+-]+-c[0-9a-fA-F]{8}-o[0-9a-fA-F]{8}")) {
            throw new IOException("Identificador de runtime inválido.");
        }
        synchronized (CHECK_LOCK) {
            long deadline = android.os.SystemClock.elapsedRealtime() + 180000L;
            while (IN_FLIGHT.get()
                    && android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    CHECK_LOCK.wait(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("A instalação manual da runtime foi interrompida.", interrupted);
                }
            }
            if (IN_FLIGHT.get()) {
                throw new IOException("A verificação automática continua ocupada. Tente novamente após a conclusão.");
            }
            IN_FLIGHT.set(true);
        }
        Context app = context.getApplicationContext();
        SharedPreferences preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        try {
            Release release = findRuntimeRelease(requestedVersion);
            publishProgress(app, preferences, "manual-runtime",
                    "Instalando a runtime selecionada " + release.version + "…", 0L, release.size);
            installRelease(app, preferences, release, setAsAutomaticDefault);
            if (setAsAutomaticDefault) {
                preferences.edit().putString(LAST_AUTOMATIC_VERSION, release.version).apply();
                setStatus(preferences, "Runtime padrão selecionada: " + release.version
                        + " (Horizon PC " + release.upstreamTag + ").");
            } else {
                setStatus(preferences, "Runtime " + release.version
                        + " instalada e verificada para uso em uma instância fixada.");
            }
        } catch (Exception error) {
            setStatus(preferences, "Falha ao instalar a runtime selecionada: " + error.getMessage());
            throw error;
        } finally {
            finishProgress(app, preferences);
            List<Runnable> callbacks;
            synchronized (CHECK_LOCK) {
                IN_FLIGHT.set(false);
                callbacks = new ArrayList<>(PENDING_CALLBACKS);
                PENDING_CALLBACKS.clear();
                CHECK_LOCK.notifyAll();
            }
            for (Runnable callback : callbacks) postFinished(callback);
        }
    }

    /** Persists the automatic/default runtime separately from temporary per-instance pins. */
    public static void setAutomaticRuntimeVersion(Context context, String version) throws IOException {
        if (!HorizonRuntimeManager.hasInstalledVersion(context, version)) {
            throw new IOException("A runtime escolhida não está instalada ou não passou na verificação.");
        }
        context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(LAST_AUTOMATIC_VERSION, version).apply();
    }

    public static String automaticRuntimeVersion(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(LAST_AUTOMATIC_VERSION, "");
    }

    /** Restores the default pointer after leaving an instance that used a pinned runtime. */
    public static void restoreAutomaticRuntimeVersion(Context context) throws IOException {
        String version = automaticRuntimeVersion(context);
        if (version == null || version.isEmpty()) return;
        if (HorizonRuntimeManager.hasInstalledVersion(context, version)) {
            HorizonRuntimeManager.activateInstalledVersion(context, version);
        }
    }

    private static Release findRuntimeRelease(String wantedVersion) throws Exception {
        for (int page = 1; page <= 10; page++) {
            JSONArray releases = new JSONArray(readUrl(RELEASES_URL + "&page=" + page,
                    4 * 1024 * 1024));
            if (releases.length() == 0) break;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject item = releases.optJSONObject(i);
                if (item == null || item.optBoolean("draft", false)
                        || item.optBoolean("prerelease", false)) continue;
                String tag = item.optString("tag_name", "");
                if (!tag.equals(TAG_PREFIX + wantedVersion)) continue;
                String upstream = upstreamTagFromRuntimeVersion(wantedVersion);
                if (upstream.isEmpty()) throw new IOException("Release aponta para tag Horizon inválida.");
                return releaseFromAssets(item, wantedVersion, upstream,
                        ENGINE_ASSET, "horizon-runtime-manifest.json");
            }
            if (releases.length() < 100) break;
        }
        throw new IOException("A versão " + wantedVersion
                + " não está mais disponível entre os releases verificados do MobileHorizonNative.");
    }

    private static final class OfficialRelease {
        final String tag;
        final String publishedAt;

        OfficialRelease(String tag, String publishedAt) {
            this.tag = tag;
            this.publishedAt = publishedAt;
        }
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
