# Standalone Java compatibility host (J0)

This is the first executable slice of the original-JAR compatibility path. It is intended to run under the app's OpenJDK 25 runtime after launcher integration is implemented.

Run the test harness:

    bash tools/fabric_java_compat/test-host.sh

Inspect a JAR without executing it:

    java -cp fabric-java-host.jar dev.mobilehorizon.fabric.FabricJavaCompatHost --probe mod.jar

Run supported base entrypoints only in a developer/test environment:

    HORIZON_FABRIC_JAVA_EXECUTION=1 java -cp fabric-java-host.jar dev.mobilehorizon.fabric.FabricJavaCompatHost --run mod.jar [dependency.jar ...]

Execution requires the explicit environment opt-in and is not connected to import. This tool does not sandbox Java code; use trusted fixtures only until the Android permission model and native-game bridge exist.

The host hashes and scans the original JAR, reports class-file versions and metadata, and uses JSONL on stdout. It accepts only the default preLaunch, main and client entrypoint adapters. Mixins, access wideners, nested JARs and unsupported Java versions fail closed. Missing Fabric API/Minecraft symbols are reported as errors; this is not complete Fabric compatibility.

The Android integration must still supervise the process, connect it to the Rust runtime, and keep it disabled in Vanilla.
