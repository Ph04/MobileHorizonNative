package dev.mobilehorizon.fabric;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

/** Bounded, non-executing inventory of one source Fabric JAR. */
final class FabricMetadata {
    static final int MAX_ENTRIES = 200000, MAX_METADATA_BYTES = 1024 * 1024;
    static final long MAX_JAR_BYTES = 512L * 1024 * 1024;
    final Path path; final String sha256, id, name, version;
    final int classCount, maxClassMajor, nestedJarCount;
    final List<EntryPoint> entrypoints; final List<String> dependencies;
    final boolean hasMixins, hasAccessWidener;
    static final class EntryPoint {
        final String group, className, adapter;
        EntryPoint(String g, String c, String a) { group=g; className=c; adapter=a; }
    }
    private FabricMetadata(Path p, String hash, Map<String,Object> d, int count, int major,
            List<EntryPoint> eps, boolean mixins, boolean aw, int nested, List<String> deps) {
        path=p; sha256=hash; id=str(d.get("id")); name=str(d.getOrDefault("name",id));
        version=str(d.getOrDefault("version","unknown")); classCount=count; maxClassMajor=major;
        entrypoints=eps; hasMixins=mixins; hasAccessWidener=aw; nestedJarCount=nested; dependencies=deps;
    }
    static FabricMetadata inspect(Path input) throws Exception {
        Path p=input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("JAR does not exist");
        long size=Files.size(p);
        if (size <= 0 || size > MAX_JAR_BYTES) throw new IllegalArgumentException("JAR size outside supported limit");
        String hash=sha256(p);
        try (JarFile jar=new JarFile(p.toFile(),false)) {
            if (jar.size() > MAX_ENTRIES) throw new IllegalArgumentException("JAR has too many entries");
            JarEntry me=jar.getJarEntry("fabric.mod.json");
            if (me==null || me.getSize()<0 || me.getSize()>MAX_METADATA_BYTES)
                throw new IllegalArgumentException("fabric.mod.json missing or too large");
            Map<String,Object> d;
            try (InputStream in=jar.getInputStream(me)) {
                byte[] raw=in.readNBytes(MAX_METADATA_BYTES+1);
                if (raw.length>MAX_METADATA_BYTES) throw new IllegalArgumentException("fabric.mod.json too large");
                Object parsed=MiniJson.parse(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                if (!(parsed instanceof Map<?,?>)) throw new IllegalArgumentException("fabric.mod.json must be an object");
                d=cast(parsed);
            }
            String id=str(d.get("id"));
            if (!id.matches("[a-z][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid Fabric mod ID");
            int count=0, max=0; Enumeration<JarEntry> entries=jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e=entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                count++;
                try (InputStream in=jar.getInputStream(e)) {
                    byte[] h=in.readNBytes(8);
                    if (h.length!=8 || (h[0]&255)!=0xca || (h[1]&255)!=0xfe || (h[2]&255)!=0xba || (h[3]&255)!=0xbe)
                        throw new IllegalArgumentException("Invalid class file: "+e.getName());
                    max=Math.max(max,((h[6]&255)<<8)|(h[7]&255));
                }
            }
            List<EntryPoint> eps=readEntrypoints(d.get("entrypoints"));
            boolean mixins=nonempty(d.get("mixins")), aw=!str(d.get("accessWidener")).isEmpty();
            Object j=d.get("jars"); int nested=j instanceof List<?>?((List<?>)j).size():0;
            List<String> deps=new ArrayList<>(); Object depends=d.get("depends");
            if (depends instanceof Map<?,?>) for (Map.Entry<?,?> e:((Map<?,?>)depends).entrySet())
                deps.add(String.valueOf(e.getKey())+"="+String.valueOf(e.getValue()));
            return new FabricMetadata(p,hash,d,count,max,eps,mixins,aw,nested,deps);
        }
    }
    List<String> blockers() {
        List<String> b=new ArrayList<>();
        if (hasMixins) b.add("mixin_transformations_not_supported");
        if (hasAccessWidener) b.add("access_widener_not_supported");
        if (nestedJarCount>0) b.add("nested_jars_not_supported");
        int runtimeMajor=44+Runtime.version().feature();
        if (maxClassMajor>runtimeMajor) b.add("class_major_"+maxClassMajor+"_exceeds_runtime_major_"+runtimeMajor);
        for (EntryPoint ep:entrypoints) if (!"default".equals(ep.adapter)) b.add("entrypoint_adapter_not_supported:"+ep.adapter);
        return b;
    }
    Map<String,Object> summary() {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("path",path.toString()); m.put("sha256",sha256); m.put("mod_id",id); m.put("name",name);
        m.put("version",version); m.put("class_count",classCount); m.put("max_class_major",maxClassMajor);
        m.put("runtime_java_feature",Runtime.version().feature());
        List<Map<String,Object>> eps=new ArrayList<>();
        for (EntryPoint ep:entrypoints) { Map<String,Object> x=new LinkedHashMap<>();
            x.put("group",ep.group); x.put("class",ep.className); x.put("adapter",ep.adapter); eps.add(x); }
        m.put("entrypoints",eps); m.put("has_mixins",hasMixins); m.put("has_access_widener",hasAccessWidener);
        m.put("nested_jar_count",nestedJarCount); m.put("dependencies",dependencies); m.put("execution_blockers",blockers());
        return m;
    }
    private static List<EntryPoint> readEntrypoints(Object raw) {
        List<EntryPoint> out=new ArrayList<>(); if (!(raw instanceof Map<?,?>)) return out;
        for (Map.Entry<?,?> e:((Map<?,?>)raw).entrySet()) add(out,String.valueOf(e.getKey()),e.getValue());
        return out;
    }
    private static void add(List<EntryPoint> out,String group,Object value) {
        if (value instanceof String) out.add(new EntryPoint(group,(String)value,"default"));
        else if (value instanceof List<?>) for (Object x:(List<?>)value) add(out,group,x);
        else if (value instanceof Map<?,?>) {
            Map<String,Object> m=cast(value); String clazz=str(m.get("value"));
            String adapter=str(m.getOrDefault("adapter","default"));
            if (!clazz.isEmpty()) out.add(new EntryPoint(group,clazz,adapter.isEmpty()?"default":adapter));
        }
    }
    private static boolean nonempty(Object x) {
        if (x==null) return false; if (x instanceof List<?>) return !((List<?>)x).isEmpty();
        if (x instanceof String) return !((String)x).isBlank(); return true;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object x) { return (Map<String,Object>)x; }
    private static String str(Object x) { return x==null?"":String.valueOf(x); }
    private static String sha256(Path p) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(p)){ byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1)d.update(b,0,n); }
        StringBuilder out=new StringBuilder(); for(byte b:d.digest())out.append(String.format("%02x",b&255)); return out.toString();
    }
}
