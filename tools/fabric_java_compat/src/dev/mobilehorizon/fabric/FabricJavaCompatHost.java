package dev.mobilehorizon.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** J0 standalone entrypoint host. Not yet connected to Horizon's game loop. */
public final class FabricJavaCompatHost {
    private FabricJavaCompatHost() {}
    public static void main(String[] args) { int status=run(args); if(status!=0) System.exit(status); }

    private static int run(String[] args) {
        if (args.length == 1 && args[0].equals("--bridge-stdio")) return runBridgeStdio();
        if(args.length<2 || !(args[0].equals("--probe")||args[0].equals("--run"))) {
            emit("usage",Map.of("usage","FabricJavaCompatHost --probe <mod.jar> | --run <mod.jar> [dependency.jar ...]")); return 2;
        }
        if(args[0].equals("--run") && !"1".equals(System.getenv("HORIZON_FABRIC_JAVA_EXECUTION"))) {
            emit("execution_blocked",Map.of("reason","explicit_environment_opt_in_required")); return 3;
        }
        FabricMetadata mod;
        try { mod=FabricMetadata.inspect(Path.of(args[1])); emit("mod_inspected",mod.summary()); }
        catch(Throwable e) { emit("mod_inspection_failed",error(e)); return 2; }
        if(args[0].equals("--probe")) return 0;
        List<String> blockers=mod.blockers();
        if(!blockers.isEmpty()){emit("mod_execution_blocked",Map.of("mod_id",mod.id,"blockers",blockers));return 3;}
        if(mod.entrypoints.isEmpty()){emit("mod_execution_skipped",Map.of("mod_id",mod.id,"reason","no_entrypoints"));return 0;}

        try {
            List<URL> urls=new ArrayList<>(); urls.add(mod.path.toUri().toURL()); List<String> deps=new ArrayList<>();
            for(int i=2;i<args.length;i++){Path p=Path.of(args[i]).toAbsolutePath().normalize();
                if(!Files.isRegularFile(p))throw new IllegalArgumentException("Dependency JAR is missing: "+p);
                urls.add(p.toUri().toURL()); deps.add(p.getFileName().toString());}
            emit("mod_classloader_start",Map.of("mod_id",mod.id,"dependency_jars",deps));
            try(URLClassLoader loader=new URLClassLoader(urls.toArray(new URL[0]),FabricJavaCompatHost.class.getClassLoader())){
                int invoked=0;
                for(String group:List.of("preLaunch","main","client")) for(FabricMetadata.EntryPoint ep:mod.entrypoints){
                    if(!ep.group.equals(group))continue;
                    try {
                        String text=invoke(loader,ep); invoked++;
                        emit("entrypoint_invoked",Map.of("mod_id",mod.id,"group",ep.group,"class",ep.className));
                        if(!text.isEmpty())emit("mod_output",Map.of("mod_id",mod.id,"group",ep.group,"class",ep.className,
                            "text",text.length()>4096?text.substring(0,4096):text));
                    } catch(Throwable e){Map<String,Object> m=error(e);m.put("mod_id",mod.id);m.put("group",ep.group);m.put("class",ep.className);
                        emit("entrypoint_failed",m);return 4;}
                }
                for(FabricMetadata.EntryPoint ep:mod.entrypoints) {
                    if(!List.of("preLaunch","main","client","server").contains(ep.group))
                        emit("entrypoint_group_skipped",Map.of("mod_id",mod.id,"group",ep.group,"class",ep.className,"reason","entrypoint_group_not_implemented"));
                    else if(ep.group.equals("server"))
                        emit("entrypoint_group_skipped",Map.of("mod_id",mod.id,"group",ep.group,"class",ep.className,"reason","client_host_does_not_run_server_entrypoints"));
                }
                emit("mod_execution_completed",Map.of("mod_id",mod.id,"invoked_entrypoints",invoked,
                    "note","No Horizon game APIs or Fabric event callbacks are connected in J0"));
            }
            return 0;
        } catch(Throwable e){emit("mod_execution_failed",error(e));return 4;}
    }


    private static final int MAX_BRIDGE_LINE_CHARS = 64 * 1024;
    private static final class BridgeState {
        boolean authenticated;
        boolean closed;
        long tick;
        long revision;
        Map<String,Object> world = new LinkedHashMap<>(Map.of(
                "world_loaded", false, "dimension", "", "singleplayer", false));
        String lastChat = "";
        long chatMessages;
    }

    /** Long-lived JSONL control plane. It deliberately does not load Fabric JARs. */
    private static int runBridgeStdio() {
        String expectedToken = System.getenv("HORIZON_FABRIC_BRIDGE_TOKEN");
        if (expectedToken == null || expectedToken.isBlank()) {
            System.err.println("Fabric bridge stdio refused: session token is missing.");
            return 3;
        }
        BridgeState state = new BridgeState();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Thread bridgeDispatcher = Thread.currentThread();
        dev.mobilehorizon.fabric.compat.NativeBridgeClient.Transport transport =
                (path, payload, timeoutMillis) -> {
                    if (Thread.currentThread() != bridgeDispatcher) throw new IllegalStateException("Native queries must run on the serialized bridge dispatcher thread.");
                    return queryStdio(input, path, payload, timeoutMillis);
                };
        dev.mobilehorizon.fabric.compat.NativeBridgeClient.install(transport);
        try {
            String line;
            while ((line = readBoundedLine(input)) != null) {
                Map<String,Object> request;
                try {
                    Object parsed = MiniJson.parse(line);
                    if (!(parsed instanceof Map<?,?>)) throw new IllegalArgumentException("frame must be a JSON object");
                    request = castMap(parsed);
                } catch (Throwable error) {
                    emitBridgeFrame(response("", false, Map.of("error", "invalid_json_frame",
                            "message", safeText(error.getMessage(), 512))));
                    continue;
                }
                Map<String,Object> answer;
                try {
                    answer = handleBridgeFrame(state, request, input, expectedToken);
                } catch (Throwable error) {
                    answer = response(string(request.get("request_id")), false,
                            Map.of("error", "bridge_handler_failed",
                                    "message", safeText(error.getMessage(), 512)));
                }
                emitBridgeFrame(answer);
                if (state.closed) return 0;
            }
            return 0;
        } catch (Throwable error) {
            System.err.println("Fabric bridge stdio terminated: " + error.getClass().getSimpleName()
                    + ": " + safeText(error.getMessage(), 1024));
            return 4;
        } finally {
            dev.mobilehorizon.fabric.compat.NativeBridgeClient.clear(transport);
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong BRIDGE_QUERY_IDS = new java.util.concurrent.atomic.AtomicLong();

    private static Map<String,Object> queryStdio(BufferedReader input, String path, Map<String,Object> payload, long timeoutMillis) throws Exception {
        if (timeoutMillis < 1 || timeoutMillis > 30_000L) throw new IllegalArgumentException("invalid_query_timeout");
        String queryId = "java-query-" + BRIDGE_QUERY_IDS.incrementAndGet();
        Map<String,Object> query = new LinkedHashMap<>();
        query.put("protocol_version", 1); query.put("kind", "query"); query.put("request_id", queryId);
        query.put("path", path); query.put("payload", payload); emitBridgeFrame(query);
        String line = readBoundedLine(input);
        if (line == null) throw new IOException("native_query_reply_missing");
        Object parsed = MiniJson.parse(line);
        if (!(parsed instanceof Map<?,?>)) throw new IOException("native_query_reply_not_object");
        Map<String,Object> reply = castMap(parsed);
        if (!"response".equals(string(reply.get("kind"))) || !queryId.equals(string(reply.get("request_id"))) || !Boolean.TRUE.equals(reply.get("ok"))) throw new IllegalStateException(safeText(reply.get("error"), 512));
        Object result = reply.get("result");
        if (result instanceof Map<?,?>) return castMap(result);
        Map<String,Object> wrapped = new LinkedHashMap<>(); wrapped.put("value", result); return wrapped;
    }

    private static Map<String,Object> handleBridgeFrame(
            BridgeState state, Map<String,Object> request, BufferedReader input, String expectedToken) throws Exception {
        String requestId = string(request.get("request_id"));
        String kind = string(request.get("kind"));
        if (requestId.isEmpty() || requestId.length() > 128) {
            return response("", false, Map.of("error", "invalid_request_id"));
        }
        if (kind.equals("hello")) {
            boolean tokenOk = expectedToken.equals(string(request.get("session_token")));
            boolean protocolOk = number(request.get("protocol_version"), -1) == 1;
            if (!tokenOk || !protocolOk) {
                return response(requestId, false, Map.of("error",
                        tokenOk ? "unsupported_protocol" : "session_token_mismatch"));
            }
            state.authenticated = true;
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("runtime_status", "bridge_control_plane_only");
            body.put("loader_version", "0.19.5");
            body.put("minecraft_version", "26.3");
            body.put("capabilities", List.of("world.snapshot", "player.snapshot", "clock.snapshot",
                    "inventory.snapshot", "inventory.selected", "inventory.offhand",
                    "world.lifecycle", "client_tick", "chat.received"));
            body.put("compatibility_modules", dev.mobilehorizon.fabric.compat.CompatibilityModuleRegistry.describe());
            body.put("not_implemented", List.of("minecraft_java_classes", "fabric_api_entrypoints", "mixins",
                    "registries", "rendering", "resource_reload", "gameplay_mutation"));
            return response(requestId, true, body);
        }
        if (!state.authenticated) return response(requestId, false, Map.of("error", "hello_required"));
        if (kind.equals("shutdown")) {
            state.closed = true;
            return response(requestId, true, Map.of("runtime_status", "stopping"));
        }
        if (kind.equals("query_probe")) {
            String queryId = requestId + ":query";
            Map<String,Object> payload = asMap(request.get("payload"));
            String path = string(payload.getOrDefault("path", "world.snapshot"));
            if (path.length() > 128) return response(requestId, false, Map.of("error", "query_path_too_long"));
            Map<String,Object> query = new LinkedHashMap<>();
            query.put("protocol_version", 1);
            query.put("kind", "query");
            query.put("request_id", queryId);
            query.put("path", path);
            emitBridgeFrame(query);
            String replyLine = readBoundedLine(input);
            if (replyLine == null) return response(requestId, false, Map.of("error", "query_reply_missing"));
            Object parsed = MiniJson.parse(replyLine);
            if (!(parsed instanceof Map<?,?>)) return response(requestId, false, Map.of("error", "query_reply_not_object"));
            Map<String,Object> reply = castMap(parsed);
            if (!"response".equals(string(reply.get("kind"))) || !queryId.equals(string(reply.get("request_id")))) {
                return response(requestId, false, Map.of("error", "query_reply_id_mismatch"));
            }
            if (!Boolean.TRUE.equals(reply.get("ok"))) {
                return response(requestId, false, Map.of("error", safeText(reply.get("error"), 256)));
            }
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("query_path", path);
            body.put("query_result", reply.get("result"));
            return response(requestId, true, body);
        }
        if (!kind.equals("event")) return response(requestId, false, Map.of("error", "unsupported_frame_kind"));
        String event = string(request.get("event"));
        Map<String,Object> payload = asMap(request.get("payload"));
        switch (event) {
            case "world_snapshot" -> {
                // Preserve the full native snapshot; do not drop player/clock/inventory fields.
                Map<String,Object> next = new LinkedHashMap<>(payload);
                next.put("world_loaded", Boolean.TRUE.equals(payload.get("world_loaded")));
                next.put("dimension", payload.get("dimension") instanceof String ? payload.get("dimension") : "");
                next.put("singleplayer", Boolean.TRUE.equals(payload.get("singleplayer")));
                next.putIfAbsent("player", null);
                next.putIfAbsent("clock", null);
                if (!next.containsKey("inventory")) {
                    Map<String,Object> emptyInventory = new LinkedHashMap<>();
                    emptyInventory.put("selected", 0);
                    emptyInventory.put("selected_stack", null);
                    emptyInventory.put("slots", List.of());
                    emptyInventory.put("offhand", null);
                    next.put("inventory", emptyInventory);
                }
                if (!next.equals(state.world)) {
                    state.world = next;
                    state.revision++;
                }
            }
            case "world_join" -> {
                Map<String,Object> next = new LinkedHashMap<>(payload);
                next.put("world_loaded", true);
                state.world = next;
                state.revision++;
            }
            case "world_leave" -> {
                state.world = new LinkedHashMap<>(Map.of(
                        "world_loaded", false, "dimension", "", "singleplayer", false));
                state.revision++;
            }
            case "client_tick" -> state.tick = number(payload.get("tick"), state.tick);
            case "chat_received", "chat_send_observed" -> {
                state.lastChat = safeText(payload.get("text"), 4096);
                state.chatMessages++;
            }
            default -> {
                return response(requestId, false, Map.of("error", "unsupported_event", "event", safeText(event, 128)));
            }
        }
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("event", event);
        body.put("world_revision", state.revision);
        body.put("latest_tick", state.tick);
        body.put("runtime_status", "bridge_control_plane_only");
        return response(requestId, true, body);
    }

    private static Map<String,Object> response(String requestId, boolean ok, Map<String,Object> body) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("protocol_version", 1);
        result.put("kind", "response");
        result.put("request_id", requestId);
        result.put("ok", ok);
        result.putAll(body);
        return result;
    }

    private static void emitBridgeFrame(Map<String,Object> frame) {
        System.out.println(MiniJson.stringify(frame));
        System.out.flush();
    }

    private static String readBoundedLine(BufferedReader input) throws IOException {
        StringBuilder result = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return result.toString();
            if (value == '\r') continue;
            if (result.length() >= MAX_BRIDGE_LINE_CHARS) throw new IOException("bridge frame exceeds 64 KiB");
            result.append((char)value);
        }
        if (result.length() == 0) return null;
        throw new IOException("EOF in an incomplete bridge frame");
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> castMap(Object value) { return (Map<String,Object>) value; }
    private static Map<String,Object> asMap(Object value) {
        return value instanceof Map<?,?> ? castMap(value) : Map.of();
    }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value, long fallback) {
        return value instanceof Number ? ((Number)value).longValue() : fallback;
    }
    private static String safeText(Object value, int limit) {
        String text = value == null ? "" : String.valueOf(value);
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static String invoke(ClassLoader loader,FabricMetadata.EntryPoint ep) throws Exception {
        PrintStream old=System.out; ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(PrintStream capture=new PrintStream(bytes,true,StandardCharsets.UTF_8)){
            System.setOut(capture);
            try {
                Class<?> type=Class.forName(ep.className,true,loader); Object instance=type.getDeclaredConstructor().newInstance();
                if(ep.group.equals("preLaunch")) {
                    if(!(instance instanceof PreLaunchEntrypoint))throw new IllegalStateException("Entrypoint does not implement PreLaunchEntrypoint");
                    ((PreLaunchEntrypoint)instance).onPreLaunch();
                } else if(ep.group.equals("main")) {
                    if(!(instance instanceof ModInitializer))throw new IllegalStateException("Entrypoint does not implement ModInitializer");
                    ((ModInitializer)instance).onInitialize();
                } else if(ep.group.equals("client")) {
                    if(!(instance instanceof ClientModInitializer))throw new IllegalStateException("Entrypoint does not implement ClientModInitializer");
                    ((ClientModInitializer)instance).onInitializeClient();
                }
            } finally { System.setOut(old); }
        } finally { System.setOut(old); }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    private static Map<String,Object> error(Throwable e){Map<String,Object> m=new LinkedHashMap<>();
        m.put("error_type",e.getClass().getName());m.put("message",String.valueOf(e.getMessage()));return m;}
    private static void emit(String event,Map<String,Object> fields){Map<String,Object> row=new LinkedHashMap<>();
        row.put("schema_version",1);row.put("component","fabric_java_host");row.put("event",event);row.putAll(fields);
        System.out.println(MiniJson.stringify(row));}
}
