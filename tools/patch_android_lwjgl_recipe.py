#!/usr/bin/env python3
"""Retarget the pinned Android LWJGL recipe to Minecraft 26.3's LWJGL 3.4.3."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_android_lwjgl_recipe.py <android-lwjgl3-repository>")

root = Path(sys.argv[1]).resolve()
common_path = root / "scripts" / "common.sh"
if not common_path.is_file():
    raise SystemExit(f"Android LWJGL recipe not found: {common_path}")

text = common_path.read_text(encoding="utf-8")

def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact pin, found {count}")
    return source.replace(old, new, 1)

old_version = 'LWJGL_VERSION="' + "$" + '{LWJGL_VERSION:-3.4.1}"'
new_version = 'LWJGL_VERSION="' + "$" + '{LWJGL_VERSION:-3.4.3}"'
constants = (
    (old_version, new_version, "LWJGL version"),
    ('LWJGL_COMMIT="b800ccffab14396fc529ddb6c931b7c5c5226763"',
     'LWJGL_COMMIT="30fac9b95f99cda97312232be25ba55297bf9951"', "LWJGL commit"),
    ('LIBFFI_VERSION="3.5.0"', 'LIBFFI_VERSION="3.8.0"', "libffi version"),
    ('LIBFFI_COMMIT="d2c78d2ebbd9e65401095c6a2f281fe5132f028b"',
     'LIBFFI_COMMIT="12ffd1f9dc56fcea79d2f742f424301ae668d663"', "libffi commit"),
    ('LIBFFI_TARBALL_SHA256="8C72678628A5DD8782F08AD421D5A441E42C1C5C1B33E0BC211CBFCF1F3B3978"',
     'LIBFFI_TARBALL_SHA256="7DA3E2D9A171EB0A038F592ECAD3FF2BB2550F3496D87B3B29AD0CF4430C0DB4"', "libffi tarball SHA-256"),
)
for old, new, label in constants:
    text = replace_once(text, old, new, label)

pins = {
    "90665167605D92AD71A8CDE7AAD180AA38855BB5536F97E38FCC1D3CE2B2CBD8":
        "408873571618380A640FC9295C722413F608B688B3328C348B01FB5C50AB4303",
    "635E0F0C5898689F586A7C325117CCB6EAEAAB3F791F0761F6E8D045743A2AAC":
        "F449CFE0AB3C17C36EA446AC225AA906330B74D0E73FD8E34C1C6BFF0A80F312",
    "8756860E8AB4D8C6942E12496B8081CFD291442CDBC6D13DBA4B993905128872":
        "F23204EFEE2BFAFFB72A974D2E28C64C3C1D9787144B5C5890F129C465E467BD",
    "578295A8348F838EC7B6CEC6304FAFF6AC675FEED55107825EED8F6B5C5610B2":
        "578295A8348F838EC7B6CEC6304FAFF6AC675FEED55107825EED8F6B5C5610B2",
    "005BD3FD1E69E5119341C0E61D8AF5074DA86D239285F5582C15A09D910F223C":
        "005BD3FD1E69E5119341C0E61D8AF5074DA86D239285F5582C15A09D910F223C",
}
for old, new in pins.items():
    text = replace_once(text, old, new, "LWJGL source SHA-256")

if new_version not in text or 'LIBFFI_VERSION="3.8.0"' not in text:
    raise SystemExit("version pin verification failed")

# Pin every implementation source in LWJGL 3.4.3's split miniz library.
old_source_paths = """modules/lwjgl/core/src/main/c/dependencies/miniz/miniz.c
modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_UNISTD.c"""
new_source_paths = """modules/lwjgl/core/src/main/c/dependencies/miniz/miniz.c
modules/lwjgl/core/src/main/c/dependencies/miniz/miniz_tdef.c
modules/lwjgl/core/src/main/c/dependencies/miniz/miniz_tinfl.c
modules/lwjgl/core/src/main/c/dependencies/miniz/miniz_zip.c
modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_UNISTD.c"""
text = replace_once(text, old_source_paths, new_source_paths, "miniz source pin paths")
old_miniz_pin_cases = """        modules/lwjgl/core/src/main/c/dependencies/miniz/miniz.c)
            echo "F23204EFEE2BFAFFB72A974D2E28C64C3C1D9787144B5C5890F129C465E467BD" ;;
        modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_UNISTD.c)"""
new_miniz_pin_cases = """        modules/lwjgl/core/src/main/c/dependencies/miniz/miniz.c)
            echo "F23204EFEE2BFAFFB72A974D2E28C64C3C1D9787144B5C5890F129C465E467BD" ;;
        modules/lwjgl/core/src/main/c/dependencies/miniz/miniz_tdef.c)
            echo "85B93205232D51D092F24041934D951F11F9EDC02DF1F475E8CEB7B281A1E7F6" ;;
        modules/lwjgl/core/src/main/c/dependencies/miniz/miniz_tinfl.c)
            echo "2296EBD21EF9AF5EBBEFD5B454D4BB67D8FB4AF2D24945E7FB32114374F75A76" ;;
        modules/lwjgl/core/src/main/c/dependencies/miniz/miniz_zip.c)
            echo "3C1F90797C02DDC9D6BFCDF7079F5EA6F6212F257ED8745F2334DD021D697669" ;;
        modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_UNISTD.c)"""
text = replace_once(text, old_miniz_pin_cases, new_miniz_pin_cases, "miniz source hashes")

common_path.write_text(text, encoding="utf-8")

# The Android recipe previously compiled only miniz.c, leaving the new tdef/tinfl
# implementation in separate files out of the link. Compile all four miniz C units.
build_path = root / "scripts" / "build.sh"
if not build_path.is_file():
    raise SystemExit(f"Android LWJGL build script not found: {build_path}")
build_text = build_path.read_text(encoding="utf-8")
old_miniz_sources = "    # miniz is compiled into liblwjgl.so (the upstream Ant build leaves it out).\n    echo \"$CORE_SRC_ROOT/main/c/dependencies/miniz/miniz.c\""
new_miniz_sources = "    # LWJGL 3.4.3 splits miniz into core, deflate, inflate and ZIP units.\n    find \"$CORE_SRC_ROOT/main/c/dependencies/miniz\" -maxdepth 1 -name '*.c' -print"
build_text = replace_once(build_text, old_miniz_sources, new_miniz_sources, "miniz source list")
build_text = replace_once(build_text, '[[ "${#CORE_SRCS[@]}" -eq 24 ]]', '[[ "${#CORE_SRCS[@]}" -eq 27 ]]', "core source count guard")
build_text = replace_once(build_text, "expected 24 core translation units", "expected 27 core translation units", "core source count message")
build_path.write_text(build_text, encoding="utf-8")
print("Pinned Android recipe to LWJGL 3.4.3 + libffi 3.8.0; compiled all miniz units.")
