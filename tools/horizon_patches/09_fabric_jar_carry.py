"""Install the direct-JAR command around Horizon's existing content carry pipeline."""
from pathlib import Path

template = repo_root / "tools/horizon_fabric_carry.rs"
target = root / "crates/horizon-port-pack/src/bin/horizon-fabric-carry.rs"
if not template.is_file():
    raise SystemExit(f"Missing checked-in Fabric carry source: {template}")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(template.read_text(encoding="utf-8"), encoding="utf-8")
print("Installed direct Fabric JAR content-carry command.")
