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
    // Machine identifiers stay separate from the human-readable Nightly name/date.
    private static final String PC_RELEASE_TAG = "pc_release_tag";
    private static final String PC_RELEASE_LABEL = "pc_release_label";
    private static final String PC_RELEASE_COMMIT_PREFIX = "pc_release_commit_prefix";
    private static final String OFFICIAL_LATEST_TAG = "official_latest_tag";
    private static final String OFFICIAL_LATEST_LABEL = "official_latest_label";
    private static final String OFFICIAL_LATEST_COMMIT = "official_latest_commit";
    private static final String OFFICIAL_CHECKED_AT = "official_checked_at_ms";
    private static final String TEST_SEED_DONE = "test_old_runtime_seed_done";
    private static final String PREFERENCES = "horizon_runtime_updates";
    private static final String LAST_CHECK = "last_check_ms";
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

    /** Human-readable upstream build represented by the currently installed runtime. */
    public static String pcReleaseLabel(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String label = preferences.getString(PC_RELEASE_LABEL, "");
        if (label != null && !label.trim().isEmpty()) return label;
        String tag = preferences.getString(PC_RELEASE_TAG,
                BuildConfig.HORIZON_TEST_OLD_RUNTIME ? BuildConfig.HORIZON_PC_RELEASE_TAG : "");
        if (tag == null || tag.trim().isEmpty()) return "runtime Android ainda não instalada";
        return OFFICIAL_NIGHTLY_TAG.equals(tag) ? "Nightly" : tag;
    }

    /** Human-readable current upstream publication; never used as a machine version key. */
    public static String latestOfficialReleaseLabel(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String label = preferences.getString(OFFICIAL_LATEST_LABEL, "");
        if (label != null && !label.trim().isEmpty()) {
            return "Último release oficial verificado: " + label;
        }
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
            recordInstalledRuntime(preferences, previous, null);
            preferences.edit().putBoolean(TEST_SEED_DONE, true).apply();
            setStatus(preferences, "Teste do atualizador: baixada a versão anterior do Horizon PC "
                    + previous.upstreamTag + ". A próxima verificação atualizará para "
                    + BuildConfig.HORIZON_PC_RELEASE_TAG + ".");
            return;
        }

        // Horizon's public release tag is a moving channel identifier ("nightly").
        // The release name/date is presentation metadata; the resolved upstream commit
        // is the immutable identity used to select the matching Android runtime.
        OfficialRelease official = null;
        if (!BuildConfig.HORIZON_TEST_OLD_RUNTIME) {
            publishProgress(context, preferences, "checking-official",
                    "Consultando o release oficial mais recente do Horizon…", 0L, -1L);
            try {
                official = latestOfficialRelease();
                Log.i(TAG, "Release oficial do Horizon detectada: " + official.displayName
                        + " [" + official.tag + ", commit " + official.commitSha + "].");
                preferences.edit()
                        .putString(OFFICIAL_LATEST_TAG, official.tag)
                        .putString(OFFICIAL_LATEST_LABEL, official.displayName)
                        .putString(OFFICIAL_LATEST_COMMIT, official.commitSha)
                        .putLong(OFFICIAL_CHECKED_AT, System.currentTimeMillis())
                        .apply();
            } catch (Exception error) {
                Log.w(TAG, "Não foi possível consultar a publicação oficial do Horizon em "
                        + OFFICIAL_NIGHTLY_RELEASE_URL
                        + "; a próxima tentativa automática será em até cinco minutos.", error);
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
            // A moving Nightly tag alone is not enough: every dated Nightly uses the same
            // tag_name. Require the eight-character source commit embedded in the runtime ID.
            latest = latestRuntimeRelease(official.tag, official.commitSha);
            if (latest == null) {
                latestPublished = latestRuntimeRelease();
                if (!HorizonRuntimeManager.hasActiveRuntimeMarker(context)
                        && latestPublished != null) {
                    // Only a first install may use an older verified runtime while the exact
                    // Nightly build is still compiling. Never replace an existing runtime with it.
                    latest = latestPublished;
                } else {
                    String available = latestPublished == null ? ""
                            : " A última runtime Android publicada é "
                                + runtimeDisplayLabel(latestPublished.version, latestPublished.upstreamTag) + ".";
                    setStatus(preferences, "A publicação oficial " + official.displayName
                            + " (commit " + official.commitSha.substring(0, 8) + ") já foi detectada, "
                            + "mas a runtime Android ARM64 desse commit ainda não foi publicada."
                            + available + " A runtime instalada será mantida; o launcher continuará "
                            + "verificando até encontrar a compilação exata.");
                    return;
                }
            }
        } else {
            // Network fallback: use the newest verified Android runtime, but do not claim it
            // represents a particular dated Nightly until the official commit can be checked.
            latest = latestRuntimeRelease();
        }

        if (latest == null) {
            if (official != null) {
                setStatus(preferences, "A publicação oficial " + official.displayName
                        + " foi detectada, mas nenhum pacote Android ARM64 correspondente "
                        + "e verificado está disponível. O launcher continuará verificando automaticamente.");
            } else {
                setStatus(preferences, "Nenhum release Android do Horizon foi encontrado; "
                        + "a consulta à publicação oficial também não foi concluída.");
            }
            return;
        }

        boolean installNeeded = !latest.version.equals(currentVersion);
        if (installNeeded) {
            Log.i(TAG, "Runtime Android encontrada: " + runtimeDisplayLabel(latest.version, latest.upstreamTag)
                    + "; atual " + currentVersion);
            installRelease(context, preferences, latest);
        }
        recordInstalledRuntime(preferences, latest, official);

        if (BuildConfig.HORIZON_TEST_OLD_RUNTIME) {
            setStatus(preferences, "Horizon está atualizado — release PC " + latest.upstreamTag
                    + " (" + latest.version + ").");
            return;
        }

        if (official == null) {
            String runtimeMessage = installNeeded
                    ? "A runtime Android " + runtimeDisplayLabel(latest.version, latest.upstreamTag)
                        + " foi baixada e verificada; será ativada na próxima abertura do jogo."
                    : "A runtime Android instalada é " + runtimeDisplayLabel(latest.version, latest.upstreamTag) + ".";
            setStatus(preferences, runtimeMessage
                    + " Não foi possível consultar agora a publicação oficial mais recente do Horizon.");
            return;
        }

        boolean exactNightly = runtimeVersionMatchesRelease(
                latest.version, official.tag, official.commitSha);
        if (!exactNightly) {
            String currentRuntime = runtimeDisplayLabel(latest.version, latest.upstreamTag);
            String status = "A publicação oficial atual é " + official.displayName
                    + " (commit " + official.commitSha.substring(0, 8) + "), mas a runtime Android "
                    + "disponível corresponde a " + currentRuntime + ". ";
            if (installNeeded) {
                status += "Ela foi instalada como alternativa inicial e será ativada na próxima "
                        + "abertura. O launcher não a marcará como a Nightly atual.";
            } else {
                status += "A runtime instalada foi mantida. O launcher continuará procurando "
                        + "uma compilação para o commit oficial exato.";
            }
            setStatus(preferences, status);
            return;
        }

        if (installNeeded) {
            setStatus(preferences, "Runtime Android verificada para " + official.displayName
                    + "; será ativada na próxima inicialização do jogo.");
        } else {
            setStatus(preferences, "Horizon está atualizado — " + official.displayName
                    + " e runtime Android " + latest.version + " instalada.");
        }
    }

    private static OfficialRelease latestOfficialRelease() throws Exception {
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

        String commitSha = resolveOfficialNightlyCommit();
        String displayName = formatOfficialNightlyLabel(
                release.optString("name", ""), publishedAt, commitSha);
        return new OfficialRelease(tag, publishedAt, commitSha, displayName);
    }

    /** Resolves the moving 'nightly' ref to an immutable 40-character source commit. */
    private static String resolveOfficialNightlyCommit() throws Exception {
        String refUrl = "https://api.github.com/repos/horizon-rust/horizon/git/ref/tags/"
                + OFFICIAL_NIGHTLY_TAG;
        JSONObject ref = new JSONObject(readUrl(refUrl, 256 * 1024));
        if (!("refs/tags/" + OFFICIAL_NIGHTLY_TAG).equals(ref.optString("ref", ""))) {
            throw new IOException("A referência Git da Nightly oficial não corresponde à tag esperada.");
        }
        JSONObject object = ref.optJSONObject("object");
        for (int depth = 0; depth < 8 && object != null; depth++) {
            String type = object.optString("type", "");
            String sha = object.optString("sha", "");
            if ("commit".equals(type)) {
                if (!sha.matches("(?i)[0-9a-f]{40}")) {
                    throw new IOException("O commit da Nightly oficial não tem SHA completo.");
                }
                return sha.toLowerCase(java.util.Locale.ROOT);
            }
            if (!"tag".equals(type) || !sha.matches("(?i)[0-9a-f]{40}")) break;
            String tagUrl = "https://api.github.com/repos/horizon-rust/horizon/git/tags/" + sha;
            JSONObject annotated = new JSONObject(readUrl(tagUrl, 256 * 1024));
            object = annotated.optJSONObject("object");
        }
        throw new IOException("Não foi possível resolver a tag móvel Nightly para um commit imutável.");
    }

    static String formatOfficialNightlyLabel(String name, String publishedAt, String commitSha) {
        String source = name == null ? "" : name.trim();
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?i)^Nightly\\s+(\\d{4}-\\d{2}-\\d{2})\\s*[·•-]\\s*([0-9a-f]{7,40}).*$")
                .matcher(source);
        if (matcher.matches() && commitSha != null
                && commitSha.toLowerCase(java.util.Locale.ROOT)
                        .startsWith(matcher.group(2).toLowerCase(java.util.Locale.ROOT))) {
            return "Nightly " + matcher.group(1) + " · " + matcher.group(2).toLowerCase(java.util.Locale.ROOT);
        }
        String date = publishedAt == null ? "" : publishedAt.trim();
        int separator = date.indexOf('T');
        if (separator > 0) date = date.substring(0, separator);
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) date = "data desconhecida";
        String shortCommit = commitSha == null || commitSha.length() < 7
                ? "commit desconhecido" : commitSha.substring(0, 7);
        return "Nightly " + date + " · " + shortCommit;
    }

    private static void recordInstalledRuntime(SharedPreferences preferences, Release release,
                                               OfficialRelease official) {
        String commitPrefix = runtimeCommitPrefixFromVersion(release.version);
        boolean exactOfficial = official != null
                && runtimeVersionMatchesRelease(release.version, official.tag, official.commitSha);
        String label = exactOfficial ? official.displayName
                : runtimeDisplayLabel(release.version, release.upstreamTag);
        preferences.edit()
                .putString(PC_RELEASE_TAG, release.upstreamTag)
                .putString(PC_RELEASE_COMMIT_PREFIX, commitPrefix)
                .putString(PC_RELEASE_LABEL, label)
                .apply();
    }

    private static String runtimeDisplayLabel(String runtimeVersion, String upstreamTag) {
        if (OFFICIAL_NIGHTLY_TAG.equals(upstreamTag)) {
            String prefix = runtimeCommitPrefixFromVersion(runtimeVersion);
            return prefix.isEmpty() ? "Nightly" : "Nightly · " + prefix;
        }
        return upstreamTag == null || upstreamTag.trim().isEmpty() ? runtimeVersion : upstreamTag;
    }

    private static void installRelease(Context context, SharedPreferences preferences,
                                       Release release) throws Exception {
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
        return latestRuntimeRelease("", "");
    }

    /** Compatibility overload for tests and stable/date-tag releases. */
    private static Release latestRuntimeRelease(String requiredUpstreamTag) throws Exception {
        return latestRuntimeRelease(requiredUpstreamTag, "");
    }

    /**
     * Finds the newest verified Android runtime. For Nightly, both the moving channel tag
     * and the immutable upstream commit must match; a prior date's Nightly is not equivalent.
     */
    private static Release latestRuntimeRelease(String requiredUpstreamTag,
                                                String requiredUpstreamCommit) throws Exception {
        Release latest = null;
        String requiredTag = requiredUpstreamTag == null ? "" : requiredUpstreamTag.trim();
        String requiredCommit = requiredUpstreamCommit == null
                ? "" : requiredUpstreamCommit.trim().toLowerCase(java.util.Locale.ROOT);
        if (!requiredTag.isEmpty() && !isValidUpstreamTag(requiredTag)) {
            throw new IOException("A tag oficial solicitada para a runtime não é válida.");
        }
        if (!requiredCommit.isEmpty() && !requiredCommit.matches("(?i)[0-9a-f]{40}")) {
            throw new IOException("O commit oficial solicitado para a runtime não é válido.");
        }

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
                        && !runtimeVersionMatchesRelease(version, requiredTag, requiredCommit)) {
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
                    Log.w(TAG, "Ignorando release Android incompleto: " + tag, invalidRelease);
                }
            }
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

    static String runtimeCommitPrefixFromVersion(String version) {
        if (version == null || !version.startsWith("horizon-")) return "";
        Matcher matcher = Pattern.compile("^.+-c([0-9a-fA-F]{8})-o[0-9a-fA-F]{8}$")
                .matcher(version.substring("horizon-".length()));
        return matcher.matches() ? matcher.group(1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** Channel-only comparison retained for immutable releases and legacy tests. */
    static boolean runtimeVersionMatchesRelease(String runtimeVersion, String officialTag) {
        return isValidUpstreamTag(officialTag)
                && officialTag.equals(upstreamTagFromRuntimeVersion(runtimeVersion));
    }

    /** Nightly comparison: channel plus exact immutable source-commit prefix. */
    static boolean runtimeVersionMatchesRelease(String runtimeVersion, String officialTag,
                                                String officialCommit) {
        if (!isValidUpstreamTag(officialTag)
                || !officialTag.equals(upstreamTagFromRuntimeVersion(runtimeVersion))) return false;
        if (officialCommit == null || !officialCommit.matches("(?i)[0-9a-f]{40}")) return false;
        String prefix = runtimeCommitPrefixFromVersion(runtimeVersion);
        return !prefix.isEmpty() && officialCommit.toLowerCase(java.util.Locale.ROOT).startsWith(prefix);
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
        final String tag;
        final String publishedAt;
        final String commitSha;
        final String displayName;

        OfficialRelease(String tag, String publishedAt, String commitSha, String displayName) {
            this.tag = tag;
            this.publishedAt = publishedAt;
            this.commitSha = commitSha;
            this.displayName = displayName;
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
