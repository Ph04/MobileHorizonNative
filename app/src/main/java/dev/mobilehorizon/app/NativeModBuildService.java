package dev.mobilehorizon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the remote native-mod build alive and visible while GitHub Actions runs. */
public final class NativeModBuildService extends Service {
    static final String EXTRA_INSTANCE_ID = "native_mod_build_instance_id";
    private static final String PREFS = "native_mod_build_status";
    private static final String CHANNEL_ID = "native_mod_build";
    private static final int NOTIFICATION_ID = 8421;
    private static final long WAKE_LOCK_MS = 2L * 60L * 60L * 1000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean started;
    private PowerManager.WakeLock wakeLock;
    private String activeInstanceId;

    static void start(Context context, String instanceId) {
        if (!GameInstanceManager.isValidInstanceId(instanceId)) {
            throw new IllegalArgumentException("ID da instância inválido.");
        }
        setState(context, instanceId, "RUNNING", "Preparando a compilação remota…");
        Intent intent = new Intent(context, NativeModBuildService.class);
        intent.putExtra(EXTRA_INSTANCE_ID, instanceId);
        context.startForegroundService(intent);
    }

    static String state(Context context, String instanceId) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(instanceId + ".state", "IDLE");
    }

    static String message(Context context, String instanceId) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(instanceId + ".message", "");
    }

    static long updatedAt(Context context, String instanceId) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(instanceId + ".updated", 0L);
    }

    static boolean isRunning(Context context, String instanceId) {
        return "RUNNING".equals(state(context, instanceId));
    }

    private static void setState(Context context, String instanceId,
                                 String state, String message) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        preferences.edit()
                .putString(instanceId + ".state", state)
                .putString(instanceId + ".message", message == null ? "" : message)
                .putLong(instanceId + ".updated", System.currentTimeMillis())
                .commit();
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (started) return START_NOT_STICKY;
        String instanceId = intent == null ? null
                : intent.getStringExtra(EXTRA_INSTANCE_ID);
        if (!GameInstanceManager.isValidInstanceId(instanceId)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        activeInstanceId = instanceId;
        started = true;
        startForeground(NOTIFICATION_ID, notification("Iniciando compilação…", true));
        acquireWakeLock();
        worker.execute(() -> executeBuild(instanceId, startId));
        return START_NOT_STICKY;
    }

    private void executeBuild(String instanceId, int startId) {
        try {
            String token = GitHubTokenStore.load(getApplicationContext());
            if (token == null || token.isEmpty()) {
                throw new IllegalStateException("Configure o token Fine-grained do GitHub na tela de módulos.");
            }
            String installedVersion = NativeModBuildClient.buildAndInstall(
                    getApplicationContext(), instanceId, token,
                    message -> updateProgress(instanceId, message));
            updateState(instanceId, "SUCCESS",
                    "Compilação concluída. Runtime " + installedVersion + " instalada nesta instância.");
            updateNotification("Runtime nativa instalada: " + installedVersion, false);
        } catch (Exception error) {
            String detail = error.getMessage();
            if (detail == null || detail.trim().isEmpty()) detail = error.getClass().getSimpleName();
            if (detail.length() > 900) detail = detail.substring(0, 900);
            updateState(instanceId, "FAILED", detail);
            updateNotification("Falha na compilação: " + detail, false);
            android.util.Log.e("HorizonNativeBuild",
                    "Remote .hnmod build failed for instance " + instanceId + ": " + detail);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try { wakeLock.release(); } catch (RuntimeException ignored) { }
            }
            wakeLock = null;
            stopForeground(false);
            stopSelf(startId);
        }
    }

    private void updateProgress(String instanceId, String message) {
        updateState(instanceId, "RUNNING", message);
        updateNotification(message, true);
    }

    private void updateState(String instanceId, String state, String message) {
        setState(getApplicationContext(), instanceId, state, message);
    }

    private void updateNotification(String message, boolean ongoing) {
        NotificationManager manager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(message, ongoing));
    }

    private Notification notification(String message, boolean ongoing) {
        Intent open = new Intent(this, NativeModsActivity.class);
        open.putExtra(NativeModsActivity.EXTRA_INSTANCE_ID, activeInstanceId);
        PendingIntent pending = PendingIntent.getActivity(this, 8422, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("MobileHorizon · Compilação .hnmod")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(pending)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(!ongoing)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Compilação de módulos nativos", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progresso da compilação remota dos módulos .hnmod.");
        manager.createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) return;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "MobileHorizon:NativeModBuild");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_MS);
        } catch (Exception error) {
            android.util.Log.w("HorizonNativeBuild", "Could not acquire build wake lock.", error);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (RuntimeException ignored) { }
        }
        worker.shutdown();
        super.onDestroy();
    }
}
