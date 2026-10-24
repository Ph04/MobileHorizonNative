#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
HOST="$TMP/fabric-java-host.jar"
bash "$ROOT/tools/fabric_java_compat/build-host.sh" "$HOST"
mkdir -p "$TMP/src/test" "$TMP/fixture-classes" "$TMP/mixin-package"
cat > "$TMP/src/test/FixtureMod.java" <<'JAVA'
package test;
import net.fabricmc.api.ModInitializer;
public final class FixtureMod implements ModInitializer {
  @Override public void onInitialize() { System.setProperty("fabric.compat.fixture.initialized", "true"); }
}
JAVA
javac --release 17 -cp "$HOST" -d "$TMP/fixture-classes" "$TMP/src/test/FixtureMod.java"
cat > "$TMP/fabric.mod.json" <<'JSON'
{"schemaVersion":1,"id":"compatfixture","version":"1.0.0","entrypoints":{"main":"test.FixtureMod"}}
JSON
jar --create --file "$TMP/fixture.jar" -C "$TMP/fixture-classes" . -C "$TMP" fabric.mod.json
java -jar "$HOST" --probe "$TMP/fixture.jar" > "$TMP/probe.jsonl"
grep -Fq '"event":"mod_inspected"' "$TMP/probe.jsonl"
grep -Fq '"mod_id":"compatfixture"' "$TMP/probe.jsonl"
HORIZON_FABRIC_JAVA_EXECUTION=1 java -jar "$HOST" --run "$TMP/fixture.jar" > "$TMP/run.jsonl"
grep -Fq '"event":"entrypoint_invoked"' "$TMP/run.jsonl"
grep -Fq '"event":"mod_execution_completed"' "$TMP/run.jsonl"
cat > "$TMP/mixin-package/fabric.mod.json" <<'JSON'
{"schemaVersion":1,"id":"compatfixture","version":"1.0.0","mixins":["fixture.mixins.json"],"entrypoints":{"main":"test.FixtureMod"}}
JSON
jar --create --file "$TMP/mixin.jar" -C "$TMP/fixture-classes" . -C "$TMP/mixin-package" fabric.mod.json
set +e
HORIZON_FABRIC_JAVA_EXECUTION=1 java -jar "$HOST" --run "$TMP/mixin.jar" > "$TMP/mixin.jsonl"
status=$?
set -e
if [[ "$status" -ne 3 ]]; then echo "Expected Mixin fail-closed status 3, got $status" >&2; cat "$TMP/mixin.jsonl" >&2; exit 1; fi
grep -Fq '"event":"mod_execution_blocked"' "$TMP/mixin.jsonl"
grep -Fq 'mixin_transformations_not_supported' "$TMP/mixin.jsonl"
python3 - "$HOST" <<'PY'
import json, os, subprocess, sys
host = sys.argv[1]
env = os.environ.copy()
env["HORIZON_FABRIC_BRIDGE_TOKEN"] = "test-session-token"
process = subprocess.Popen(
    ["java", "-jar", host, "--bridge-stdio"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=True, encoding="utf-8", env=env, bufsize=1,
)
def send(frame):
    process.stdin.write(json.dumps(frame, separators=(",", ":")) + "\n")
    process.stdin.flush()
def receive():
    line = process.stdout.readline()
    if not line:
        raise AssertionError("bridge host closed stdout: " + process.stderr.read())
    return json.loads(line)
send({"protocol_version": 1, "kind": "hello", "request_id": "hello-1",
      "session_token": "test-session-token"})
hello = receive()
assert hello.get("ok") is True and hello.get("runtime_status") == "bridge_control_plane_only", hello
modules = hello.get("compatibility_modules")
assert isinstance(modules, list) and any(m.get("id") == "world-biomes" and m.get("status") == "native-backend-only" for m in modules), hello
assert any(m.get("id") == "mixin-adapters" and m.get("status") == "blocked" for m in modules), hello
full_snapshot = {
    "world_loaded": True,
    "dimension": "minecraft:overworld",
    "singleplayer": True,
    "player": {"x": 1.5, "y": 64.0, "z": -3.5, "yaw": 90.0, "pitch": 0.0,
               "health": 20.0, "food": 20, "dimension": "minecraft:overworld"},
    "clock": {"game_time": 240, "day_time": 1200},
    "inventory": {"selected": 2, "selected_stack": {"id": "minecraft:diamond_pickaxe",
                  "count": 1, "damage": 3, "max_damage": 1561, "components": []},
                  "slots": [{"slot": 2, "stack": {"id": "minecraft:diamond_pickaxe", "count": 1}}],
                  "offhand": None},
}
send({"kind": "event", "request_id": "snapshot-1", "event": "world_snapshot",
      "payload": full_snapshot})
snapshot_ack = receive()
assert snapshot_ack.get("ok") is True and snapshot_ack.get("world_revision") == 1, snapshot_ack
send({"kind": "query_probe", "request_id": "query-1", "payload": {"path": "player.snapshot"}})
query = receive()
assert query.get("kind") == "query" and query.get("path") == "player.snapshot", query
assert query.get("timeout_ms") == 10000, query
send({"kind": "response", "request_id": query["request_id"], "ok": True,
      "result": full_snapshot["player"]})
query_result = receive()
assert query_result.get("request_id") == "query-1" and query_result.get("ok") is True, query_result
assert query_result.get("query_result", {}).get("dimension") == "minecraft:overworld", query_result
send({"kind": "query_probe", "request_id": "query-2", "payload": {"path": "inventory.snapshot"}})
query = receive()
assert query.get("kind") == "query" and query.get("path") == "inventory.snapshot", query
send({"kind": "response", "request_id": query["request_id"], "ok": True,
      "result": full_snapshot["inventory"]})
query_result = receive()
assert query_result.get("request_id") == "query-2" and query_result.get("ok") is True, query_result
assert query_result.get("query_result", {}).get("selected") == 2, query_result
send({"kind": "shutdown", "request_id": "shutdown-1"})
assert receive().get("ok") is True
process.wait(timeout=5)
assert process.returncode == 0, process.stderr.read()
PY
cat > "$TMP/src/test/NativeBiomeApiFixture.java" <<'JAVA'
package test;
import dev.mobilehorizon.fabric.compat.NativeBiomeApi;
import dev.mobilehorizon.fabric.compat.NativeBridgeClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
public final class NativeBiomeApiFixture {
  public static void main(String[] args) {
    AtomicLong seenTimeout = new AtomicLong();
    NativeBridgeClient.Transport transport = (path, payload, timeoutMillis) -> {
      seenTimeout.set(timeoutMillis);
      return switch (path) {
        case "fabric.biome.catalog" -> Map.of("biomes", List.of(Map.of(
          "numeric_id", 1, "id", "minecraft:forest", "temperature", 0.7,
          "downfall", 0.8, "precipitation", true)));
        case "fabric.biome.sample" -> Map.of("samples", List.of(Map.of(
          "block_position", List.of(-1, 64, -5), "quart_position", List.of(-1, 16, -2),
          "biome_id", "minecraft:forest")));
        case "fabric.biome.search" -> Map.of("found", true, "biome_id", "minecraft:forest",
          "position", List.of(10, 64, 10), "distance_blocks", 11);
        default -> throw new IllegalArgumentException("unexpected native API path " + path);
      };
    };
    NativeBridgeClient.install(transport);
    try {
      var catalog = NativeBiomeApi.catalog();
      if (catalog.size() != 1 || !catalog.get(0).id().equals("minecraft:forest")) throw new AssertionError(catalog);
      var samples = NativeBiomeApi.sample(List.of(new NativeBiomeApi.BlockPos(-1, 64, -5)));
      if (samples.size() != 1 || samples.get(0).quartPosition().x() != -1) throw new AssertionError(samples);
      var search = NativeBiomeApi.findClosest("minecraft:forest",
          new NativeBiomeApi.BlockPos(0, 64, 0), 6400, 32, 64);
      if (!search.found() || search.distanceBlocks().orElse(-1) != 11) throw new AssertionError(search);
      if (seenTimeout.get() != 10000L) throw new AssertionError("Timeout wasn't forwarded: " + seenTimeout.get());
    } finally {
      NativeBridgeClient.clear(transport);
    }
  }
}
JAVA
javac --release 17 -cp "$HOST" -d "$TMP/fixture-classes" "$TMP/src/test/NativeBiomeApiFixture.java"
java -ea -cp "$HOST:$TMP/fixture-classes" test.NativeBiomeApiFixture
echo "Fabric Java host, typed native biome API and bounded JSONL bridge tests passed."
