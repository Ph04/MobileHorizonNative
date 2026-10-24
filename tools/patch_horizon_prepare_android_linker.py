#!/usr/bin/env python3
"""Apply MobileHorizon Android W^X-compatible launcher changes to the pinned Horizon preparer."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_horizon_prepare_android_linker.py <horizon-source-root>")

source_root = Path(sys.argv[1]).resolve()
path = source_root / "crates/horizon-prepare/src/java.rs"
source = path.read_text(encoding="utf-8")

old_javac_major = '''/// The major version `javac -version` reports ("javac 25.0.1" → 25; "javac 1.8.0" → 8).
fn javac_major(javac: &Path) -> Result<u32, String> {
    let output = Command::new(javac)
        .arg("-version")
        .stdin(Stdio::null())
        .output()
        .map_err(|e| format!("{}: {e}", javac.display()))?;
    let text = String::from_utf8_lossy(&output.stdout).into_owned()
        + &String::from_utf8_lossy(&output.stderr);
    let version = text
        .split_whitespace()
        .skip_while(|w| *w != "javac")
        .nth(1)
        .ok_or_else(|| {
            format!(
                "{} -version printed no version: {}",
                javac.display(),
                text.trim()
            )
        })?;
    let mut parts = version.split(['.', '-', '+', '_']);
    let first: u32 = parts.next().and_then(|p| p.parse().ok()).unwrap_or(0);
    Ok(if first == 1 {
        parts.next().and_then(|p| p.parse().ok()).unwrap_or(0)
    } else {
        first
    })
}
'''
new_javac_major = '''// Android 10+ blocks direct execve() of binaries in app-private writable storage
// for apps targeting API 29+. Run Bionic's linker via the system shell so its
// argv is constructed as "linker64 <absolute-ELF> [JVM arguments...]". The shell
// script keeps all arguments quoted and does not interpolate caller-controlled text.
fn java_command(java: &Path) -> Command {
    #[cfg(target_os = "android")]
    {
        let mut command = Command::new("/system/bin/sh");
        command.args([
            "-c",
            "exec /system/bin/linker64 \\"$@\\"",
            "linker64",
        ]);
        // linker64 consumes the ELF path, then the loaded program's argv[0].
        // Duplicate java so -m/-version remain Java arguments.
        command.arg(java);
        command.arg(java);
        command
    }
    #[cfg(not(target_os = "android"))]
    {
        Command::new(java)
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
        Command::new(javac)
    }
}

/// The major version `javac -version` reports ("javac 25.0.1" → 25; "javac 1.8.0" → 8).
fn javac_major(javac: &Path, java: &Path) -> Result<u32, String> {
    let output = javac_command(javac, java)
        .arg("-version")
        .stdin(Stdio::null())
        .output()
        .map_err(|e| format!("{}: {e}", javac.display()))?;
    let text = String::from_utf8_lossy(&output.stdout).into_owned()
        + &String::from_utf8_lossy(&output.stderr);
    let version = text
        .split_whitespace()
        .skip_while(|w| *w != "javac")
        .nth(1)
        .ok_or_else(|| {
            format!(
                "{} -version printed no version: {}",
                javac.display(),
                text.trim()
            )
        })?;
    let mut parts = version.split(['.', '-', '+', '_']);
    let first: u32 = parts.next().and_then(|p| p.parse().ok()).unwrap_or(0);
    Ok(if first == 1 {
        parts.next().and_then(|p| p.parse().ok()).unwrap_or(0)
    } else {
        first
    })
}
'''
if source.count(old_javac_major) != 1:
    raise SystemExit("Pinned java.rs changed: expected one javac_major function; refusing unsafe patch")
source = source.replace(old_javac_major, new_javac_major, 1)

old_candidate = "        match javac_major(&javac) {"
new_candidate = "        match javac_major(&javac, &java) {"
if source.count(old_candidate) != 1:
    raise SystemExit("Pinned java.rs changed: expected one javac_major call; refusing unsafe patch")
source = source.replace(old_candidate, new_candidate, 1)

old_javac = "        let mut command = Command::new(&self.javac);"
new_javac = "        let mut command = javac_command(&self.javac, &self.java);"
if source.count(old_javac) != 1:
    raise SystemExit("Pinned java.rs changed: expected one Jdk::javac command; refusing unsafe patch")
source = source.replace(old_javac, new_javac, 1)

old_java = "        let mut command = Command::new(&self.java);"
new_java = "        let mut command = java_command(&self.java);"
if source.count(old_java) != 1:
    raise SystemExit("Pinned java.rs changed: expected one Jdk::java command; refusing unsafe patch")
source = source.replace(old_java, new_java, 1)

path.write_text(source, encoding="utf-8")
print("Patched Horizon Java launcher: Android uses /system/bin/linker64 for Java and javac.")
