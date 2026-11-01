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
        "775B6609E6FC15A98B6A3A2BBADC31186B35A7C01F0F1192583676D548121E88",
    "005BD3FD1E69E5119341C0E61D8AF5074DA86D239285F5582C15A09D910F223C":
        "B0DC0B30F9B3E6DEC093041ED2BFD698A57243889220A3AF630E1862B86B4234",
}
for old, new in pins.items():
    text = replace_once(text, old, new, "LWJGL source SHA-256")

if new_version not in text or 'LIBFFI_VERSION="3.8.0"' not in text:
    raise SystemExit("version pin verification failed")

common_path.write_text(text, encoding="utf-8")
print("Pinned Android recipe to LWJGL 3.4.3 + libffi 3.8.0.")
