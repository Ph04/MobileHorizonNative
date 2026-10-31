package dev.mobilehorizon.forge;

import dev.mobilehorizon.compat.api.CompatibilityContext;
import dev.mobilehorizon.compat.api.CompatibilityEvent;
import dev.mobilehorizon.compat.api.CompatibilityPlugin;

import java.util.concurrent.atomic.AtomicLong;

/**
 * First Forge adapter milestone: validates the Java-side ModLauncher/FML/client
 * class surface and reports whether a full Forge bootstrap can even be attempted.
 *
 * This is deliberately a probe, not a fake Forge runtime. Horizon does not ship
 * Minecraft's Java client classes, so this adapter does not claim to load .jar mods.
 */
public final class ForgeCompatibilityPlugin implements CompatibilityPlugin {
    private static final String TARGET_MINECRAFT = "26.3";
    private static final String TARGET_FORGE = "66.1.2";
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong frames = new AtomicLong();

    @Override
    public void onLoad(CompatibilityContext context) {
        context.log("Horizon Forge adapter probe 0.1.0 starting.");
        context.log("Target: Minecraft " + TARGET_MINECRAFT + " / Forge " + TARGET_FORGE + ".");
        context.log("This adapter currently validates dependencies only; it does not start Forge or load Java mods.");

        if (!TARGET_MINECRAFT.equals(context.minecraftVersion())
                || !"forge".equals(context.loaderId())
                || !TARGET_FORGE.equals(context.loaderVersion())) {
            context.log("Profile mismatch: this adapter package is pinned to Minecraft "
                    + TARGET_MINECRAFT + " / Forge " + TARGET_FORGE + ".");
            return;
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        boolean modLauncher = classAvailable("cpw.mods.modlauncher.Launcher", loader);
        boolean bootstrapLauncher = classAvailable("cpw.mods.bootstraplauncher.BootstrapLauncher", loader);
        boolean forgeLoader = classAvailable("net.minecraftforge.fml.ModLoader", loader);
        boolean minecraftClient = classAvailable("net.minecraft.client.Minecraft", loader);

        context.log("Forge dependency probe: ModLauncher=" + modLauncher
                + ", BootstrapLauncher=" + bootstrapLauncher
                + ", FML=" + forgeLoader
                + ", MinecraftClientClasses=" + minecraftClient + ".");

        if (!modLauncher || !bootstrapLauncher || !forgeLoader || !minecraftClient) {
            context.log("Forge runtime is not ready. Missing Java launch/client classes must be mapped to Horizon's Rust game before mod execution can be enabled.");
        } else {
            context.log("Forge launch classes are visible. This milestone still does not invoke ModLauncher; a verified Minecraft API adapter and lifecycle mapping are required first.");
        }
    }

    @Override
    public void onEvent(CompatibilityEvent event, CompatibilityContext context) {
        switch (event.type()) {
            case "tick" -> ticks.incrementAndGet();
            case "frame" -> frames.incrementAndGet();
            case "world_lifecycle" -> context.log("World lifecycle event received by Forge adapter: "
                    + truncate(event.payloadJson(), 320));
            case "server_tick" -> {
                // Reserved for the Forge event bus mapping stage.
            }
            default -> {
                // Unknown Horizon events are intentionally ignored by this probe.
            }
        }
    }

    @Override
    public void onUnload() {
        System.out.println("Forge compatibility probe stopped. ticks=" + ticks.get()
                + ", frames=" + frames.get());
    }

    private static boolean classAvailable(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return false;
        }
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum) + "...";
    }
}
