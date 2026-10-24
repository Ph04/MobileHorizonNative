package dev.mobilehorizon.fabric.compat;
import java.util.LinkedHashMap;import java.util.Map;import java.util.Objects;import java.util.concurrent.atomic.AtomicReference;
public final class NativeBridgeClient{
 public interface Transport{Map<String,Object> query(String path,Map<String,Object> payload,long timeoutMillis)throws Exception;}
 private static final AtomicReference<Transport> TRANSPORT=new AtomicReference<>();private NativeBridgeClient(){}
 public static void install(Transport transport){TRANSPORT.set(Objects.requireNonNull(transport,"transport"));}
 public static void clear(Transport transport){TRANSPORT.compareAndSet(transport,null);}
 public static Map<String,Object> query(String path,Map<String,Object> payload){return query(path,payload,10000L);}
 public static Map<String,Object> query(String path,Map<String,Object> payload,long timeoutMillis){
  if(path==null||path.isBlank()||path.length()>128)throw new IllegalArgumentException("Invalid native operation path");
  if(payload==null||payload.size()>64)throw new IllegalArgumentException("Native payload must be bounded");
  if(timeoutMillis<1||timeoutMillis>30000L)throw new IllegalArgumentException("Native query timeout is outside bounds");
  Transport t=TRANSPORT.get();if(t==null)throw new IllegalStateException("Fabric Bridge native query transport is not installed");
  try{Map<String,Object> r=t.query(path,Map.copyOf(new LinkedHashMap<>(payload)),timeoutMillis);if(r==null)throw new IllegalStateException("Native query returned null");return r;}
  catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Native compatibility operation failed: "+path,e);}
 }
}
