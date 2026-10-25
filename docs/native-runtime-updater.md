# Native Horizon runtime updates

## Current architecture in this branch

The APK keeps `libhorizon_android.so` as a small NativeActivity bootstrap. The Horizon client is built as `libhorizon_engine.so` and copied to `assets/horizon-runtime/` together with a build-generated `manifest.json`. The manifest records the tracked PC release tag (for example, `v0.2.50`) separately from the unique Android runtime build ID. At startup, `HorizonRuntimeManager` copies the bundled engine into app-private version storage, verifies SHA-256 and the ELF64/AArch64 header, and selects a version using an atomic `active.json` record.

A downloaded engine is intended to use the same manifest fields:

```json
{
  "format": 1,
  "version": "horizon-<unique-build-id>",
  "commit": "<upstream-commit-sha>",
  "library": "libhorizon_engine.so",
  "sha256": "<64 lowercase hexadecimal characters>"
}
```

A release publisher must produce the manifest and binary from the same build. The app must obtain the expected digest from a trusted update response; a digest delivered alongside an untrusted binary is not an authenticity guarantee. For production releases, sign the runtime manifest/checksum with a project-controlled key and verify that signature in the app before passing the digest to `installAndActivate`.

## Update and recovery behavior

- Each runtime version has its own directory under `filesDir/horizon-runtime/versions/`.
- The file is verified before activation and made read-only before it can be loaded, for Android's dynamic-code-loading restrictions.
- The bootstrap only accepts engine paths inside that private version directory and only accepts the expected `libhorizon_engine.so` filename.
- The active library is never overwritten in place. `active.json` is switched atomically so the selected engine changes on the next game launch.
- The APK-provided engine is always retained as the fallback. If loading a selected engine fails and the baseline loads instead, the next launch resets selection to the bundled baseline.
- Previous version directories are retained so a future updater/UI can offer rollback and cleanup.

## Optional compatibility scope

The default native engine now applies only the Android integration patch and a small offline/hybrid login patch. The experimental server visual/resource compatibility patches remain in the repository but are not compiled into this default engine. This avoids forcing unfinished server-side rendering/protocol changes on normal local startup. See `tools/horizon_patches/README.md` for the active and paused patch list.

## What is implemented in this branch

- `HorizonRuntimeUpdateService` checks the project's runtime releases when the app is resumed and periodically while the home screen stays visible; a manual check button is available.
- The release publisher workflow polls stable Horizon releases hourly, compiles the Android ARM64 engine from the corresponding upstream source plus the compatibility overlay, and publishes a versioned runtime release.
- The updater downloads both the runtime manifest and engine, checks GitHub's published asset digests, confirms that the manifest version/commit/overlay/ABI match the release, checks the local SHA-256 and ELF64/AArch64 header, and only then atomically activates the version for the next game launch.
- The bootstrap reports startup/fallback status. The app keeps the previous engine versions and retains the APK-bundled engine as a fallback.

## Updater-test APK

The `updaterTestDebug` flavor is a separate package and is uploaded as `mobilehorizon-native-horizon-updater-test`. On its first launch it downloads the real Android engine built from PC release `v0.2.49`, verifies it, and selects it as the active runtime. A manual update check (or a later automatic check) then downloads the engine built from `v0.2.50` from a dedicated prerelease fixture and activates it for the next game launch. The fixture release is marked prerelease and is intentionally ignored by the standard updater.

The normal `standardDebug` APK never runs the forced-old-version seed logic. The updater-test APK is specifically for validating the remote-download/hash/install/activation path; do not use its prerelease endpoint as a production update channel.

## Remaining production requirements

The manifest is **not cryptographically signed yet**. The current trust model is HTTPS plus GitHub's published asset digests and the project's release account; it detects incomplete/corrupt downloads, but it does not protect against a compromised release account. Before treating this as production-hardened, add a project-controlled signature over the manifest and verify it in the app.

The current updater targets the Horizon engine only. The `horizon-prepare` executable, JDK runtime metadata, resource data, and compatibility modules are still packaged or managed separately; their versioned updating and dependency compatibility need a subsequent phase. Detailed download progress, automatic cleanup of old versions, and a user-facing auto-update toggle are also outstanding.

Before merging, the build must be authorized and then verify that the CMake bootstrap exports `ANativeActivity_onCreate`, the Rust engine exports the activity and JNI entry points, the APK packages both components, and a device can load the engine from app-private storage on Android 16. This branch has not been compiled or device-tested yet.
