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

/** Foreground download service so large official downloads can continue while the UI is in background. */
public final class OfficialDownloadService extends Service {
    static final String ACTION_START = "dev.mobilehorizon.app.DOWNLOAD_START";
    static final String ACTION_CANCEL = "dev.mobilehorizon.app.DOWNLOAD_CANCEL";
    static final String ACTION_QUERY = "dev.mobilehorizon.app.DOWNLOAD_QUERY";
    static final String ACTION_PROGRESS = "dev.mobilehorizon.app.DOWNLOAD_PROGRESS";
    static final String ACTION_FINISHED = "dev.mobilehorizon.app.DOWNLOAD_FINISHED";
    static final String EXTRA_ALL_ASSETS = "all_assets";
    private static final String CHANNEL_ID = "minecraft-downloads";
    private static final int NOTIFICATION_ID = 26031;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile OfficialMinecraftInstaller.Progress lastProgress;
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
            cancelled.set(true);
            broadcastProgress(new OfficialMinecraftInstaller.Progress(
                    "Cancelamento solicitado", "Aguardando o ponto seguro", 0, -1, 0, -1, 0, -1));
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            if (running) {
                broadcastProgress(lastProgress);
                return START_STICKY;
            }
            android.content.SharedPreferences state = getSharedPreferences("official_download_state", MODE_PRIVATE);
            if (state.getBoolean("hasResult", false)) {
                broadcastFinished(
                        state.getBoolean("success", false),
                        state.getString("message", "Download finalizado."),
                        state.getInt("libraries", 0),
                        state.getInt("assets", 0),
                        state.getString("versionSha1", ""));
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return running ? START_STICKY : START_NOT_STICKY;
        }
        if (running) {
            broadcastProgress(lastProgress);
            return START_STICKY;
        }

        boolean allAssets = intent.getBooleanExtra(EXTRA_ALL_ASSETS, false);
        getSharedPreferences("official_download_state", MODE_PRIVATE).edit()
                .putBoolean("hasResult", false)
                .putBoolean("allAssetsRequested", allAssets)
                .apply();
        cancelled.set(false);
        running = true;
        OfficialMinecraftInstaller.Progress initial = new OfficialMinecraftInstaller.Progress(
                "Iniciando download oficial", "Minecraft " + OfficialMinecraftInstaller.VERSION,
                0, -1, 0, -1, 0, -1);
        lastProgress = initial;
        startForeground(NOTIFICATION_ID, buildNotification(initial, true));
        executor.submit(() -> runDownload(allAssets));
        return START_NOT_STICKY;
    }

    private void runDownload(boolean allAssets) {
        String outcome = "Download concluído.";
        boolean success = false;
        int libraries = 0;
        int assets = 0;
        String versionSha1 = "";
        try {
            File root = new File(getFilesDir(), "minecraft");
            OfficialMinecraftInstaller.Result base = OfficialMinecraftInstaller.installBase(root, cancelled,
                    this::onDownloadProgress);
            libraries = base.librariesDownloadedOrVerified;
            versionSha1 = base.versionJsonSha1;
            if (allAssets) {
                onDownloadProgress(new OfficialMinecraftInstaller.Progress(
                        "Lendo índice de assets oficiais", base.assetIndexId, 0, -1, 0, -1, 0, -1));
                assets = OfficialMinecraftInstaller.downloadAllAssets(root, cancelled, this::onDownloadProgress);
            }
            success = true;
            outcome = allAssets
                    ? "Arquivos base e assets completos verificados."
                    : "Arquivos base verificados. Os assets completos ainda são opcionais.";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            outcome = "Download cancelado. Arquivos parciais não verificados foram descartados.";
        } catch (Exception error) {
            outcome = message(error);
        } finally {
            running = false;
            getSharedPreferences("official_download_state", MODE_PRIVATE).edit()
                    .putBoolean("hasResult", true)
                    .putBoolean("success", success)
                    .putString("message", outcome)
                    .putInt("libraries", libraries)
                    .putInt("assets", assets)
                    .putString("versionSha1", versionSha1)
                    .putLong("finishedAt", System.currentTimeMillis())
                    .commit();
            broadcastFinished(success, outcome, libraries, assets, versionSha1);
            OfficialMinecraftInstaller.Progress finished = new OfficialMinecraftInstaller.Progress(
                    success ? "Download concluído" : (cancelled.get() ? "Download cancelado" : "Download falhou"),
                    outcome, success ? 1 : 0, 1, 0, -1, 0, -1);
            lastProgress = finished;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(finished, false));
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private void onDownloadProgress(OfficialMinecraftInstaller.Progress progress) {
        if (progress == null) return;
        lastProgress = progress;
        broadcastProgress(progress);
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationAt >= 500 || progress.total > 0 && progress.completed >= progress.total) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(progress, true));
            lastNotificationAt = now;
        }
    }

    private Notification buildNotification(OfficialMinecraftInstaller.Progress progress, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("MobileHorizon · Minecraft " + OfficialMinecraftInstaller.VERSION)
                .setContentText(progress == null ? "Download oficial" : compact(progress.stage, progress.item))
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
        if (ongoing) {
            Intent cancel = new Intent(this, OfficialDownloadService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelIntent = PendingIntent.getService(this, 2, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent).build());
        }
        if (progress != null && progress.total > 0) {
            builder.setProgress(progress.total, Math.min(progress.completed, progress.total), false);
        } else if (progress != null && progress.itemTotalBytes > 0) {
            int value = (int) Math.max(0, Math.min(1000,
                    progress.itemBytes * 1000L / progress.itemTotalBytes));
            builder.setProgress(1000, value, false);
        } else if (ongoing) {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private void broadcastProgress(OfficialMinecraftInstaller.Progress progress) {
        if (progress == null) return;
        Intent intent = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        intent.putExtra("stage", progress.stage);
        intent.putExtra("item", progress.item);
        intent.putExtra("completed", progress.completed);
        intent.putExtra("total", progress.total);
        intent.putExtra("itemBytes", progress.itemBytes);
        intent.putExtra("itemTotalBytes", progress.itemTotalBytes);
        intent.putExtra("completedBytes", progress.completedBytes);
        intent.putExtra("totalBytes", progress.totalBytes);
        sendBroadcast(intent);
    }

    private void broadcastFinished(boolean success, String message, int libraries, int assets, String versionSha1) {
        Intent intent = new Intent(ACTION_FINISHED).setPackage(getPackageName());
        intent.putExtra("success", success);
        intent.putExtra("message", message);
        intent.putExtra("libraries", libraries);
        intent.putExtra("assets", assets);
        intent.putExtra("versionSha1", versionSha1);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Downloads do Minecraft", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progresso e conclusão dos downloads oficiais de Minecraft.");
        manager.createNotificationChannel(channel);
    }

    private static String compact(String stage, String item) {
        if (stage == null) return item == null ? "Download oficial" : item;
        if (item == null || item.isEmpty()) return stage;
        return stage + " · " + item;
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
}
