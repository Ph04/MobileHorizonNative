#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$1"
if [[ -z "$OUT" ]]; then OUT="$ROOT/build/fabric-java-host.jar"; fi
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes"
find "$ROOT/tools/fabric_java_compat/src" -type f -name '*.java' -print | sort > "$TMP/sources.list"
test -s "$TMP/sources.list"
javac --release 17 -encoding UTF-8 -d "$TMP/classes" @"$TMP/sources.list"
jar --create --file "$OUT" --main-class dev.mobilehorizon.fabric.FabricJavaCompatHost -C "$TMP/classes" .
jar --list --file "$OUT" >/dev/null
test -s "$OUT"
echo "Built standalone Fabric Java host JAR: $OUT"
