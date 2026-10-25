package dev.mobilehorizon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import android.os.SystemClock;

/** Keeps Horizon runtime verification/download/install alive while the launcher is backgrounded. */
public final class HorizonRuntimeUpdateForegroundService extends Service {
    static final String ACTION_CHECK = "dev.mobilehorizon.app.HORIZON_RUNTIME_CHECK";
    static final String ACTION_PROGRESS = "dev.mobilehorizon.app.HORIZON_RUNTIME_PROGRESS";
    static final String ACTION_FINISHED = "dev.mobilehorizon.app.HORIZON_RUNTIME_FINISHED";
    static final String EXTRA_FORCE = "force_check";
    private static final String CHANNEL_ID = "horizon-runtime-updates-v1";
    private static final int NOTIFICATION_ID = 26033;

    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile long lastNotificationAt;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        String action = intent == null ? ACTION_CHECK : intent.getAction();
        if (!ACTION_CHECK.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        running = true;
        boolean force = intent != null && intent.getBooleanExtra(EXTRA_FORCE, false);
        String initial = force ? "Verificando atualizações do Horizon…"
                : "Verificando atualizações em segundo plano…";
        startForeground(NOTIFICATION_ID, buildProgressNotification(initial, -1, -1));
        executor.execute(() -> runCheck(force));
        return START_STICKY;
    }

    private void runCheck(boolean force) {
        HorizonRuntimeUpdateService.CheckResult result;
        try {
            result = HorizonRuntimeUpdateService.performCheck(getApplicationContext(), force,
                    this::onProgress);
        } catch (Throwable error) {
            String message = "Falha ao verificar atualizações do Horizon: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            result = new HorizonRuntimeUpdateService.CheckResult(false, false, true, message);
        }
        final HorizonRuntimeUpdateService.CheckResult outcome = result;
        // Service lifecycle and notification finalization stay on Android's main thread.
        mainHandler.post(() -> finishCheck(outcome));
    }

    private void finishCheck(HorizonRuntimeUpdateService.CheckResult result) {
        running = false;
        HorizonRuntimeUpdateService.finishUpdate(getApplicationContext());
        Intent finished = new Intent(ACTION_FINISHED).setPackage(getPackageName());
        finished.putExtra("success", result.success);
        finished.putExtra("updated", result.updated);
        finished.putExtra("message", result.message);
        sendBroadcast(finished);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (result.showNotification && manager != null) {
            stopForeground(STOP_FOREGROUND_DETACH);
            manager.notify(NOTIFICATION_ID, buildFinishedNotification(result));
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        stopSelf();
    }

    private void onProgress(String stage, int completed, int total) {
        Intent event = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        event.putExtra("stage", stage);
        event.putExtra("completed", completed);
        event.putExtra("total", total);
        sendBroadcast(event);

        long now = SystemClock.elapsedRealtime();
        boolean finishedStage = total > 0 && completed >= total;
        if (now - lastNotificationAt >= 600 || finishedStage) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID,
                    buildProgressNotification(stage, completed, total));
            lastNotificationAt = now;
        }
    }

    private Notification buildProgressNotification(String stage, int completed, int total) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 33, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Atualizando motor Horizon")
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

    private static String compact(String stage, int completed, int total) {
        String safeStage = stage == null || stage.trim().isEmpty()
                ? "Verificando versão publicada…" : stage;
        if (total > 0 && completed >= 0) {
            int percent = (int) Math.max(0, Math.min(100, completed * 100L / total));
            return safeStage + " · " + percent + "%";
        }
        return safeStage;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Atualizações do Horizon", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Estado, progresso e conclusão do download do motor nativo Horizon.");
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
