# Standalone Java compatibility host (J0)

This is the first executable slice of the original-JAR compatibility path. CI compiles the source into app/src/main/assets/fabric-java-host.jar. It is packaged for the later launcher integration but is NOT launched by the game in this milestone.

Build the standalone JAR:

    bash tools/fabric_java_compat/build-host.sh /tmp/fabric-java-host.jar

Run the fixture tests:

    bash tools/fabric_java_compat/test-host.sh

Inspect a JAR without executing it:

    java -jar fabric-java-host.jar --probe mod.jar

Run supported base entrypoints only in a developer/test environment:

    HORIZON_FABRIC_JAVA_EXECUTION=1 java -jar fabric-java-host.jar --run mod.jar [dependency.jar ...]

Execution requires the explicit environment opt-in and is not connected to import. This tool does not sandbox Java code; use trusted fixtures only until the Android permission model and native-game bridge exist.

The host hashes and scans the original JAR, reports class-file versions and metadata, and uses JSONL on stdout. It accepts only the default preLaunch, main and client entrypoint adapters. Mixins, access wideners, nested JARs and unsupported Java versions fail closed. Missing Fabric API/Minecraft symbols are reported as errors; this is not complete Fabric compatibility.

The Android integration must still supervise the process, connect it to the Rust runtime, and keep it disabled in Vanilla.


## Static target map

CI runs build_fabric_target_map.py after verifying the official Fabric API and Minecraft 26.3 client ABI catalogs. It scans nested Fabric API implementation classes, Minecraft class/member references, Mixin configuration names, and access-widener entries, then proposes likely native-module owners from native-targets.json.

The generated JSON is uploaded as the fabric-java-to-horizon-target-map artifact. HZN-JAVA-* IDs identify individual references; HZN-FABRIC-* IDs identify source-level native integration points returned by the capability report. The Rust bridge includes a coarse target_marker in allow-listed native-query responses, identifying the handler selected for that query. The marker does not track arbitrary Java method calls, prove a binding is implemented, or show that a mod works in-game. A static scan is never proof that a mod executes successfully in-game.
