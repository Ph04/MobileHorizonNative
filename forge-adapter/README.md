# Horizon Forge 26.3 adapter

This is the first independent Forge adapter package for MobileHorizonNative's compatibility plugin API.

## Target

- Minecraft: 26.3
- Forge: 66.1.2
- Compatibility API: 1
- Adapter package version: 0.1.0

The Forge download page currently lists 66.1.2 as the latest 26.3 build and 66.1.0 as the recommended build. This adapter pins 66.1.2 so the target is explicit and reproducible.

## Build the module package

From the repository root:

    gradle :compatibility-api:jar :forge-adapter:packageMhplugin

The package is written to:

    forge-adapter/build/distributions/forge-compatibility-26.3-0.1.0.mhplugin

Import that file from the launcher's Compatibility Modules screen. The package is a separate module; it is not embedded in an APK.

## Current implementation boundary

Version 0.1.0 is a dependency probe and API contract test, not a working Forge runtime. It checks for ModLauncher, BootstrapLauncher, FML and the Java Minecraft client entry classes in the module class loader. It reports which pieces are visible and receives Horizon tick/frame/world lifecycle observations.

It does not invoke ModLauncher, patch Java Minecraft classes, create Forge registries, render Java-side entities/blocks, or claim that arbitrary Forge JARs can run. Horizon's native client is Rust and does not provide the Java Minecraft class universe expected by Forge mods. The missing client/API mapping is the central research and implementation task for the next Forge milestone.

Do not label this package as gameplay-ready. It exists to verify the module packaging, isolated Java process, API handshake, and the initial dependency-discovery stage.

## Next engineering milestones

1. Identify the minimal Forge/ModLauncher launch target and required libraries for 26.3.
2. Define the Java compatibility facade for the Minecraft classes most commonly referenced by Forge.
3. Map registry, resource, lifecycle, input, world, entity and chat services to Horizon's native hooks.
4. Implement Forge event bus dispatch and safe request/response semantics.
5. Validate with progressively more demanding test mods; report unsupported classes and methods explicitly.
