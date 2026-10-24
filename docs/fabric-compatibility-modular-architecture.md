# Modular Fabric compatibility layer for Horizon 26.3

Compatibility code belongs to MobileHorizonNative under tools/fabric_java_compat and is installed into the pinned Horizon checkout by an ordered overlay. Do not edit downloaded Horizon sources as the source of truth. Vanilla remains default and must not start Java 25, bridge sockets or adapters.

A universal 100% guarantee for arbitrary Fabric mods is not honest until exact APIs and behavior are implemented and tested. The official Fabric API JAR and ABI catalogs are references, not implementations. A JVM or successful entrypoint does not provide Minecraft Java object identity, callbacks, registries or Mixin targets.

## Native query path

The JSONL worker accepts allow-listed operations and forwards them through a bounded queue. The game thread services at most eight queries per tick. Biome queries use Horizon's existing local native worldgen backend:
- fabric.biome.catalog: prepared biome IDs and climate metadata;
- fabric.biome.sample: up to 256 block positions sampled at quart coordinates;
- fabric.biome.search: bounded nearest-biome lookup.

This is native-only, not a Java Level/Biome/Registry facade. Queries return explicit unavailable/timeout/unsupported errors. Biome tags and modded biome registration are not implied.

## Independent module boundaries

Bridge protocol, entrypoint discovery, event contracts, biome queries, registry transactions, play networking, Mixin mappings, UI/render contracts, resource reload and diagnostics are isolated. Registry, networking, UI and resource modules still validate contracts only; they do not register live Minecraft objects, dispatch packets, draw screens or perform Java ResourceManager reloads. Required unresolved Mixins/access wideners must block activation.

## Nature's Compass gate

Nature's Compass 26.3 requires main/client entrypoints, item/components and creative-tab registration, custom payloads, server lifecycle hooks, biome queries, a search UI/rendering, required Mixins and an access widener. Its manifest stays BLOCKED until these dependencies are wired and a real in-game acceptance test passes.

## Build policy

This change is source-only and is not compiled in this step. Automatic push builds are restricted to main; workflow_dispatch remains available for a future explicitly approved build.


The Java-side NativeBiomeApi is a typed module client for catalog/sample/search responses. Its DTOs are explicitly not Minecraft Biome/BlockPos instances, so future facades can use it without exposing JSON parsing to higher-level adapters.


## Exact JVM member inventory

The CI audit now emits a second JSON report with constant-pool class, field and method references, exact JVM descriptors, Java class-file versions, declared nested JARs, and a comparison against the public Minecraft 26.3 and Fabric API ABI catalogs. A member that is not in the public/protected catalog is marked as not found in that public ABI only; private members may still exist in the official JAR, and access wideners may be relevant. The report is a static inventory and does not execute mod code.


## Fabric API -> Minecraft Java -> Horizon target map

The CI build now generates a version-pinned static target map from the intact official Fabric API 26.3 aggregate JAR, the official Minecraft 26.3 client ABI catalog, the Fabric API public ABI catalog, the module manifest, the shim surface, and the native target-marker registry.

The scanner reads nested Fabric API modules and their class-file constant pools. It inventories references to net.minecraft classes and exact field/method descriptors, plus declared Mixin configurations and access-widener entries. It never executes mod or Fabric API bytecode. HZN-JAVA-* identifiers are stable identifiers for individual mapping records; HZN-FABRIC-* identifiers identify coarse native integration points exposed by the native capability report.

The map intentionally distinguishes three things:
- A Java target being present in the public ABI catalog.
- A possible native subsystem candidate, inferred from the Fabric module and reference source.
- A verified Java-compatible implementation.

Only the first two are automated at this stage. A target that is absent from the public ABI catalog may be private/package-private or affected by mapping differences. A module candidate is not an exact behavioral binding. The JSON artifact is uploaded as fabric-java-to-horizon-target-map so unresolved targets and likely subsystem ownership can be inspected between builds.

The marker registry lives in tools/fabric_java_compat/native-targets.json; the native capability report includes the same IDs. The Rust bridge now includes a coarse target_marker in each allow-listed native-query response and exposes the marker registry in its capability report. This traces the selected bridge handler, not a Java method invocation or a mod's full call chain. New adapters should reference the catalog, add a stable marker for the actual implementation site, and add real behavior tests before a map entry can be considered implemented.

Vanilla remains the default. This map is a build-time diagnostic; it must not cause Java startup, bridge worker startup, network changes, or additional gameplay work when the compatibility profile is disabled.
