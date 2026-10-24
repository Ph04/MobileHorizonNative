#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 1 ]]; then
  echo "usage: $0 <output-dependencies-directory>" >&2
  exit 2
fi
DEST="$1"
mkdir -p "$DEST"
ASM_VERSION="9.10.1"
MIXIN_VERSION="0.17.4+mixin.0.8.7"
MIXIN_VERSION_URL="0.17.4%2Bmixin.0.8.7"
BASE="https://maven.fabricmc.net"
download_verified() {
  local url="$1"
  local filename="$2"
  local digest_file expected
  digest_file="$(mktemp)"
  curl --fail --location --retry 3 --retry-all-errors "$url" --output "$DEST/$filename"
  curl --fail --location --retry 3 --retry-all-errors "$url.sha256" --output "$digest_file"
  expected="$(tr -d '[:space:]' < "$digest_file" | tr 'A-F' 'a-f')"
  rm -f "$digest_file"
  if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
    echo "::error::Official Maven SHA-256 sidecar has an unexpected format for $filename." >&2
    exit 1
  fi
  printf '%s  %s\n' "$expected" "$DEST/$filename" | sha256sum --check -
}
for module in asm asm-analysis asm-commons asm-tree asm-util; do
  filename="$module-$ASM_VERSION.jar"
  url="$BASE/org/ow2/asm/$module/$ASM_VERSION/$filename"
  download_verified "$url" "$filename"
done
MIXIN_FILE="sponge-mixin-$MIXIN_VERSION.jar"
MIXIN_URL="$BASE/net/fabricmc/sponge-mixin/$MIXIN_VERSION_URL/$MIXIN_FILE"
download_verified "$MIXIN_URL" "$MIXIN_FILE"
echo "Downloaded and checksum-verified Fabric Loader 0.19.5 runtime dependencies (ASM $ASM_VERSION and Sponge Mixin $MIXIN_VERSION)."
