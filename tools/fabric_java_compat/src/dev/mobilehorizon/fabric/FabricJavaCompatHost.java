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
