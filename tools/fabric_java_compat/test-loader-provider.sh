#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 2 ]]; then
  echo "usage: $0 <official-fabric-loader.jar> <horizon-provider.jar>" >&2
  exit 2
fi
LOADER_JAR="$(realpath "$1")"
PROVIDER_JAR="$(realpath "$2")"
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
  -cp "$LOADER_JAR:$PROVIDER_JAR" \
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
