#!/usr/bin/env python3
"""Apply MobileHorizon Android linker invocation without losing newer JDK discovery fixes."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_horizon_prepare_android_linker.py <horizon-source-root>")

source_root = Path(sys.argv[1]).resolve()
path = source_root / "crates/horizon-prepare/src/java.rs"
source = path.read_text(encoding="utf-8")


def replace_one_compatible(text, old, new, label):
    """Replace one known old form, accept the already-patched form, and reject drift."""
    old_count = text.count(old)
    new_count = text.count(new)
    if old_count == 1 and new_count == 0:
        return text.replace(old, new, 1)
    if old_count == 0 and new_count == 1:
        return text
    raise SystemExit(
        f"Refusing unsafe Android Java-launcher patch for {label}: "
        f"old={old_count}, patched={new_count}"
    )


# Android 10+ blocks direct execve() of some binaries extracted to app-private
# writable storage for apps targeting API 29+. Invoking Bionic's linker from the
# system shell preserves argument boundaries while leaving desktop behavior alone.
helpers = r'''// Android's linker must load the ELF from app-private writable storage.
fn java_command(java: &Path) -> Command {
    #[cfg(target_os = "android")]
    {
        let mut command = Command::new("/system/bin/sh");
        command.args([
            "-c",
            "exec /system/bin/linker64 \"$@\"",
            "linker64",
        ]);
        // linker64 consumes the ELF path followed by the loaded program's argv[0].
        command.arg(java);
        command.arg(java);
        command
    }
    #[cfg(not(target_os = "android"))]
    {
        command(java)
    }
}

fn javac_command(javac: &Path, java: &Path) -> Command {
    #[cfg(target_os = "android")]
    {
        let mut command = java_command(java);
        command.args(["-m", "jdk.compiler/com.sun.tools.javac.Main"]);
        command
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = java;
        command(javac)
    }
}

'''
marker = '/// The major version `javac -version` reports'
if "fn java_command(java: &Path) -> Command {" not in source:
    if source.count(marker) != 1:
        raise SystemExit("Pinned java.rs changed: expected one javac version marker")
    source = source.replace(marker, helpers + marker, 1)

source = replace_one_compatible(
    source,
    "fn javac_major(javac: &Path) -> Result<u32, String> {",
    "fn javac_major(javac: &Path, java: &Path) -> Result<u32, String> {",
    "javac_major signature",
)
source = replace_one_compatible(
    source,
    "    let output = command(javac)\n        .arg(\"-version\")",
    "    let output = javac_command(javac, java)\n        .arg(\"-version\")",
    "javac version invocation",
)
source = replace_one_compatible(
    source,
    "        match javac_major(&javac) {",
    "        match javac_major(&javac, &java) {",
    "javac_major call",
)
source = replace_one_compatible(
    source,
    "        let mut command = command(&self.javac);",
    "        let mut command = javac_command(&self.javac, &self.java);",
    "Jdk::javac invocation",
)
source = replace_one_compatible(
    source,
    "        let mut command = command(&self.java);",
    "        let mut command = java_command(&self.java);",
    "Jdk::java invocation",
)

path.write_text(source, encoding="utf-8")
print("Patched Horizon Java launcher for Android while preserving upstream JDK discovery logic.")
