package dev.mobilehorizon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground Microsoft device-code sign-in with mandatory Minecraft Java entitlement checks. */
public final class MicrosoftAuthService extends Service {
    static final String ACTION_START = "dev.mobilehorizon.app.MSA_START";
    static final String ACTION_CANCEL = "dev.mobilehorizon.app.MSA_CANCEL";
    static final String ACTION_QUERY = "dev.mobilehorizon.app.MSA_QUERY";
    static final String ACTION_ACK = "dev.mobilehorizon.app.MSA_ACK";
    static final String ACTION_PROGRESS = "dev.mobilehorizon.app.MSA_PROGRESS";
    static final String ACTION_DEVICE_CODE = "dev.mobilehorizon.app.MSA_DEVICE_CODE";
    static final String ACTION_FINISHED = "dev.mobilehorizon.app.MSA_FINISHED";

    private static final String PREFS = "microsoft_account_state";
    private static final String CHANNEL_ID = "microsoft-auth-v1";
    private static final int NOTIFICATION_ID = 26033;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile String lastStage = "Aguardando login";
    private volatile String lastDetail = "Login Microsoft";
    private volatile MicrosoftAuthentication.DeviceCode lastDeviceCode;
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
            updateProgress("Cancelamento solicitado", "Aguardando um ponto seguro.");
            return START_NOT_STICKY;
        }
        if (ACTION_ACK.equals(action)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hasResult", false).commit();
            if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            if (running) {
                broadcastProgress(lastStage, lastDetail);
                broadcastDeviceCode();
                return START_STICKY;
            }
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("hasResult", false)) {
                broadcastFinished();
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return running ? START_STICKY : START_NOT_STICKY;
        if (running) {
            broadcastProgress(lastStage, lastDetail);
            broadcastDeviceCode();
            return START_STICKY;
        }

        cancelled.set(false);
        lastDeviceCode = null;
        running = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hasResult", false).apply();
        updateProgress("Iniciando login Microsoft", "Preparando autenticação oficial.");
        startForeground(NOTIFICATION_ID, buildNotification(true, false, false));
        executor.submit(this::runSignIn);
        return START_NOT_STICKY;
    }

    private void runSignIn() {
        boolean success = false;
        String message = "Login não concluído.";
        String name = "";
        String uuid = "";
        try {
            String clientId = BuildConfig.HORIZON_MSA_CLIENT_ID;
            updateProgress("Solicitando código", "A Microsoft vai fornecer um código de uso único.");
            MicrosoftAuthentication.DeviceCode code = MicrosoftAuthentication.requestDeviceCode(clientId);
            lastDeviceCode = code;
            broadcastDeviceCode();
            updateProgress("Aguardando confirmação", "Abra a página de verificação e informe o código exibido no app.");
            MicrosoftAuthentication.MinecraftAccount account =
                    MicrosoftAuthentication.waitForSignIn(clientId, code, cancelled,
                            this::updateProgress);
            checkCancelled();
            EncryptedRefreshTokenStore.save(this, account.refreshToken);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("hasAccount", true)
                    .putString("accountName", account.name)
                    .putString("accountUuid", account.uuid)
                    .putString("accountXuid", account.xuid == null ? "" : account.xuid)
                    .putLong("accountVerifiedAt", System.currentTimeMillis())
                    .commit();
            success = true;
            name = account.name;
            uuid = account.uuid;
            message = "Conta autenticada. A posse de Minecraft Java Edition e o perfil foram verificados pelos serviços oficiais.";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            message = "Login Microsoft cancelado.";
        } catch (Exception error) {
            message = safeMessage(error);
        } catch (Throwable error) {
            message = error.getClass().getSimpleName() + ": " + safeMessage(error);
        } finally {
            running = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("hasResult", true)
                    .putBoolean("success", success)
                    .putBoolean("cancelled", cancelled.get())
                    .putString("message", message)
                    .putString("resultName", name)
                    .putString("resultUuid", uuid)
                    .putLong("finishedAt", System.currentTimeMillis())
                    .commit();
            lastDeviceCode = null;
            lastStage = success ? "Login concluído" : (cancelled.get() ? "Login cancelado" : "Login falhou");
            lastDetail = message;
            broadcastFinished();
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID,
                    buildNotification(false, success, cancelled.get()));
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Login Microsoft cancelado.");
        }
    }

    private void updateProgress(String stage, String detail) {
        if (stage != null && !stage.isEmpty()) lastStage = stage;
        if (detail != null && !detail.isEmpty()) lastDetail = detail;
        broadcastProgress(lastStage, lastDetail);
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationAt >= 700) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID,
                    buildNotification(true, false, false));
            lastNotificationAt = now;
        }
    }

    private void broadcastProgress(String stage, String detail) {
        Intent event = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        event.putExtra("stage", stage);
        event.putExtra("detail", detail);
        sendBroadcast(event);
    }

    private void broadcastDeviceCode() {
        MicrosoftAuthentication.DeviceCode code = lastDeviceCode;
        if (code == null) return;
        Intent event = new Intent(ACTION_DEVICE_CODE).setPackage(getPackageName());
        event.putExtra("userCode", code.userCode);
        event.putExtra("verificationUri", code.verificationUri);
        event.putExtra("message", code.message);
        event.putExtra("expiresIn", code.expiresInSeconds);
        sendBroadcast(event);
    }

    private void broadcastFinished() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Intent event = new Intent(ACTION_FINISHED).setPackage(getPackageName());
        event.putExtra("success", prefs.getBoolean("success", false));
        event.putExtra("cancelled", prefs.getBoolean("cancelled", false));
        event.putExtra("message", prefs.getString("message", "Login finalizado."));
        event.putExtra("accountName", prefs.getString("resultName", ""));
        event.putExtra("accountUuid", prefs.getString("resultUuid", ""));
        sendBroadcast(event);
    }

    private Notification buildNotification(boolean ongoing, boolean success, boolean wasCancelled) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 21, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String title = ongoing ? "Login Microsoft em andamento"
                : success ? "Conta Minecraft verificada"
                : wasCancelled ? "Login Microsoft cancelado" : "Login Microsoft não concluído";
        builder.setSmallIcon(ongoing ? android.R.drawable.stat_sys_download
                        : success ? android.R.drawable.stat_sys_download_done : android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(lastStage + " · " + lastDetail)
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(ongoing)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
        if (ongoing) {
            Intent cancel = new Intent(this, MicrosoftAuthService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelIntent = PendingIntent.getService(this, 22, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent).build());
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Login Microsoft e Minecraft", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Código de login, andamento da autenticação e resultado de validação da licença.");
        manager.createNotificationChannel(channel);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "Erro desconhecido.";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.replaceAll("(?i)(access_token|refresh_token|device_code|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[oculto]");
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
