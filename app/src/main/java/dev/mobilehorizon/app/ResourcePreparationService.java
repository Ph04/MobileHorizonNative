package dev.mobilehorizon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service for preparing vanilla inputs. The operation can survive MainActivity
 * recreation and reports a durable result when the UI returns. This service does not pretend
 * that vanilla extraction is the Horizon-derived resource export.
 */
public final class ResourcePreparationService extends Service {
    static final String ACTION_START = "dev.mobilehorizon.app.PREPARE_START";
    static final String ACTION_START_FULL = "dev.mobilehorizon.app.PREPARE_START_FULL";
    static final String ACTION_CANCEL = "dev.mobilehorizon.app.PREPARE_CANCEL";
    static final String ACTION_QUERY = "dev.mobilehorizon.app.PREPARE_QUERY";
    static final String ACTION_ACK = "dev.mobilehorizon.app.PREPARE_ACK";
    static final String ACTION_PROGRESS = "dev.mobilehorizon.app.PREPARE_PROGRESS";
    static final String ACTION_FINISHED = "dev.mobilehorizon.app.PREPARE_FINISHED";

    private static final String CHANNEL_ID = "minecraft-preparation-v1";
    private static final int NOTIFICATION_ID = 26032;
    private static final String PREFS = "resource_preparation_state";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile boolean fullMode;
    private volatile ProgressState lastProgress = new ProgressState(
            "Aguardando preparação", "Minecraft 26.3", 0, -1);
    private volatile long lastNotificationAt;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            if (running) {
                cancelled.set(true);
                updateProgress(new ProgressState(
                        "Cancelamento solicitado", "Aguardando um ponto seguro", 0, -1));
            }
            return START_NOT_STICKY;
        }
        if (ACTION_ACK.equals(action)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hasResult", false).commit();
            if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            if (running) {
                broadcastProgress(lastProgress);
                return START_STICKY;
            }
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("hasResult", false)) {
                broadcastStoredResult();
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) && !ACTION_START_FULL.equals(action)) return running ? START_STICKY : START_NOT_STICKY;
        if (running) {
            broadcastProgress(lastProgress);
            return START_STICKY;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hasResult", false).apply();
        cancelled.set(false);
        fullMode = ACTION_START_FULL.equals(action);
        running = true;
        ProgressState initial = new ProgressState(
                fullMode ? "Iniciando conversão completa" : "Iniciando preparação",
                fullMode ? "Preparador Horizon + Java 25 ARM64" : "Verificando os arquivos oficiais de Minecraft 26.3", 0, -1);
        lastProgress = initial;
        startForeground(NOTIFICATION_ID, buildNotification(initial, true, false, false));
        executor.submit(this::runPreparation);
        return START_NOT_STICKY;
    }

    private void runPreparation() {
        boolean success = false;
        String message = "";
        HorizonResourceConverter.InputReport report = null;
        boolean runningFullMode = fullMode;
        try {
            if (runningFullMode) {
                message = HorizonPrepareRunner.prepare(getApplicationContext(), cancelled,
                        (stage, detail, completed, total) ->
                                updateProgress(new ProgressState(stage, detail, completed, total)));
            } else {
                report = HorizonResourceConverter.prepareConversionInputs(
                        getFilesDir(), cancelled,
                        (stage, completed, total, detail) ->
                                updateProgress(new ProgressState(stage, detail, completed, total)));
                message = report.note == null ? "Entradas vanilla preparadas; exportador Horizon pendente." : report.note;
            }
            success = true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            message = "Preparação cancelada. A extração parcial foi descartada.";
        } catch (Exception error) {
            message = message(error);
        } catch (Throwable error) {
            message = error.getClass().getSimpleName() + ": " + message(error);
        } finally {
            running = false;
            android.content.SharedPreferences.Editor state =
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean("hasResult", true)
                            .putBoolean("success", success)
                            .putBoolean("cancelled", cancelled.get())
                            .putBoolean("fullMode", runningFullMode)
                            .putString("message", message)
                            .putLong("finishedAt", System.currentTimeMillis());
            if (report != null) {
                state.putInt("libraries", report.verifiedLibraries)
                        .putInt("uniqueAssets", report.uniqueAssets)
                        .putInt("presentAssets", report.presentAssetObjects)
                        .putInt("missingAssets", report.missingAssetObjects)
                        .putInt("clientFiles", report.extractedClientFiles)
                        .putInt("serverFiles", report.extractedServerFiles)
                        .putLong("sourceBytes", report.extractedSourceBytes)
                        .putInt("hydratedNames", report.hydratedAssetNames)
                        .putLong("hydratedBytes", report.hydratedAssetBytes)
                        .putString("planPath", report.planFile == null ? "" : report.planFile.getAbsolutePath())
                        .putString("reportNote", report.note == null ? "" : report.note);
            }
            state.commit();

            ProgressState finished = new ProgressState(
                    success ? "Preparação concluída" : (cancelled.get() ? "Preparação cancelada" : "Preparação falhou"),
                    message, success ? 1 : 0, 1);
            lastProgress = finished;
            broadcastFinished();
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID,
                        buildNotification(finished, false, success, cancelled.get()));
            }
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private void updateProgress(ProgressState progress) {
        if (progress == null) return;
        lastProgress = progress;
        broadcastProgress(progress);
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationAt >= 700
                || progress.total > 0 && progress.completed >= progress.total) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID,
                    buildNotification(progress, true, false, false));
            lastNotificationAt = now;
        }
    }

    private Notification buildNotification(ProgressState progress, boolean ongoing,
                                           boolean success, boolean wasCancelled) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 12, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String title = ongoing ? "Preparando recursos para Minecraft 26.3"
                : success ? "Preparação concluída"
                : wasCancelled ? "Preparação cancelada" : "Preparação não concluída";
        builder.setSmallIcon(ongoing ? android.R.drawable.stat_sys_download
                        : success ? android.R.drawable.stat_sys_download_done : android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(progress == null ? "Preparação de recursos vanilla" : compact(progress.stage, progress.detail))
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(ongoing)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
        if (ongoing) {
            Intent cancel = new Intent(this, ResourcePreparationService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelIntent = PendingIntent.getService(this, 13, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent).build());
        }
        if (ongoing && progress != null && progress.total > 0) {
            builder.setProgress(progress.total, Math.max(0, Math.min(progress.completed, progress.total)), false);
        } else if (ongoing) {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private void broadcastProgress(ProgressState progress) {
        if (progress == null) return;
        Intent event = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        event.putExtra("stage", progress.stage);
        event.putExtra("detail", progress.detail);
        event.putExtra("completed", progress.completed);
        event.putExtra("total", progress.total);
        event.putExtra("fullMode", fullMode);
        sendBroadcast(event);
    }

    private void broadcastFinished() {
        android.content.SharedPreferences state = getSharedPreferences(PREFS, MODE_PRIVATE);
        Intent event = new Intent(ACTION_FINISHED).setPackage(getPackageName());
        event.putExtra("success", state.getBoolean("success", false));
        event.putExtra("cancelled", state.getBoolean("cancelled", false));
        event.putExtra("fullMode", state.getBoolean("fullMode", false));
        event.putExtra("message", state.getString("message", "Preparação finalizada."));
        event.putExtra("libraries", state.getInt("libraries", 0));
        event.putExtra("uniqueAssets", state.getInt("uniqueAssets", 0));
        event.putExtra("presentAssets", state.getInt("presentAssets", 0));
        event.putExtra("missingAssets", state.getInt("missingAssets", 0));
        event.putExtra("clientFiles", state.getInt("clientFiles", 0));
        event.putExtra("serverFiles", state.getInt("serverFiles", 0));
        event.putExtra("sourceBytes", state.getLong("sourceBytes", 0));
        event.putExtra("hydratedNames", state.getInt("hydratedNames", 0));
        event.putExtra("hydratedBytes", state.getLong("hydratedBytes", 0));
        event.putExtra("planPath", state.getString("planPath", ""));
        event.putExtra("reportNote", state.getString("reportNote", ""));
        sendBroadcast(event);
    }

    private void broadcastStoredResult() {
        broadcastFinished();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Preparação de recursos", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Progresso, cancelamento e conclusão da preparação de recursos vanilla.");
        manager.createNotificationChannel(channel);
    }

    private static String compact(String stage, String detail) {
        if (stage == null || stage.isEmpty()) return detail == null ? "Preparação de recursos" : detail;
        if (detail == null || detail.isEmpty()) return stage;
        return stage + " · " + detail;
    }

    private static String message(Throwable error) {
        if (error == null) return "Erro desconhecido.";
        String text = error.getMessage();
        return text == null || text.trim().isEmpty() ? error.getClass().getSimpleName() : text;
    }

    @Override
    public void onDestroy() {
        if (running) cancelled.set(true);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class ProgressState {
        final String stage;
        final String detail;
        final int completed;
        final int total;

        ProgressState(String stage, String detail, int completed, int total) {
            this.stage = stage;
            this.detail = detail;
            this.completed = completed;
            this.total = total;
        }
    }
}
