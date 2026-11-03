package dev.mobilehorizon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs ViaProxy's Microsoft device-code flow outside the activity lifecycle. */
public final class CrossVersionService extends Service {
    static final String ACTION_START = "dev.mobilehorizon.app.CROSSVERSION_SIGN_IN";
    static final String ACTION_CANCEL = "dev.mobilehorizon.app.CROSSVERSION_SIGN_IN_CANCEL";
    static final String ACTION_QUERY = "dev.mobilehorizon.app.CROSSVERSION_SIGN_IN_QUERY";
    static final String ACTION_PROGRESS = "dev.mobilehorizon.app.CROSSVERSION_SIGN_IN_PROGRESS";
    static final String ACTION_CODE = "dev.mobilehorizon.app.CROSSVERSION_SIGN_IN_CODE";
    static final String ACTION_FINISHED = "dev.mobilehorizon.app.CROSSVERSION_SIGN_IN_FINISHED";

    private static final String CHANNEL_ID = "crossversion-signin-v1";
    private static final int NOTIFICATION_ID = 26041;
    private static final String STATE = "crossversion_state";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile Process child;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelled.set(true);
            sendProgress("Cancelamento solicitado", "");
            Process runningChild = child;
            if (runningChild != null) runningChild.destroy();
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            broadcastStoredState();
            if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return running ? START_STICKY : START_NOT_STICKY;
        if (running) {
            sendProgress("Autenticação Microsoft em andamento", "");
            return START_STICKY;
        }
        if (CrossVersionManager.hasMicrosoftAccount(this)) {
            finish(true, "Uma conta Microsoft já está adicionada ao ViaProxy.");
            return START_NOT_STICKY;
        }

        running = true;
        cancelled.set(false);
        getSharedPreferences(STATE, MODE_PRIVATE).edit()
                .putString(KEY_STATE, "starting")
                .putString(KEY_CODE, "")
                .putString(KEY_MESSAGE, "")
                .apply();
        startForeground(NOTIFICATION_ID, notification("Preparando autenticação Microsoft", true));
        executor.submit(this::runSignIn);
        return START_NOT_STICKY;
    }

    private void runSignIn() {
        String outcome = "Autenticação cancelada.";
        boolean success = false;
        try {
            sendProgress("Verificando Java 25 e ViaProxy", "");
            CrossVersionManager.JavaRuntime runtime =
                    CrossVersionManager.findJavaRuntime(getApplicationContext());
            CrossVersionManager.ensureViaProxyDownloaded(getApplicationContext(),
                    (stage, completed, total) -> sendProgress(stage, total > 0
                            ? completed + " / " + total + " bytes" : ""));
            File jar = CrossVersionManager.viaProxyJar(getApplicationContext());
            if (!jar.isFile()) throw new java.io.IOException("O arquivo ViaProxy não foi encontrado após o download.");

            ProcessBuilder builder = buildJavaProcess(runtime, jar);
            Process process = builder.start();
            child = process;
            OutputStream stdin = process.getOutputStream();
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread readerThread = new Thread(() -> readOutput(process, lines),
                    "crossversion-via-proxy-signin-output");
            readerThread.setDaemon(true);
            readerThread.start();

            sendProgress("Abrindo o fluxo seguro da Microsoft", "");
            long launchAt = System.currentTimeMillis();
            boolean commandSent = false;
            long expiresAt = 0L;
            ArrayDeque<String> recent = new ArrayDeque<>();
            while (!cancelled.get()) {
                if (CrossVersionManager.hasMicrosoftAccount(this)) {
                    success = true;
                    outcome = "Conta Microsoft adicionada ao ViaProxy. Ela será usada apenas pelo proxy ao entrar em servidores traduzidos.";
                    break;
                }
                if (!process.isAlive()) {
                    outcome = "ViaProxy encerrou a autenticação antes de adicionar a conta. "
                            + joinLines(recent);
                    break;
                }
                if (!commandSent && System.currentTimeMillis() - launchAt >= 12_000L) {
                    stdin.write("account add microsoft\n".getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                    commandSent = true;
                    expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
                    sendProgress("Solicitando código de dispositivo à Microsoft", "");
                }
                String line = lines.poll(300, TimeUnit.MILLISECONDS);
                if (line != null) {
                    String clean = stripAnsi(line).trim();
                    if (!clean.isEmpty()) {
                        recent.addLast(clean);
                        while (recent.size() > 12) recent.removeFirst();
                    }
                    String code = extractDeviceCode(clean);
                    if (code != null) {
                        String url = "https://www.microsoft.com/link";
                        getSharedPreferences(STATE, MODE_PRIVATE).edit()
                                .putString(KEY_STATE, "code")
                                .putString(KEY_CODE, code)
                                .putString(KEY_URL, url)
                                .apply();
                        Intent event = new Intent(ACTION_CODE).setPackage(getPackageName());
                        event.putExtra("code", code);
                        event.putExtra("url", url);
                        sendBroadcast(event);
                        sendProgress("Aguardando aprovação na Microsoft", code);
                    }
                }
                if (commandSent && expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
                    outcome = "O código de dispositivo expirou. Solicite outro código e tente novamente.";
                    break;
                }
            }
            if (cancelled.get()) outcome = "Autenticação Microsoft cancelada.";
            stopChild(process, stdin);
            child = null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            outcome = "Autenticação Microsoft interrompida.";
        } catch (Exception error) {
            outcome = message(error);
        } finally {
            Process runningChild = child;
            if (runningChild != null) {
                runningChild.destroyForcibly();
                try { runningChild.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                child = null;
            }
            running = false;
            finish(success || CrossVersionManager.hasMicrosoftAccount(this), outcome);
        }
    }

    private ProcessBuilder buildJavaProcess(CrossVersionManager.JavaRuntime runtime, File jar) {
        ArrayList<String> command = new ArrayList<>();
        command.add("/system/bin/sh");
        command.add("-c");
        command.add("exec /system/bin/linker64 \"$@\"");
        command.add("linker64");
        command.add(runtime.java.getAbsolutePath());
        command.add(runtime.java.getAbsolutePath());
        command.add("-jar");
        command.add(jar.getAbsolutePath());
        command.add("cli");
        command.add("--bind-address");
        command.add("127.0.0.1:0");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(jar.getParentFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.putAll(runtime.environment);
        environment.put("JAVA_HOME", runtime.javaHome.getAbsolutePath());
        environment.put("LD_LIBRARY_PATH", runtime.environment.getOrDefault("LD_LIBRARY_PATH", ""));
        environment.put("HOME", new File(runtime.root, "home").getAbsolutePath());
        environment.put("TMPDIR", runtime.temporaryDirectory.getAbsolutePath());
        String options = runtime.environment.getOrDefault("JAVA_TOOL_OPTIONS", "");
        options = appendOption(options, "-Xms32m", "-Xms");
        options = appendOption(options, "-Xmx256m", "-Xmx");
        options = appendOption(options, "-XX:ActiveProcessorCount=2", "-XX:ActiveProcessorCount=");
        options = appendOption(options, "-XX:ParallelGCThreads=1", "-XX:ParallelGCThreads=");
        options = appendOption(options, "-XX:ConcGCThreads=1", "-XX:ConcGCThreads=");
        options = appendOption(options, "-XX:-UsePerfData", "-XX:-UsePerfData");
        options = appendOption(options, "-Djava.io.tmpdir=" + runtime.temporaryDirectory.getAbsolutePath(),
                "-Djava.io.tmpdir=");
        environment.put("JAVA_TOOL_OPTIONS", options);
        return builder;
    }

    private static String appendOption(String current, String value, String prefix) {
        for (String token : current.split("\\s+")) {
            if (token.equals(value) || token.startsWith(prefix)) return current;
        }
        return current.isEmpty() ? value : current + " " + value;
    }

    private static void readOutput(Process process, BlockingQueue<String> output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.offer(line);
        } catch (Exception ignored) { }
    }

    private static String stripAnsi(String text) {
        return text == null ? "" : text.replaceAll("\\u001B\\[[;\\d]*m", "")
                .replace("\u001b", "");
    }

    private static String extractDeviceCode(String line) {
        if (line == null) return null;
        String tail = null;
        int marker = line.indexOf("otc=");
        if (marker >= 0) tail = line.substring(marker + 4);
        if (tail == null) {
            marker = line.toLowerCase(java.util.Locale.ROOT).indexOf("code: ");
            if (marker >= 0) tail = line.substring(marker + 6);
        }
        if (tail == null) return null;
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (Character.isLetterOrDigit(c)) code.append(c);
            else break;
        }
        return code.length() >= 4 ? code.toString() : null;
    }

    private static void stopChild(Process process, OutputStream stdin) {
        try {
            stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Exception ignored) { }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy();
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void sendProgress(String stage, String detail) {
        getSharedPreferences(STATE, MODE_PRIVATE).edit()
                .putString(KEY_STATE, "running")
                .putString(KEY_MESSAGE, stage)
                .apply();
        Intent event = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        event.putExtra("stage", stage);
        event.putExtra("detail", detail == null ? "" : detail);
        sendBroadcast(event);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && running) manager.notify(NOTIFICATION_ID, notification(stage, true));
    }

    private void finish(boolean success, String message) {
        running = false;
        String state = success ? "done" : (cancelled.get() ? "cancelled" : "failed");
        getSharedPreferences(STATE, MODE_PRIVATE).edit()
                .putString(KEY_STATE, state)
                .putString(KEY_CODE, success ? "" : getSharedPreferences(STATE, MODE_PRIVATE).getString(KEY_CODE, ""))
                .putString(KEY_MESSAGE, message == null ? "" : message)
                .apply();
        Intent event = new Intent(ACTION_FINISHED).setPackage(getPackageName());
        event.putExtra("success", success);
        event.putExtra("message", message == null ? "" : message);
        sendBroadcast(event);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID,
                notification(success ? "Conta Microsoft adicionada ao ViaProxy"
                        : "Autenticação CrossVersion finalizada", false));
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private void broadcastStoredState() {
        if (running) {
            sendProgress("Autenticação Microsoft em andamento", "");
            return;
        }
        Intent event = new Intent(ACTION_FINISHED).setPackage(getPackageName());
        event.putExtra("success", CrossVersionManager.hasMicrosoftAccount(this));
        event.putExtra("message", getSharedPreferences(STATE, MODE_PRIVATE)
                .getString(KEY_MESSAGE, ""));
        sendBroadcast(event);
    }

    private Notification notification(String text, boolean ongoing) {
        Intent open = new Intent(this, CrossVersionActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 41, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(ongoing ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done)
                .setContentTitle(UiLanguage.translate(this, "CrossVersion · Microsoft"))
                .setContentText(UiLanguage.translate(this, text))
                .setContentIntent(openIntent)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(!ongoing);
        if (ongoing) {
            Intent cancel = new Intent(this, CrossVersionService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelIntent = PendingIntent.getService(this, 42, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent).build());
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "CrossVersion · autenticação", NotificationManager.IMPORTANCE_LOW));
    }

    private static String message(Throwable error) {
        if (error == null) return "Erro desconhecido.";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }

    private static final String KEY_STATE = "sign_in_state";
    private static final String KEY_CODE = "sign_in_code";
    private static final String KEY_URL = "sign_in_url";
    private static final String KEY_MESSAGE = "sign_in_message";

    @Override public void onDestroy() {
        cancelled.set(true);
        Process process = child;
        if (process != null) process.destroyForcibly();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
