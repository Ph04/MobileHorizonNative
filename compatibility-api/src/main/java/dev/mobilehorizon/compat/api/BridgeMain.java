package dev.mobilehorizon.compat.api;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Separate-process entry point for user-installed compatibility modules.
 *
 * Args: socket moduleId moduleVersion instanceId minecraftVersion loaderId
 *       loaderVersion moduleDirectory entrypointJar entrypointClass
 */
public final class BridgeMain {
    public static final int API_VERSION = 1;
    private static final int MAX_LINE_CHARS = 512 * 1024;

    private BridgeMain() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable error) {
            System.err.println("MobileHorizon compatibility bridge failed: " + error);
            error.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args == null || args.length != 10) {
            throw new IllegalArgumentException("expected 10 bridge arguments");
        }
        Path socketPath = Path.of(args[0]).toAbsolutePath().normalize();
        String moduleId = requireArg(args[1], "module id", 96);
        String moduleVersion = requireArg(args[2], "module version", 64);
        String instanceId = requireArg(args[3], "instance id", 80);
        String minecraftVersion = requireArg(args[4], "Minecraft version", 32);
        String loaderId = requireArg(args[5], "loader id", 24);
        String loaderVersion = requireArg(args[6], "loader version", 48);
        Path moduleDirectory = Path.of(args[7]).toRealPath();
        Path entrypointJar = Path.of(args[8]).toRealPath();
        String entrypointClass = requireArg(args[9], "entrypoint class", 240);

        Path payloadRoot = moduleDirectory.resolve("payload").normalize();
        if (!payloadRoot.toRealPath().startsWith(moduleDirectory)
                || !entrypointJar.startsWith(payloadRoot.toRealPath())
                || !Files.isRegularFile(entrypointJar, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(entrypointJar)) {
            throw new SecurityException("module entrypoint is outside the installed payload");
        }

        List<URL> jars = findPayloadJars(payloadRoot);
        URL expectedEntrypointUrl = entrypointJar.toUri().toURL();
        if (jars.isEmpty() || jars.stream().noneMatch(
                url -> url.toExternalForm().equals(expectedEntrypointUrl.toExternalForm()))) {
            throw new IOException("the installed payload does not contain its declared entrypoint JAR");
        }

        try (SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
             URLClassLoader loader = new URLClassLoader(jars.toArray(URL[]::new),
                     CompatibilityPlugin.class.getClassLoader())) {
            socket.connect(UnixDomainSocketAddress.of(socketPath));
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    Channels.newInputStream(socket), StandardCharsets.UTF_8));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                    Channels.newOutputStream(socket), StandardCharsets.UTF_8));
            Wire wire = new Wire(input, output);

            wire.send("HELLO|" + API_VERSION + "|"
                    + ProtocolCodec.encode(moduleId) + "|"
                    + ProtocolCodec.encode(moduleVersion) + "|"
                    + ProtocolCodec.encode(instanceId) + "|"
                    + ProtocolCodec.encode(minecraftVersion) + "|"
                    + ProtocolCodec.encode(loaderId) + "|"
                    + ProtocolCodec.encode(loaderVersion));

            String accepted = wire.read();
            if (!("ACCEPT|" + API_VERSION).equals(accepted)) {
                throw new SecurityException("Rust bridge did not accept compatibility API version "
                        + API_VERSION + ": " + accepted);
            }

            try {
                Thread.currentThread().setContextClassLoader(loader);
                Class<?> candidate = Class.forName(entrypointClass, true, loader);
                if (!CompatibilityPlugin.class.isAssignableFrom(candidate)) {
                    throw new IllegalArgumentException("module entrypoint must implement "
                            + CompatibilityPlugin.class.getName());
                }
                CompatibilityPlugin plugin = (CompatibilityPlugin) candidate
                        .getDeclaredConstructor().newInstance();
                CompatibilityContext context = new BridgeContext(
                        wire, moduleId, moduleVersion, instanceId, minecraftVersion,
                        loaderId, loaderVersion);

                plugin.onLoad(context);
                wire.send("LOADED|" + API_VERSION);
                context.activate();
                while (true) {
                    String line = wire.read();
                    if ("STOP".equals(line)) {
                        plugin.onUnload();
                        wire.send("STOPPED");
                        return;
                    }
                    if (line.startsWith("EVENT|")) {
                        String[] fields = line.split("\\|", 4);
                        if (fields.length != 4) throw new IOException("malformed event frame");
                        long sequence = Long.parseLong(fields[1]);
                        String type = ProtocolCodec.decode(fields[2]);
                        String payload = ProtocolCodec.decode(fields[3]);
                        plugin.onEvent(new CompatibilityEvent(sequence, type, payload), context);
                        wire.send("RESULT|" + sequence + "|OK");
                    } else if ("PING".equals(line)) {
                        wire.send("PONG");
                    } else {
                        throw new IOException("unknown Rust bridge frame");
                    }
                }
            } catch (Throwable error) {
                try {
                    wire.send("ERROR|" + ProtocolCodec.encode(error.toString()));
                } catch (IOException ignored) {
                    // The original module failure remains the useful cause.
                }
                throw error;
            }
        }
    }

    private static List<URL> findPayloadJars(Path payloadRoot) throws IOException {
        List<URL> jars = new ArrayList<>();
        try (var files = Files.walk(payloadRoot)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(payloadRoot.toRealPath())) {
                    throw new SecurityException("symbolic-link or escaped JAR in module payload");
                }
                jars.add(file.toUri().toURL());
            }
        }
        return jars;
    }

    private static String requireArg(String value, String label, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " is missing or too long");
        }
        ProtocolCodec.requireSingleLine(value, label, maximum);
        return value;
    }

    private static final class Wire {
        private final BufferedReader input;
        private final BufferedWriter output;

        Wire(BufferedReader input, BufferedWriter output) {
            this.input = input;
            this.output = output;
        }

        synchronized void send(String line) throws IOException {
            ProtocolCodec.requireSingleLine(line, "bridge frame", MAX_LINE_CHARS);
            output.write(line);
            output.newLine();
            output.flush();
        }

        String read() throws IOException {
            StringBuilder line = new StringBuilder();
            while (true) {
                int next = input.read();
                if (next < 0) {
                    if (line.length() == 0) throw new IOException("Rust bridge disconnected");
                    break;
                }
                if (next == '\n') break;
                if (next == '\r') continue;
                if (line.length() >= MAX_LINE_CHARS) {
                    throw new IOException("bridge frame exceeds size limit");
                }
                line.append((char) next);
            }
            return line.toString();
        }
    }

    private static final class BridgeContext implements CompatibilityContext {
        private final Wire wire;
        private final Object outputLock = new Object();
        private final List<String> pendingFrames = new ArrayList<>();
        private boolean outputActive;
        private final String moduleId;
        private final String moduleVersion;
        private final String instanceId;
        private final String minecraftVersion;
        private final String loaderId;
        private final String loaderVersion;

        BridgeContext(Wire wire, String moduleId, String moduleVersion, String instanceId,
                      String minecraftVersion, String loaderId, String loaderVersion) {
            this.wire = wire;
            this.moduleId = moduleId;
            this.moduleVersion = moduleVersion;
            this.instanceId = instanceId;
            this.minecraftVersion = minecraftVersion;
            this.loaderId = loaderId;
            this.loaderVersion = loaderVersion;
        }

        @Override public String moduleId() { return moduleId; }
        @Override public String moduleVersion() { return moduleVersion; }
        @Override public String instanceId() { return instanceId; }
        @Override public String minecraftVersion() { return minecraftVersion; }
        @Override public String loaderId() { return loaderId; }
        @Override public String loaderVersion() { return loaderVersion; }

        private void sendOrQueue(String frame) throws IOException {
            synchronized (outputLock) {
                if (!outputActive) {
                    if (pendingFrames.size() >= 64) {
                        throw new IOException("module emitted too many startup frames");
                    }
                    pendingFrames.add(frame);
                    return;
                }
            }
            wire.send(frame);
        }

        void activate() throws IOException {
            List<String> toFlush;
            synchronized (outputLock) {
                outputActive = true;
                toFlush = new ArrayList<>(pendingFrames);
                pendingFrames.clear();
            }
            for (String frame : toFlush) wire.send(frame);
        }

        @Override public void log(String message) {
            String safe = message == null ? "" : message;
            if (safe.length() > 4096) safe = safe.substring(0, 4096);
            try {
                sendOrQueue("LOG|" + ProtocolCodec.encode(safe));
            } catch (IOException error) {
                throw new IllegalStateException("could not send bridge log", error);
            }
        }

        @Override public void emitAction(String type, String payloadJson) {
            try {
                ProtocolCodec.requireSingleLine(type, "action type", 64);
                String json = payloadJson == null ? "{}" : payloadJson;
                if (json.length() > 64 * 1024) {
                    throw new IllegalArgumentException("action payload exceeds 64 KiB");
                }
                sendOrQueue("ACTION|" + ProtocolCodec.encode(type) + "|"
                        + ProtocolCodec.encode(json));
            } catch (IOException error) {
                throw new IllegalStateException("could not send bridge action", error);
            }
        }
    }
}
