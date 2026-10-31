package dev.mobilehorizon.compat.api;

/** Services exposed to a compatibility module by the bridge. */
public interface CompatibilityContext {
    String moduleId();
    String moduleVersion();
    String instanceId();
    String minecraftVersion();
    String loaderId();
    String loaderVersion();

    /** Writes a bounded diagnostic message to the MobileHorizon/Horizon log. */
    void log(String message);

    /**
     * Sends a typed JSON action to the Rust bridge.
     *
     * The Rust side validates and allow-lists action types. Modules must not assume
     * that an action is implemented merely because it can be submitted.
     */
    void emitAction(String type, String payloadJson);
}
