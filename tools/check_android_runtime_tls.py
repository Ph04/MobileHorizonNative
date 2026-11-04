#!/usr/bin/env python3
"""Reject Android ARM64 DSOs that require ELF TLS unsupported by minSdk 26.

The Horizon engine is loaded after process startup with dlopen(). For the
launcher minSdk (Android 8.0/API 26), use emulated TLS rather than ELF TLS.
This audit reads only dynamic relocations and program headers, so static
relocation records that the linker already resolved do not cause false alarms.
"""
from __future__ import annotations
import re
import subprocess
import sys
from pathlib import Path

TLS_RELOCATION = re.compile(r"\\bR_AARCH64_(?:TLS_[A-Z0-9_]+|TLSDESC[A-Z0-9_]*)\\b")
TLS_PROGRAM_HEADER = re.compile(r"^\\s*TLS\\s+", re.MULTILINE)

def find_tls_requirements(dynamic_relocations: str, program_headers: str) -> list[str]:
    findings = sorted(set(TLS_RELOCATION.findall(dynamic_relocations)))
    if TLS_PROGRAM_HEADER.search(program_headers):
        findings.append("PT_TLS program header (ELF TLS segment)")
    return findings

def audit(readelf: str, library: Path) -> list[str]:
    dynamic_relocations = subprocess.run(
        [readelf, "--use-dynamic", "--relocations", "--wide", str(library)],
        check=True, capture_output=True, text=True,
    ).stdout
    program_headers = subprocess.run(
        [readelf, "--program-headers", "--wide", str(library)],
        check=True, capture_output=True, text=True,
    ).stdout
    return find_tls_requirements(dynamic_relocations, program_headers)

def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(f"Usage: {Path(argv[0]).name} <llvm-readelf> <arm64-library.so>", file=sys.stderr)
        return 2
    readelf, library = argv[1], Path(argv[2])
    if not library.is_file():
        print(f"TLS audit: shared library not found: {library}", file=sys.stderr)
        return 2
    try:
        findings = audit(readelf, library)
    except subprocess.CalledProcessError as error:
        detail = error.stderr.strip() if error.stderr else str(error)
        print(f"TLS audit: llvm-readelf failed: {detail}", file=sys.stderr)
        return 2
    if findings:
        print(
            "::error::Android runtime contains ELF TLS requirements incompatible with "
            "the API-26 emulated-TLS baseline and/or dlopen loading:",
            file=sys.stderr,
        )
        for item in findings:
            print(f"  - {item}", file=sys.stderr)
        return 1
    print(
        f"TLS audit passed: {library.name} has no dynamic AArch64 TLS relocations "
        "and no PT_TLS segment (compatible with minSdk 26 and late dlopen)."
    )
    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
