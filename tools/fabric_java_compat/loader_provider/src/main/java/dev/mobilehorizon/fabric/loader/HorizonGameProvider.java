package dev.mobilehorizon.fabric.loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;

/** Safe, isolated metadata-resolution provider. Never point it at original mod JARs. */
public final class HorizonGameProvider implements GameProvider {
    private static final String GAME_VERSION = "26.3";
    private final GameTransformer transformer = new GameTransformer();
    private final Arguments arguments = new Arguments();
    private Path auditRoot = Paths.get(".").toAbsolutePath().normalize();

    @Override public String getGameId() { return "minecraft"; }
    @Override public String getGameName() { return "Minecraft (Horizon preflight)"; }
    @Override public String getRawGameVersion() { return GAME_VERSION; }
    @Override public String getNormalizedGameVersion() { return GAME_VERSION; }

    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        ModMetadata metadata = new BuiltinModMetadata.Builder("minecraft", GAME_VERSION)
                .setName("Minecraft 26.3 (Horizon native compatibility endpoint)")
                .setDescription("Synthetic version marker for isolated metadata preflight; supplies no Minecraft Java classes.")
                .build();
        return Collections.singletonList(new BuiltinMod(Collections.emptyList(), metadata));
    }

    @Override public String getEntrypoint() { return HorizonPreflightMain.class.getName(); }
    @Override public Path getLaunchDirectory() { return auditRoot; }
    @Override public boolean requiresUrlClassLoader() { return false; }
    @Override public Set<BuiltinTransform> getBuiltinTransforms(String className) { return Collections.emptySet(); }

    @Override
    public boolean isEnabled() {
        return Boolean.getBoolean("horizon.fabric.provider.enabled")
                && Boolean.getBoolean("horizon.fabric.preflight");
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        if (!isEnabled()) return false;
        String root = System.getProperty("horizon.fabric.auditRoot", "").trim();
        if (root.isEmpty()) return false;
        Path candidate = Paths.get(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate)) return false;
        auditRoot = candidate;
        arguments.parse(args);
        return true;
    }

    @Override public void initialize(FabricLauncher launcher) { launcher.setValidParentClassPath(launcher.getClassPath()); }
    @Override public GameTransformer getEntrypointTransformer() { return transformer; }
    @Override public void unlockClassPath(FabricLauncher launcher) { }
    @Override public Arguments getArguments() { return arguments; }
    @Override public String[] getLaunchArguments(boolean sanitize) { return arguments.toArray(); }
    @Override public String getRuntimeNamespace(String defaultNs) { return "official"; }
    @Override public String getDefaultModDistributionNamespace(String defaultNs) { return "official"; }
    @Override public boolean canOpenErrorGui() { return false; }
    @Override public boolean hasAwtSupport() { return false; }

    @Override
    public boolean displayCrash(Throwable exception, String context) {
        try {
            String reportPath = System.getProperty("horizon.fabric.report", "").trim();
            if (!reportPath.isEmpty()) {
                Path target = Paths.get(reportPath).toAbsolutePath().normalize();
                Files.createDirectories(target.getParent());
                Files.writeString(target, "{\"ok\":false,\"stage\":\"fabric-loader\",\"error\":\""
                        + escape(exception == null ? context : exception.toString()) + "\"}\n");
            }
        } catch (Exception ignored) { }
        return true;
    }

    @Override
    public void launch(ClassLoader loader) {
        Collection<ModContainer> discovered = FabricLoader.getInstance().getAllMods();
        List<ModContainer> mods = new ArrayList<>(discovered);
        mods.sort((left, right) -> left.getMetadata().getId().compareTo(right.getMetadata().getId()));
        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true,\"mode\":\"metadata_preflight_only\",\"game_version\":\"")
                .append(GAME_VERSION).append("\",\"loader_version\":\"")
                .append(escape(FabricLoader.getInstance().getModContainer("fabricloader")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown")))
                .append("\",\"mods\":[");
        boolean first = true;
        for (ModContainer container : mods) {
            ModMetadata metadata = container.getMetadata();
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":\"").append(escape(metadata.getId()))
                    .append("\",\"version\":\"")
                    .append(escape(metadata.getVersion().getFriendlyString())).append("\"}");
        }
        json.append("]}");
        String report = json.toString();
        try {
            String reportPath = System.getProperty("horizon.fabric.report", "").trim();
            if (reportPath.isEmpty()) throw new IOException("horizon.fabric.report was not provided");
            Path target = Paths.get(reportPath).toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            Files.writeString(target, report + "\n");
        } catch (IOException error) {
            throw new IllegalStateException("Could not write the Fabric Loader preflight report", error);
        }
        System.out.println("HORIZON_FABRIC_PREFLIGHT_RESULT=" + report);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
