#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_ROOT="${1:-${RUNNER_TEMP:-/tmp}/mobilehorizon-lwjgl-android}"
RECIPE_REPOSITORY="https://github.com/MDTCopper/android-lwjgl3.git"
RECIPE_COMMIT="7bc318fff71b4fc81d174ad86d796c8eb724dc58"
EXPECTED_LWJGL_COMMIT="b800ccffab14396fc529ddb6c931b7c5c5226763"
EXPECTED_LIBFFI_VERSION="3.5.0"
NDK_REVISION="27.2.12479018"
ANDROID_API_LEVEL="26"
ABI="arm64-v8a"

if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME/ndk/$NDK_REVISION" ]]; then
    echo "Android NDK $NDK_REVISION is required under ANDROID_HOME." >&2
    exit 1
fi
RECIPE="$WORK_ROOT/android-lwjgl3"
BUILD="$WORK_ROOT/build"
mkdir -p "$WORK_ROOT"
if [[ ! -d "$RECIPE/.git" ]]; then
    git init "$RECIPE"
    git -C "$RECIPE" remote add origin "$RECIPE_REPOSITORY"
fi
git -C "$RECIPE" fetch --depth 1 origin "$RECIPE_COMMIT"
git -C "$RECIPE" checkout --detach FETCH_HEAD
test "$(git -C "$RECIPE" rev-parse HEAD)" = "$RECIPE_COMMIT"
grep -Fq "LWJGL_COMMIT=\"$EXPECTED_LWJGL_COMMIT\"" "$RECIPE/scripts/common.sh"
grep -Fq "LIBFFI_VERSION=\"$EXPECTED_LIBFFI_VERSION\"" "$RECIPE/scripts/common.sh"

export NDK_HOME="$ANDROID_HOME/ndk/$NDK_REVISION"
export NDK_VERSION="$NDK_REVISION"
export ANDROID_API="$ANDROID_API_LEVEL"
echo "Building LWJGL $EXPECTED_LWJGL_COMMIT + libffi $EXPECTED_LIBFFI_VERSION for $ABI, API $ANDROID_API_LEVEL."
"$RECIPE/scripts/build.sh" \
    --abi "$ABI" \
    --workdir "$BUILD/work" \
    --outdir "$BUILD/dist" \
    --jobs "${LWJGL_BUILD_JOBS:-2}"

SOURCE_LIBRARY="$BUILD/dist/linux/arm64/org/lwjgl/liblwjgl.so"
test -s "$SOURCE_LIBRARY"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$TOOLCHAIN/llvm-readelf" -h "$SOURCE_LIBRARY" > "$BUILD/lwjgl-elf-header.txt"
grep -Fq "AArch64" "$BUILD/lwjgl-elf-header.txt"
"$TOOLCHAIN/llvm-nm" -D --defined-only "$SOURCE_LIBRARY" > "$BUILD/lwjgl-defined-symbols.txt"
for symbol in Java_org_lwjgl_system_MemoryAccessJNI_getPointerSize Java_org_lwjgl_system_libffi_LibFFI_FFI_1TYPE_1DOUBLE; do
    grep -Fq "$symbol" "$BUILD/lwjgl-defined-symbols.txt" || { echo "Missing full LWJGL JNI symbol: $symbol" >&2; exit 1; }
done
if "$TOOLCHAIN/llvm-nm" -D --undefined-only "$SOURCE_LIBRARY" | grep -E 'ffi_(call|type_double|prep_cif)'; then
    echo "libffi was not statically linked into liblwjgl.so." >&2
    exit 1
fi
"$TOOLCHAIN/llvm-readelf" -d "$SOURCE_LIBRARY" > "$BUILD/lwjgl-dynamic-dependencies.txt"
if grep -E 'libffi\.so|libjvm\.so|libc\+\+_shared\.so|GLIBC_' "$BUILD/lwjgl-dynamic-dependencies.txt"; then
    echo "Unexpected non-platform dependency in Android LWJGL core." >&2
    exit 1
fi
DESTINATION="$ROOT/app/src/main/jniLibs/$ABI"
mkdir -p "$DESTINATION"
install -m 0755 "$SOURCE_LIBRARY" "$DESTINATION/liblwjgl.so"
sha256sum "$DESTINATION/liblwjgl.so"
echo "Complete ARM64 LWJGL core staged at $DESTINATION/liblwjgl.so"
