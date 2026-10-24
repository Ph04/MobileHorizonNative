package dev.mobilehorizon.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lifecycle supervisor for the opt-in Java/Rust bridge control plane. */
final class FabricBridgeSession implements AutoCloseable {
    private static final String TAG = "MobileHorizonFabric";
    private static final String HOST_ASSET = "fabric-java-host.jar";
    private static final String LOCK_ASSET = "fabric-java-host-runtime.json";
    private static final String MAIN_CLASS = "dev.mobilehorizon.fabric.FabricJavaCompatHost";
    private static final long MAX_HOST_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_LOCK_BYTES = 64 * 1024;
    private static final int MAX_FRAME_BYTES = 64 * 1024;

    private final Context context;
    private final ServerSocket server;
    private final String token;
    private final File runtimeRoot;
    private final File hostJar;
    private final File tempDir;
    private final File hostLog;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile Socket peer;
    private volatile Process child;

    private FabricBridgeSession(Context input) throws Exception {
        context = input.getApplicationContext();
        runtimeRoot = new File(new File(context.getFilesDir(), "horizon"), "fabric-bridge-runtime");
        ensureDirectory(runtimeRoot);
        File hostDir = new File(runtimeRoot, "host");
        ensureDirectory(hostDir);
        hostJar = new File(hostDir, HOST_ASSET);
        tempDir = new File(runtimeRoot, "java-host-tmp");
        ensureDirectory(tempDir);
        hostLog = new File(runtimeRoot, "java-host-stderr.log");
        token = UUID.randomUUID().toString();
        server = new ServerSocket();
        server.setReuseAddress(false);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        try {
            installVerifiedHost(context.getAssets());
            Os.setenv("HORIZON_FABRIC_BRIDGE_PORT", Integer.toString(server.getLocalPort()), true);
            Os.setenv("HORIZON_FABRIC_BRIDGE_TOKEN", token, true);
            Os.setenv("HORIZON_FABRIC_BRIDGE_PROTOCOL", "1", true);
        } catch (Exception error) {
            try { server.close(); } catch (Exception ignored) {}
            clearEnvironment();
            throw error;
        }
    }

    static FabricBridgeSession prepare(Context context) throws Exception {
        return new FabricBridgeSession(context);
    }

    int port() { return server.getLocalPort(); }

    void start() {
        if (cancelled.get() || !started.compareAndSet(false, true)) return;
        Thread worker = new Thread(this::runSession, "horizon-fabric-java-session");
        worker.setDaemon(true);
        worker.start();
    }

    private void runSession() {
        try (Socket socket = server.accept()) {
            if (cancelled.get()) return;
            if (!socket.getInetAddress().isLoopbackAddress()) throw new IOException("Bridge peer is not loopback.");
            peer = socket;
            socket.setTcpNoDelay(true);
            Log.i(TAG, "Native Horizon connected; lazily preparing Java 25.");
            JdkRuntimeInstaller.RuntimePaths java = JdkRuntimeInstaller.ensureInstalled(
                    context, cancelled,
                    (stage, detail, completed, total) ->
                            Log.i(TAG, stage + ": " + detail + " (" + completed + "/" + total + ")"));
            if (cancelled.get()) return;
            String[] command = {
                    "/system/bin/sh", "-c", "exec /system/bin/linker64 \"$@\"", "linker64",
                    java.java.getAbsolutePath(),
                    "-Xms16m", "-Xmx128m", "-XX:+UseSerialGC", "-XX:-UsePerfData",
                    "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + tempDir.getAbsolutePath(),
                    "-cp", hostJar.getAbsolutePath(), MAIN_CLASS, "--bridge-stdio"
            };
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(java.processEnvironment(tempDir));
            builder.environment().put("HORIZON_FABRIC_BRIDGE_TOKEN", token);
            builder.redirectError(hostLog);
            Process process = builder.start();
            child = process;
            Log.i(TAG, "Java bridge control plane started; original Fabric mods remain unsupported.");

            AtomicBoolean relayStopped = new AtomicBoolean(false);
            Thread toHost = new Thread(() -> {
                try { relayLines(socket.getInputStream(), process.getOutputStream(), relayStopped); }
                catch (Exception error) {
                    if (!cancelled.get()) Log.i(TAG, "Rust->Java stream ended: " + safeMessage(error));
                } finally { stopRelay(relayStopped, socket, process); }
            }, "horizon-fabric-rust-to-java");
            Thread toRust = new Thread(() -> {
                try { relayLines(process.getInputStream(), socket.getOutputStream(), relayStopped); }
                catch (Exception error) {
                    if (!cancelled.get()) Log.i(TAG, "Java->Rust stream ended: " + safeMessage(error));
                } finally { stopRelay(relayStopped, socket, process); }
            }, "horizon-fabric-java-to-rust");
            toHost.setDaemon(true);
            toRust.setDaemon(true);
            toHost.start();
            toRust.start();
            while (!cancelled.get() && !relayStopped.get() && process.isAlive()) {
                process.waitFor(250, TimeUnit.MILLISECONDS);
            }
            stopRelay(relayStopped, socket, process);
            Log.i(TAG, "Fabric Java bridge session ended.");
        } catch (Exception error) {
            if (!cancelled.get()) Log.e(TAG, "Fabric Java bridge session failed; game execution is not blocked.", error);
        } finally {
            Socket socket = peer;
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            Process process = child;
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void relayLines(InputStream rawInput, OutputStream rawOutput,
                                   AtomicBoolean stopped) throws IOException {
        BufferedInputStream input = new BufferedInputStream(rawInput);
        BufferedOutputStream output = new BufferedOutputStream(rawOutput);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int value;
        while (!stopped.get() && (value = input.read()) != -1) {
            frame.write(value);
            if (frame.size() > MAX_FRAME_BYTES) throw new IOException("JSONL frame exceeds 64 KiB.");
            if (value == '\n') {
                output.write(frame.toByteArray());
                output.flush();
                frame.reset();
            }
        }
        if (frame.size() > 0) throw new IOException("EOF in an incomplete JSONL frame.");
        throw new IOException("Bridge peer disconnected.");
    }

    private static void stopRelay(AtomicBoolean stopped, Socket socket, Process process) {
        if (!stopped.compareAndSet(false, true)) return;
        try { socket.close(); } catch (Exception ignored) {}
        try { process.getOutputStream().close(); } catch (Exception ignored) {}
        if (process.isAlive()) process.destroy();
    }

    private void installVerifiedHost(AssetManager assets) throws Exception {
        JSONObject manifest;
        try (InputStream input = assets.open(LOCK_ASSET)) {
            manifest = new JSONObject(new String(readLimited(input, MAX_LOCK_BYTES), StandardCharsets.UTF_8));
        }
        String digest = manifest.optString("sha256", "").toLowerCase(Locale.ROOT);
        long expectedBytes = manifest.optLong("size_bytes", -1L);
        if (manifest.optInt("schema_version", -1) != 1
                || !"MobileHorizonNative/fabric-java-host".equals(manifest.optString("artifact", ""))
                || !MAIN_CLASS.equals(manifest.optString("main_class", ""))
                || manifest.optInt("protocol_version", -1) != 1
                || !digest.matches("[0-9a-f]{64}")
                || expectedBytes <= 0 || expectedBytes > MAX_HOST_BYTES) {
            throw new IOException("O manifesto do host Java Fabric está ausente ou inválido.");
        }
        if (hostJar.isFile() && hostJar.length() == expectedBytes && digest.equals(sha256(hostJar))
                && validateHostJar(hostJar)) return;
        File partial = new File(hostJar.getParentFile(), ".fabric-java-host.jar.part");
        if (partial.exists() && !partial.delete()) throw new IOException("Não foi possível limpar o host Java parcial.");
        try {
            try (InputStream input = new BufferedInputStream(assets.open(HOST_ASSET));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_HOST_BYTES) throw new IOException("O host Java Fabric excede 16 MiB.");
                    output.write(buffer, 0, read);
                }
            }
            if (partial.length() != expectedBytes || !digest.equals(sha256(partial)) || !validateHostJar(partial)) {
                throw new IOException("O host Java Fabric não corresponde ao manifesto verificado.");
            }
            try {
                Files.move(partial.toPath(), hostJar.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicMoveUnavailable) {
                Files.move(partial.toPath(), hostJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (partial.exists()) partial.delete();
        }
    }

    private static boolean validateHostJar(File jar) {
        if (!jar.isFile() || jar.length() <= 0 || jar.length() > MAX_HOST_BYTES) return false;
        try (ZipFile zip = new ZipFile(jar)) {
            for (String name : new String[]{
                    "dev/mobilehorizon/fabric/FabricJavaCompatHost.class",
                    "dev/mobilehorizon/fabric/MiniJson.class",
                    "dev/mobilehorizon/fabric/FabricMetadata.class"}) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() <= 0) return false;
            }
            return true;
        } catch (Exception error) { return false; }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > maxBytes) throw new IOException("Manifesto do host Fabric excede o limite.");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Não foi possível criar " + directory.getAbsolutePath());
        }
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? "" : String.valueOf(error.getMessage());
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private void clearEnvironment() {
        try { Os.unsetenv("HORIZON_FABRIC_BRIDGE_PORT"); } catch (Exception ignored) {}
        try { Os.unsetenv("HORIZON_FABRIC_BRIDGE_TOKEN"); } catch (Exception ignored) {}
        try { Os.unsetenv("HORIZON_FABRIC_BRIDGE_PROTOCOL"); } catch (Exception ignored) {}
    }

    @Override public void close() {
        if (!cancelled.compareAndSet(false, true)) return;
        clearEnvironment();
        try { server.close(); } catch (Exception ignored) {}
        Socket socket = peer;
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        Process process = child;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
