package dev.mobilehorizon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service for Horizon runtime checks/downloads.
 *
 * Follows the same lifecycle contract as OfficialDownloadService and ResourcePreparationService:
 * quick foreground promotion, worker-thread execution, durable result state, query/ack actions,
 * throttled progress broadcasts, and a completion notification when an update needs attention.
 */
public final class HorizonRuntimeUpdateForegroundService extends Service {
    static final String ACTION_CHECK = "dev.mobilehorizon.app.HORIZON_RUNTIME_CHECK";
    static final String ACTION_QUERY = "dev.mobilehorizon.app.HORIZON_RUNTIME_QUERY";
    static final String ACTION_ACK = "dev.mobilehorizon.app.HORIZON_RUNTIME_ACK";
    static final String ACTION_PROGRESS = "dev.mobilehorizon.app.HORIZON_RUNTIME_PROGRESS";
    static final String ACTION_FINISHED = "dev.mobilehorizon.app.HORIZON_RUNTIME_FINISHED";
    static final String EXTRA_FORCE = "force_check";

    private static final String TAG = "HorizonRuntimeUpdater";
    private static final String PREFS = "horizon_runtime_updates";
    private static final String CHANNEL_ID = "horizon-runtime-updates-v1";
    private static final int NOTIFICATION_ID = 26033;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean forceForCurrentRun;
    private volatile long lastNotificationAt;
    private volatile long lastBroadcastAt;
    private volatile String lastBroadcastStage = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_ACK.equals(action)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hasResult", false).commit();
            if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_QUERY.equals(action)) {
            if (running) {
                broadcastStoredProgress();
                return START_NOT_STICKY;
            }
            SharedPreferences state = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (state.getBoolean("hasResult", false)) {
                broadcastStoredResult();
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            // If Android recreated the service after interrupting an operation, the persisted
            // in-progress lock tells us to retry the idempotent release check/download.
            if (HorizonRuntimeUpdateService.isUpdateInProgress(getApplicationContext())) {
                beginCheck(true, startId);
            } else {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }

        if (action == null) {
            // START_STICKY restart after process pressure: resume the check only if it had been active.
            if (HorizonRuntimeUpdateService.isUpdateInProgress(getApplicationContext())) {
                beginCheck(true, startId);
            } else {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }

        if (!ACTION_CHECK.equals(action)) {
            if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (running) {
            broadcastStoredProgress();
            return START_NOT_STICKY;
        }

        boolean force = intent.getBooleanExtra(EXTRA_FORCE, false);
        beginCheck(force, startId);
        return START_NOT_STICKY;
    }

    private void beginCheck(boolean force, int startId) {
        if (running) {
            broadcastStoredProgress();
            return;
        }
        running = true;
        forceForCurrentRun = force;
        String initial = force ? "Verificando atualizações do Horizon…" : "Verificando atualizações em segundo plano…";
        try {
            startForeground(NOTIFICATION_ID, buildProgressNotification(initial, -1, -1));
        } catch (RuntimeException error) {
            running = false;
            String message = "Não foi possível manter a atualização do Horizon em primeiro plano: "
                    + safeMessage(error);
            Log.e(TAG, message, error);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("update_in_progress", false)
                    .putBoolean("hasResult", true)
                    .putBoolean("success", false)
                    .putBoolean("updated", false)
                    .putBoolean("showNotification", true)
                    .putString("message", message)
                    .putString("last_status", message)
                    .putLong("progress_updated_at", System.currentTimeMillis())
                    .commit();
            stopSelf(startId);
            return;
        }
        lastNotificationAt = 0L;
        lastBroadcastAt = 0L;
        lastBroadcastStage = initial;
        executor.execute(() -> runCheck(force));
    }

    private void runCheck(boolean force) {
        HorizonRuntimeUpdateService.CheckResult result;
        try {
            result = HorizonRuntimeUpdateService.performCheck(getApplicationContext(), force, this::onProgress);
        } catch (Throwable error) {
            String message = "Falha ao verificar atualizações do Horizon: " + safeMessage(error);
            Log.e(TAG, message, error);
            result = new HorizonRuntimeUpdateService.CheckResult(false, false, true, message);
        }
        final HorizonRuntimeUpdateService.CheckResult outcome = result;
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.post(() -> finishCheck(outcome));
    }

    private void finishCheck(HorizonRuntimeUpdateService.CheckResult result) {
        running = false;
        SharedPreferences state = getSharedPreferences(PREFS, MODE_PRIVATE);
        state.edit()
                .putBoolean("hasResult", true)
                .putBoolean("success", result.success)
                .putBoolean("updated", result.updated)
                .putBoolean("showNotification", result.showNotification)
                .putString("message", result.message)
                .putLong("finishedAt", System.currentTimeMillis())
                .commit();

        // Clear the lock only once the worker has stored the durable outcome.
        HorizonRuntimeUpdateService.finishUpdate(getApplicationContext());
        broadcastStoredResult();

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (result.showNotification && manager != null) {
            stopForeground(STOP_FOREGROUND_DETACH);
            try {
                manager.notify(NOTIFICATION_ID, buildFinishedNotification(result));
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not display updater result notification", error);
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        stopSelf();
    }

    private void onProgress(String stage, int completed, int total) {
        long now = SystemClock.elapsedRealtime();
        boolean stageChanged = stage != null && !stage.equals(lastBroadcastStage);
        boolean finishedStage = total > 0 && completed >= total;

        if (stageChanged || now - lastBroadcastAt >= 500 || finishedStage) {
            broadcastStoredProgress(stage, completed, total);
            lastBroadcastAt = now;
            lastBroadcastStage = stage == null ? "" : stage;
        }

        if (stageChanged || now - lastNotificationAt >= 700 || finishedStage) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                try {
                    manager.notify(NOTIFICATION_ID, buildProgressNotification(stage, completed, total));
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not refresh updater progress notification", error);
                }
            }
            lastNotificationAt = now;
        }
    }

    private void broadcastStoredProgress() {
        broadcastStoredProgress(
                HorizonRuntimeUpdateService.progressStage(getApplicationContext()),
                HorizonRuntimeUpdateService.progressCompleted(getApplicationContext()),
                HorizonRuntimeUpdateService.progressTotal(getApplicationContext()));
    }

    private void broadcastStoredProgress(String stage, int completed, int total) {
        Intent event = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        event.putExtra("stage", stage == null ? "" : stage);
        event.putExtra("completed", completed);
        event.putExtra("total", total);
        sendBroadcast(event);
    }

    private void broadcastStoredResult() {
        SharedPreferences state = getSharedPreferences(PREFS, MODE_PRIVATE);
        Intent event = new Intent(ACTION_FINISHED).setPackage(getPackageName());
        event.putExtra("success", state.getBoolean("success", false));
        event.putExtra("updated", state.getBoolean("updated", false));
        event.putExtra("showNotification", state.getBoolean("showNotification", false));
        event.putExtra("message", state.getString("message",
                HorizonRuntimeUpdateService.lastStatus(getApplicationContext())));
        event.putExtra("finishedAt", state.getLong("finishedAt", 0L));
        sendBroadcast(event);
    }

    private Notification buildProgressNotification(String stage, int completed, int total) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 33, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Atualizador do motor Horizon")
                .setContentText(compact(stage, completed, total))
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (total > 0 && completed >= 0) {
            int percent = (int) Math.max(0, Math.min(100, completed * 100L / total));
            builder.setProgress(100, percent, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private Notification buildFinishedNotification(HorizonRuntimeUpdateService.CheckResult result) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 34, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String title = result.success ? "Motor Horizon atualizado" : "Falha na atualização do Horizon";
        builder.setSmallIcon(result.success ? android.R.drawable.stat_sys_download_done
                        : android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(result.message)
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .setAutoCancel(true);
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Atualizações do Horizon", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Estado e progresso das atualizações do motor nativo Horizon.");
        manager.createNotificationChannel(channel);
    }

    private static String compact(String stage, int completed, int total) {
        String safeStage = stage == null || stage.trim().isEmpty()
                ? "Verificando versão publicada…" : stage;
        if (total > 0 && completed >= 0) {
            int percent = (int) Math.max(0, Math.min(100, completed * 100L / total));
            return safeStage + " · " + percent + "%";
        }
        return safeStage;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "Erro desconhecido.";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void onDestroy() {
        if (running) {
            // Do not clear the persisted lock here: ACTION_QUERY/START_STICKY recovery may retry it.
            executor.shutdownNow();
        } else {
            executor.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
