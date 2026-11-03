# Native Horizon runtime updates

## Current architecture in this branch

The APK keeps `libhorizon_android.so` as a small NativeActivity bootstrap. The Horizon client is built as `libhorizon_engine.so` and copied to `assets/horizon-runtime/` together with a build-generated `manifest.json`. The manifest records the tracked PC release tag (for example, `v0.2.50`) separately from the unique Android runtime build ID. At startup, `HorizonRuntimeManager` copies the bundled engine into app-private version storage, verifies SHA-256 and the ELF64/AArch64 header, and selects a version using an atomic `active.json` record.

A downloaded engine is intended to use the same manifest fields:

```json
{
  "format": 1,
  "version": "horizon-nightly-c<commit8>-o<overlay8>",
  "upstream_tag": "nightly",
  "upstream_commit": "<40-character upstream commit SHA>",
  "overlay": "<8-character Android overlay fingerprint>",
  "abi": "arm64-v8a",
  "library": "horizon-runtime-arm64-v8a.so",
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

The default Horizon runtime applies the Android integration and the checked CrossVersion adapter. CrossVersion is compiled into the client engine; it does not use a separately imported mod or a second JVM. Other experimental server visual/resource compatibility patches remain isolated and are not enabled by this runtime. See `tools/horizon_patches/README.md` for the active and paused patch list.

## What is implemented in this branch

- `HorizonRuntimeUpdateService` checks the project's runtime releases when the app is resumed and periodically while the home screen stays visible; a manual check button is available.
- The release publisher resolves the moving upstream `nightly` pre-release, pins its exact Git commit, compiles the Android ARM64 engine from that source plus the compatibility overlay, and publishes an immutable versioned runtime release. Its active source branch is \`feature/fabric-java-bridge-test\`; a small default-branch scheduler may invoke this reusable workflow, but runtime source and compatibility patches are checked out from the active branch rather than from the older \`main\` application tree.
- The publisher follows the moving upstream `nightly` pre-release, resolves it to an immutable 40-character commit SHA (including annotated-tag dereferencing), and includes that commit plus the Android overlay fingerprint in the unique runtime ID. The fingerprint includes `tools/crossversion/`, so changes to the built-in integration cannot be hidden by an older complete release. Existing semantic/date-based runtime IDs remain valid for installed versions and rollback.
- The updater reads the upstream release at the exact `nightly` tag, requires it to be a published pre-release, and compares it to the newest verified Android runtime built for that same channel. Versioned Android runtime releases remain immutable and are selected by publication timestamp; incomplete entries are skipped. It does not compare incompatible version-string schemes to decide which runtime is newest.
- The updater downloads both the runtime manifest and engine, checks GitHub's published asset digests, confirms that the manifest version/commit/overlay/ABI match the release, checks the local SHA-256 and ELF64/AArch64 header, and only then atomically activates the version for the next game launch.
- The bootstrap reports startup/fallback status. The app keeps the previous engine versions and retains the APK-bundled engine as a fallback.

## Updater-test APK

The automatic runtime publisher builds and releases only the Android ARM64
Horizon engine; it does not assemble an extra APK. On this development branch,
a push-triggered runtime build requires the explicit
`[publish-runtime-approved]` marker; ordinary commits do not publish. The
standard Android build workflow separately skips APK assembly unless an explicit
`[build-apk-approved]` marker is present. Any updater-test fixture remains
isolated to explicit test variants and is ignored by the standard updater, which
accepts published non-prerelease runtime releases only.

## Remaining production requirements

The manifest is **not cryptographically signed yet**. The current trust model is HTTPS plus GitHub's published asset digests and the project's release account; it detects incomplete/corrupt downloads, but it does not protect against a compromised release account. Before treating this as production-hardened, add a project-controlled signature over the manifest and verify it in the app.

The updater targets the Horizon engine only. `tools/horizon-revisions.env` remains a static pin for the Android preparation/fallback packaging path; it does not cap the separate moving-Nightly runtime updater. The `horizon-prepare` executable, JDK runtime metadata, resource data, and compatibility modules are still packaged or managed separately; their versioned updating and dependency compatibility need a subsequent phase. Detailed download progress, automatic cleanup of old versions, and a user-facing auto-update toggle are also outstanding.

Before merging, the build must be authorized and then verify that the CMake bootstrap exports `ANativeActivity_onCreate`, the Rust engine exports the activity and JNI entry points, the APK packages both components, and a device can load the engine from app-private storage on Android 16. This branch has not been compiled or device-tested yet.

## Per-instance game homes

The launcher keeps the original `filesDir/horizon` directory as the fixed `default` instance. It does not move, rename, or copy existing worlds, settings, logs, resource packs, or shader packs. New instance homes are stored under `filesDir/instances/<internal-id>/game`. The internal ID is generated by the app and is validated before resolving a path; the display name is never used as a path component.

The instance catalog is a small versioned JSON file written atomically. The manager bounds its size, validates instance IDs and names, and refuses unsafe symbolic-link paths during deletion. The default and selected instance cannot be deleted. The prepared Minecraft resource tree under `filesDir/.local` and the downloaded native Horizon runtime under `filesDir/horizon-runtime` remain shared to avoid duplicating large files. Mobile performance defaults are applied independently to newly created profiles while the legacy default-profile marker is honored.

## Worlds and local backups

The Worlds screen lists valid `saves/<world-folder>/level.dat` entries in the selected instance. It reads the display name from gzip-compressed NBT `Data.LevelName`, with bounds on decompressed bytes, array/list sizes and nesting depth. If the name cannot be read, the folder name is shown.

Backups are ZIP archives stored under the active instance's private `world-backups` directory, outside Minecraft's `saves` folder. Each archive contains a versioned manifest and a single world root. Restore validates the manifest, every archive path, duplicate paths, entry count and total uncompressed size before replacing the current world. The current world is moved aside until the restored directory is promoted; the UI requires explicit confirmation before replacement. World and backup operations remain scoped to the selected instance.
