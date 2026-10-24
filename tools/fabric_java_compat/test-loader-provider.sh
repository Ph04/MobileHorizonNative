#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 3 ]]; then
  echo "usage: $0 <official-fabric-loader.jar> <horizon-provider.jar> <dependency-directory>" >&2
  exit 2
fi
LOADER_JAR="$(realpath "$1")"
PROVIDER_JAR="$(realpath "$2")"
DEPENDENCY_DIR="$(realpath "$3")"
test -d "$DEPENDENCY_DIR"
CLASSPATH="$LOADER_JAR:$PROVIDER_JAR"
for dependency in \
  asm-9.10.1.jar \
  asm-analysis-9.10.1.jar \
  asm-commons-9.10.1.jar \
  asm-tree-9.10.1.jar \
  asm-util-9.10.1.jar \
  'sponge-mixin-0.17.4+mixin.0.8.7.jar'; do
  test -s "$DEPENDENCY_DIR/$dependency" || {
    echo "Missing verified Fabric Loader runtime dependency: $DEPENDENCY_DIR/$dependency" >&2
    exit 1
  }
  CLASSPATH="$CLASSPATH:$DEPENDENCY_DIR/$dependency"
done
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/mods" "$WORK/game"
set +e
java \
  -Dfabric.skipMcProvider=true \
  -Dfabric.noGui=true \
  -Dfabric.development=false \
  -Dfabric.gameVersion=26.3 \
  -Dfabric.modsFolder="$WORK/mods" \
  -Dhorizon.fabric.provider.enabled=true \
  -Dhorizon.fabric.preflight=true \
  -Dhorizon.fabric.auditRoot="$WORK/game" \
  -Dhorizon.fabric.report="$WORK/report.json" \
  -cp "$CLASSPATH" \
  net.fabricmc.loader.impl.launch.knot.KnotClient >"$WORK/loader.log" 2>&1
STATUS=$?
set -e
if [[ "$STATUS" -ne 0 ]]; then
  cat "$WORK/loader.log"
  echo "Official Fabric Loader could not initialize the Horizon metadata-only provider (exit $STATUS)." >&2
  exit "$STATUS"
fi
test -s "$WORK/report.json" || { cat "$WORK/loader.log"; echo "Loader did not write a preflight report." >&2; exit 1; }
python3 - "$WORK/report.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)
assert report.get("ok") is True, report
assert report.get("mode") == "metadata_preflight_only", report
assert report.get("game_version") == "26.3", report
assert any(mod.get("id") == "minecraft" for mod in report.get("mods", [])), report
print("Fabric Loader custom GameProvider smoke test passed: " +
      ", ".join(mod["id"] for mod in report["mods"]))
PY


# Confirm the provider can execute a real, minimal Fabric main entrypoint in its
# explicitly enabled smoke mode. This fixture has no Minecraft/Fabric API dependency.
mkdir -p "$WORK/live-src/dev/example" "$WORK/live-classes" "$WORK/live-mods" "$WORK/live-game"
cat > "$WORK/live-src/fabric.mod.json" <<'JSON'
{
  "schemaVersion": 1,
  "id": "horizon_smoke_mod",
  "version": "1.0.0",
  "name": "Horizon Loader EntryPoint Smoke Test",
  "environment": "*",
  "entrypoints": {
    "main": ["dev.example.SmokeMod"]
  },
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "26.3"
  }
}
JSON
cat > "$WORK/live-src/dev/example/SmokeMod.java" <<'JAVA'
package dev.example;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
public final class SmokeMod implements ModInitializer {
    @Override public void onInitialize() {
        String path = System.getProperty("horizon.fabric.smoke.marker", "");
        if (path.isEmpty()) throw new IllegalStateException("Missing smoke marker path");
        try { Files.writeString(Path.of(path), "entrypoint-initialized"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
JAVA
javac --release 17 -classpath "$LOADER_JAR" -d "$WORK/live-classes" "$WORK/live-src/dev/example/SmokeMod.java"
cp "$WORK/live-src/fabric.mod.json" "$WORK/live-classes/"
jar --create --file "$WORK/live-mods/horizon-smoke.jar" -C "$WORK/live-classes" .
set +e
java \
  -Dfabric.skipMcProvider=true \
  -Dfabric.noGui=true \
  -Dfabric.development=false \
  -Dfabric.gameVersion=26.3 \
  -Dfabric.modsFolder="$WORK/live-mods" \
  -Dhorizon.fabric.provider.enabled=true \
  -Dhorizon.fabric.preflight=true \
  -Dhorizon.fabric.live=true \
  -Dhorizon.fabric.auditRoot="$WORK/live-game" \
  -Dhorizon.fabric.report="$WORK/live-report.json" \
  -Dhorizon.fabric.smoke.marker="$WORK/live-marker.txt" \
  -cp "$CLASSPATH" \
  net.fabricmc.loader.impl.launch.knot.KnotClient >"$WORK/live-loader.log" 2>&1
STATUS=$?
set -e
if [[ "$STATUS" -ne 0 ]]; then
  cat "$WORK/live-loader.log"
  echo "Fabric Loader could not run the minimal main-entrypoint fixture (exit $STATUS)." >&2
  exit "$STATUS"
fi
test -s "$WORK/live-marker.txt" || { cat "$WORK/live-loader.log"; echo "Fabric main entrypoint did not execute." >&2; exit 1; }
python3 - "$WORK/live-report.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)
assert report.get("ok") is True, report
assert report.get("mode") == "entrypoints_smoke", report
assert report.get("entrypoints", {}).get("main") == 1, report
assert any(mod.get("id") == "horizon_smoke_mod" for mod in report.get("mods", [])), report
print("Fabric Loader executed the minimal original-JAR main entrypoint successfully.")
PY
