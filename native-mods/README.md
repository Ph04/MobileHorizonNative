# Horizon native mods — Android ARM64 build input

Place only the original, reviewed `.hnmod` Rust source packages in this directory.
Ordinary `.hmod` content packages are imported and managed separately by the app.

**Never rename a `.hmod` to `.hnmod`.** The formats are separate and not interchangeable. The native workflow applies
Rust sources to Horizon and compiles a game library. This code runs inside the game
process with the app's permissions; it is not sandboxed.

## Select which mods are compiled

Edit [`enabled-mods.txt`](enabled-mods.txt). It uses the same selection syntax as
the PC launcher's per-instance `horizon-mods.txt`:

```text
mod.mod_menu:true
mod.map_overlay:false
```

Only `true` entries are compiled. A package that is present in `native-mods/` but
marked `false` (or omitted) remains installed on Android and is not included in
the compiled runtime. The IDs must match the `id` in each `horizon-native.json`.
The Android app writes the same `mod.<id>:true` rows when the user activates a
native mod.

Native mods are compiled into the selected Horizon build; they are **not** hot-unloaded
like sandboxed plugins. Turning a mod off removes it from the next build selection.
The exact selected set has its own runtime build. With every native mod disabled,
the app uses the regular Horizon runtime instead.

## Compile and publish

1. Review each archive and its Rust source. Add the exact original archive(s) to
   `native-mods/`.
2. Set only the intended mod IDs to `true` in `enabled-mods.txt`.
3. Commit the package and selection changes to
   `feature/fabric-java-bridge-test` with `[trust-native-mods]` in the commit
   message. This is an explicit acknowledgement that you reviewed and trust the
   Rust code. Alternatively, manually dispatch the workflow and confirm
   `I TRUST THESE NATIVE MODS`.
4. The workflow validates the selected package set and its exact source pins.
   A mod without a `hooks` API is built against the exact Horizon commit in its
   `horizon-native.json`; when every selected mod declares a hooks API, the workflow
   may use the latest official Nightly after checking that API. The matching pinned
   `horizon-mixins` revision is used for the transformation. It then compiles the
   ARM64 Android Horizon engine and publishes a versioned release with the binary
   and SHA-256 manifest. This workflow does not build an APK.

Every release is tied to the exact package bytes, IDs, selected set, upstream Horizon
commit, and `horizon-mixins` revision. A changed source archive or changed selection
requires a matching build.

## Compatibility limits

The Android compiler supports native mixin bundles, Rust source/crates and data. It currently rejects `[api]` (traits), `[dependencies]` (automatic inter-mod dependency resolution), and `[[post_build]]` programs. Those PC launcher features have not yet been ported to this Android workflow.

## Incremental Android compilation

The workflow uses the Horizon workspace's `fast` Cargo profile (optimized, 16 codegen units, incremental compilation) and stores Cargo's target directory, registry, Git checkouts, and the pinned `horizon-mixins` CLI in GitHub Actions cache. Cache keys are scoped to the Android target, Rust toolchain, profile, and Horizon commit—not to the native modset—so a changed `.hnmod` can reuse unchanged dependencies and crates. Cargo fingerprints decide what must actually be rebuilt; changed source or dependencies are not skipped. The final Android engine still has to be linked for each distinct selected modset.

This is incremental **recompilation of the Horizon workspace**, not independent hot-loaded `.so` files for arbitrary mixin mods. Separate per-mod shared libraries would require a stable dynamic plugin ABI and loader that this Horizon integration does not currently provide.


Do not use this workflow for packages whose source you have not reviewed. Rust code executes in-process with the game's permissions and may access app-private data or the network. Enabling a mod is a code execution decision, not just a content toggle.
