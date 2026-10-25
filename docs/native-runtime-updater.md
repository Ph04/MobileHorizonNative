# Native Horizon runtime updates

## Current architecture in this branch

The APK keeps `libhorizon_android.so` as a small NativeActivity bootstrap. The Horizon client is built as `libhorizon_engine.so` and copied to `assets/horizon-runtime/` together with a build-generated `manifest.json`. At startup, `HorizonRuntimeManager` copies the bundled engine into app-private version storage, verifies SHA-256 and the ELF64/AArch64 header, and selects a version using an atomic `active.json` record.

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

## What is not wired up yet

This is the loading and verified-install foundation, not the complete automatic updater. The branch does not yet poll releases on app start / in the background, fetch a signed runtime manifest, show update progress, or publish an Android ARM64 runtime release when upstream Horizon changes. The official Horizon launcher uses release installation for desktop assets; the Android artifact still has to be built by this repository's CI.

Before merging, the build must be allowed to run and must verify that the CMake bootstrap exports `ANativeActivity_onCreate`, the Rust engine exports the activity and JNI entry points, the APK packages both components, and a device can load an engine from app-private storage on Android 16. This branch has not been compiled or device-tested yet.
