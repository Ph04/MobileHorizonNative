#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes" "$TMP/src/test" "$TMP/fixture-classes"
find "$ROOT/tools/fabric_java_compat/src" -name '*.java' -print > "$TMP/sources.txt"
javac --release 17 -d "$TMP/classes" @"$TMP/sources.txt"
cat > "$TMP/src/test/FixtureMod.java" <<'JAVA'
package test;
import net.fabricmc.api.ModInitializer;
public final class FixtureMod implements ModInitializer {
  @Override public void onInitialize() { System.setProperty("fabric.compat.fixture.initialized", "true"); }
}
JAVA
javac --release 17 -cp "$TMP/classes" -d "$TMP/fixture-classes" "$TMP/src/test/FixtureMod.java"
cat > "$TMP/fabric.mod.json" <<'JSON'
{"schemaVersion":1,"id":"compatfixture","version":"1.0.0","entrypoints":{"main":"test.FixtureMod"}}
JSON
jar --create --file "$TMP/fixture.jar" -C "$TMP/fixture-classes" . -C "$TMP" fabric.mod.json
java -cp "$TMP/classes" dev.mobilehorizon.fabric.FabricJavaCompatHost --probe "$TMP/fixture.jar" > "$TMP/probe.jsonl"
grep -Fq '"event":"mod_inspected"' "$TMP/probe.jsonl"
grep -Fq '"mod_id":"compatfixture"' "$TMP/probe.jsonl"
HORIZON_FABRIC_JAVA_EXECUTION=1 java -cp "$TMP/classes" dev.mobilehorizon.fabric.FabricJavaCompatHost --run "$TMP/fixture.jar" > "$TMP/run.jsonl"
grep -Fq '"event":"entrypoint_invoked"' "$TMP/run.jsonl"
grep -Fq '"event":"mod_execution_completed"' "$TMP/run.jsonl"
cat > "$TMP/mixin.mod.json" <<'JSON'
{"schemaVersion":1,"id":"compatfixture","version":"1.0.0","mixins":["fixture.mixins.json"],"entrypoints":{"main":"test.FixtureMod"}}
JSON
mkdir "$TMP/mixin-package"
cp "$TMP/mixin.mod.json" "$TMP/mixin-package/fabric.mod.json"
jar --create --file "$TMP/mixin.jar" -C "$TMP/fixture-classes" . -C "$TMP/mixin-package" fabric.mod.json
set +e
HORIZON_FABRIC_JAVA_EXECUTION=1 java -cp "$TMP/classes" dev.mobilehorizon.fabric.FabricJavaCompatHost --run "$TMP/mixin.jar" > "$TMP/mixin.jsonl"
status=$?
set -e
if [[ "$status" -ne 3 ]]; then echo "Expected Mixin fail-closed status 3, got $status" >&2; cat "$TMP/mixin.jsonl" >&2; exit 1; fi
grep -Fq '"event":"mod_execution_blocked"' "$TMP/mixin.jsonl"
grep -Fq 'mixin_transformations_not_supported' "$TMP/mixin.jsonl"
echo "Fabric Java compatibility host tests passed."
