#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 2 ]]; then
  echo "usage: $0 <official-fabric-loader.jar> <output-provider.jar>" >&2
  exit 2
fi
LOADER_JAR="$1"
OUTPUT="$2"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE="$ROOT/tools/fabric_java_compat/loader_provider/src/main/java"
RESOURCES="$ROOT/tools/fabric_java_compat/loader_provider/src/main/resources"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
test -s "$LOADER_JAR"
mkdir -p "$WORK/classes" "$(dirname "$OUTPUT")"
mapfile -t SOURCES < <(find "$SOURCE" -name '*.java' -type f | sort)
test "${#SOURCES[@]}" -ge 2
javac --release 17 -encoding UTF-8 -classpath "$LOADER_JAR" -d "$WORK/classes" "${SOURCES[@]}"
cp -R "$RESOURCES/." "$WORK/classes/"
jar --create --file "$OUTPUT" -C "$WORK/classes" .
jar tf "$OUTPUT" | grep -Fq 'dev/mobilehorizon/fabric/loader/HorizonGameProvider.class'
jar tf "$OUTPUT" | grep -Fq 'META-INF/services/net.fabricmc.loader.impl.game.GameProvider'
echo "Built isolated Horizon GameProvider for Fabric Loader: $OUTPUT"
