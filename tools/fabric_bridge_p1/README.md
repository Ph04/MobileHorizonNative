# Horizon Fabric Bridge P1 kernel

This is the P1 adapter kernel supplied for MobileHorizonNative: deterministic typed events, keybindings, typed command parsing, declarative HUD state, per-mod configuration, and callback quotas.

The patch overlay copies the Rust modules into the pinned Horizon `horizon-mods` crate and keeps the WIT contract in a separate WIT directory so it cannot break the existing `horizon:mods` package.

**Important runtime boundary:** compiling the kernel does not by itself make new WIT imports available to a `.hmod`. The current Horizon runtime still binds the existing `horizon:mods` world. A subsequent runtime binding must register the P1 host interfaces, validate each capability in the manifest, and dispatch the exported guest callbacks before P1 mods may be declared loadable. This scaffold must not be presented as full Fabric Loader or Java bytecode compatibility.

Run standalone unit tests with:
`cargo test --manifest-path tools/fabric_bridge_p1/Cargo.toml`
