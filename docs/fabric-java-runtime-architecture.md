# Fabric original-runtime compatibility for Horizon 26.3

## Direction

The main compatibility path is changing from extracting assets and porting Java behavior manually to a hybrid original-JAR runtime:

- Preserve the original Fabric JAR and dependency metadata.
- Run original Java entrypoints in a separate OpenJDK 25 process instead of attempting arbitrary Java-to-Rust/WASM translation.
- Implement the Fabric Loader, Fabric API and Minecraft-facing Java contracts that supported mods expect.
- Bridge supported game operations to Horizon's Rust engine through a versioned IPC contract.
- Keep content carry-over for assets/data as a complementary layer, not as proof that the mod works.
- Keep WASM P1 available for native ports, but do not require each original Fabric mod to be rewritten into WASM.

A JVM alone does not make a mod compatible: Horizon does not contain Minecraft's Java client classes or Fabric's Mixin transformation environment. Those contracts need real shims/adapters.

## Boundaries

1. Import and inspect only: validate the JAR, hash it, preserve it, inspect fabric.mod.json, bytecode versions, entrypoints, dependencies, nested JARs, access wideners and Mixins. Import never executes code.
2. Java host: separate process using the already provisioned OpenJDK 25 runtime. stdout is JSONL protocol only; process output is captured as diagnostics. A mod failure must not terminate the native game.
3. Java compatibility surface: start with Loader entrypoints, then add matching Fabric API classes and the Minecraft signatures used by tested mods. Missing symbols must fail with explicit diagnostics.
4. Native bridge: versioned requests/events only. Rust validates each operation and maps it to real Horizon hooks/state; no Rust pointers are exposed to Java.
5. Opt-in isolation: host startup and hooks are disabled unless the user selects Fabric Bridge. Vanilla must keep its current mod paths, protocol, network handshake and startup path.

## Entry points, Mixins and limitations

The first host slice accepts default preLaunch, main and client entrypoints. Mods declaring Mixins, access wideners, nested JARs, unsupported adapters, or bytecode newer than the active JVM are blocked until supported. No false success states.

Mixins cannot patch arbitrary Rust functions. Supported injection patterns must be translated to stable Horizon hook IDs or operate on a Java-owned compatibility surface; unsupported targets remain blockers. Fabric API symbols must match Minecraft 26.3 / Fabric API 0.165.0 semantics, not merely names.

OpenJDK 25 is already part of resource preparation, but the host is not yet connected to NativeGameActivity or Horizon's game loop. Running another JVM costs RAM and is a serious constraint on 4 GB devices. A separate process is not a sandbox; Java code can access files available to the app UID. Do not auto-execute imported JARs.

## Milestones

- J0 — standalone host/bootstrap and JSONL diagnostics. No game APIs are connected yet.
- J1 — opt-in lifecycle startup, dependency resolution, classloader isolation, shutdown and bounded supervision.
- J2 — Fabric API base/event bus and Rust callback bridge, including cancellation where Horizon has a matching hook.
- J3 — read-only world/biome access, with Nature's Compass as the first real behavior test.
- J4 — registries, registration timing and supported content mutations.
- J5 — Mixin/render/resource-reload adapter patterns.

"JAR inspected", "entrypoint invoked", and "works in-game" are different states. Each milestone needs host tests and an Android build.

## Current status

The repository has a Fabric auditor, content-only JAR carry-over, an isolated WASM P1 profile and a pinned OpenJDK 25 installer. The standalone host introduced alongside this document is J0 groundwork: it implements only base entrypoint interfaces in a fixture harness. It does not implement the full Fabric API, provide Minecraft classes, apply Mixins or dispatch events to Horizon. It is not yet launched by the game.

## Full Fabric API artifact (bundled intact)

The Android workflow downloads the complete official Maven artifact `net.fabricmc.fabric-api:fabric-api:0.165.0+26.3` as one JAR, verifies the upstream SHA-256 sidecar, checks `fabric.mod.json` plus every declared nested module JAR, and writes a local lock manifest. The APK contains the original aggregate artifact without exploding or hand-selecting its modules.

When Fabric Bridge is requested, `FabricApiRuntimeInstaller` verifies the bundled hash and metadata again, then stages that same single JAR in `filesDir/horizon/fabric-bridge-runtime/mods/`. User-imported original Fabric JARs are mirrored into that isolated Java-mods directory; removal from the Resource Manager removes the matching staged copy. The directory is exposed to the future host via `HORIZON_FABRIC_JAVA_MODS`.

This is the correct dependency packaging shape, but it does **not** yet mean that Fabric API works in-game. Its nested modules still need Loader-style discovery, and their calls to Minecraft/Fabric classes need the Java compatibility surface and Rust hooks. The present host does not execute this bundle; startup falls back to Vanilla if the packaged aggregate cannot be verified. No Fabric Java host or mod bytecode is run by this staging step.

\n## Official Fabric Loader artifact

The Android workflow pins and verifies the official Fabric Loader 0.19.5 Maven JAR for Minecraft 26.3, validates KnotClient, GameProvider and FabricLoader API classes, and writes a SHA-256 lock. It also builds a small HorizonGameProvider JAR against the official Loader, registers it through `META-INF/services/net.fabricmc.loader.impl.game.GameProvider`, and smoke-tests an empty metadata-only profile in CI. When Fabric Bridge is explicitly selected, Android verifies and stages both artifacts under `filesDir/horizon/fabric-bridge-runtime/loader/`, exposing paths through `HORIZON_FABRIC_LOADER_JAR` and `HORIZON_FABRIC_PROVIDER_JAR`. Neither artifact is placed in `mods/`.

The provider currently supports isolated metadata preflight only. The Android game does not launch Knot, and the provider must not be pointed at original mod JARs: Knot invokes preLaunch entrypoints before the provider launch callback. The next integration must create a metadata-only staging set, resolve nested module metadata and dependencies, then build game-facing Java APIs and Rust callbacks before attempting mod entrypoints. This is not yet in-game mod execution. Vanilla clears all Fabric environment variables and never stages or starts the Loader.
