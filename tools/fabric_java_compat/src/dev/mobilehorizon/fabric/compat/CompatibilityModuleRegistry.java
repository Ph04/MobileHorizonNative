package dev.mobilehorizon.fabric.compat;
import java.util.ArrayList;import java.util.LinkedHashMap;import java.util.List;import java.util.Map;
public final class CompatibilityModuleRegistry{
 private CompatibilityModuleRegistry(){}
 public static List<Map<String,Object>> describe(){
  List<Map<String,Object>> m=new ArrayList<>();
  m.add(module("bridge-protocol","implemented",List.of(),List.of("bounded-jsonl","request-ids","session-auth","readonly-snapshots","bounded-game-thread-query-queue"),List.of("No Minecraft Java object identity","Only allow-listed native operations")));
  m.add(module("entrypoint-loader","preflight-only",List.of("bridge-protocol"),List.of("metadata-preflight","loader-provider-smoke-test"),List.of("Knot is not launched against a Java Minecraft client in normal gameplay")));
  m.add(module("api-events","contract-only",List.of("bridge-protocol","entrypoint-loader"),List.of("bounded-event-contract"),List.of("Transport observations are not Fabric callbacks","Priority/cancellation/order are not implemented")));
  m.add(module("world-biomes","native-backend-only",List.of("bridge-protocol"),List.of("fabric.biome.catalog","fabric.biome.sample","fabric.biome.search"),List.of("Java Level/Biome/Registry facade missing","Biome tags unavailable")));
  m.add(module("registries-components","contract-only",List.of("bridge-protocol","api-events"),List.of("registration-transaction-model"),List.of("Live item/component/creative-tab registration unavailable")));
  m.add(module("play-networking","contract-only",List.of("bridge-protocol","api-events"),List.of("payload-registration-validation"),List.of("Native packet dispatcher and Fabric codecs unavailable")));
  m.add(module("mixin-adapters","blocked",List.of("entrypoint-loader","api-events"),List.of(),List.of("Minecraft Java targets missing","Access widener/Mixin application incomplete")));
  m.add(module("client-ui-render","contract-only",List.of("bridge-protocol","api-events"),List.of("bounded-render-command-model"),List.of("Screen/GuiGraphics/Font facades missing","Native renderer doesn't consume mod commands")));
  m.add(module("resources-reload","contract-only",List.of("bridge-protocol"),List.of("resource-layer-contract"),List.of("Java ResourceManager reload semantics not implemented")));
  m.add(module("diagnostics","implemented",List.of("bridge-protocol"),List.of("static-abi-audit","module-status-report"),List.of("Static inspection isn't in-game evidence")));
  return List.copyOf(m);
 }
 private static Map<String,Object> module(String id,String status,List<String> requires,List<String> provides,List<String> limitations){Map<String,Object> v=new LinkedHashMap<>();v.put("id",id);v.put("version",1);v.put("status",status);v.put("requires",requires);v.put("provides",provides);v.put("limitations",limitations);return Map.copyOf(v);}
}
