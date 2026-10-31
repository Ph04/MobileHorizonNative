package dev.mobilehorizon.compat.api;

/**
 * Entry point implemented by an optional compatibility module.
 *
 * Implementations run in the separate Java 25 bridge process, not inside Android
 * and not inside Horizon's Rust process. A module is loaded only when the selected
 * game instance names that exact installed module version.
 */
public interface CompatibilityPlugin {
    /** Called once after the bridge validates the module and opens its channel. */
    void onLoad(CompatibilityContext context) throws Exception;

    /** Receives a Horizon event. Payload is a UTF-8 JSON document. */
    default void onEvent(CompatibilityEvent event, CompatibilityContext context)
            throws Exception {
    }

    /** Called once during an orderly game shutdown. */
    default void onUnload() throws Exception {
    }
}
