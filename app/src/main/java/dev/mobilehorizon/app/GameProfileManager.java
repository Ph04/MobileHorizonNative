package dev.mobilehorizon.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-instance Minecraft/loader identity.
 *
 * Worlds, options, logs, mods and configs stay in the instance game home. Shared official
 * Minecraft resources, Horizon runtime and installed compatibility modules are not copied.
 */
public final class GameProfileManager {
    private static final int FORMAT = 1;
    private static final String PROFILE_DIRECTORY = ".mobilehorizon";
    private static final String PROFILE_FILE = "profile.json";
    private static final String LAUNCH_FILE = "launch-profile.json";

    public static final class Profile {
        public final String instanceId;
        public final String minecraftVersion;
        public final String loaderId;
        public final String loaderVersion;
        public final String moduleId;
        public final String moduleVersion;

        private Profile(String instanceId, String minecraftVersion, String loaderId,
                        String loaderVersion, String moduleId, String moduleVersion) {
            this.instanceId = instanceId;
            this.minecraftVersion = minecraftVersion;
            this.loaderId = loaderId;
            this.loaderVersion = loaderVersion;
            this.moduleId = moduleId;
            this.moduleVersion = moduleVersion;
        }

        public boolean isVanilla() {
            return "vanilla".equals(loaderId);
        }

        public String displayName() {
            if (isVanilla()) return "Vanilla " + minecraftVersion;
            return capitalize(loaderId) + " " + loaderVersion + " • Minecraft " + minecraftVersion;
        }
    }

    public static final class LoaderChoice {
        public final String loaderId;
        public final String loaderVersion;
        public final String moduleId;
        public final String moduleVersion;
        public final String label;

        private LoaderChoice(String loaderId, String loaderVersion, String moduleId,
                             String moduleVersion, String label) {
            this.loaderId = loaderId;
            this.loaderVersion = loaderVersion;
            this.moduleId = moduleId;
            this.moduleVersion = moduleVersion;
            this.label = label;
        }

        @Override public String toString() {
            return label;
        }
    }

    public static final class LaunchProfile {
        public final Profile profile;
        public final File profileFile;
        public final File launchFile;
        public final File modsDirectory;
        public final File moduleDirectory;
        public final String entrypointJar;
        public final String entrypointClass;

        private LaunchProfile(Profile profile, File profileFile, File launchFile,
                              File modsDirectory, File moduleDirectory,
                              String entrypointJar, String entrypointClass) {
            this.profile = profile;
            this.profileFile = profileFile;
            this.launchFile = launchFile;
            this.modsDirectory = modsDirectory;
            this.moduleDirectory = moduleDirectory;
            this.entrypointJar = entrypointJar;
            this.entrypointClass = entrypointClass;
        }

        public String modulePathForEnvironment() {
            return moduleDirectory == null ? "" : moduleDirectory.getAbsolutePath();
        }
    }

    private GameProfileManager() {}

    public static boolean isValidMinecraftVersion(String value) {
        return value != null && value.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?");
    }

    public static synchronized List<LoaderChoice> listLoaderChoices(
            Context context, String minecraftVersion) throws IOException {
        if (!isValidMinecraftVersion(minecraftVersion)) {
            throw new IOException("Versão do Minecraft inválida.");
        }
        ArrayList<LoaderChoice> choices = new ArrayList<>();
        choices.add(new LoaderChoice("vanilla", "", "", "", "Vanilla • Minecraft " + minecraftVersion));
        for (CompatibilityPluginManager.Module module
                : CompatibilityPluginManager.listModules(context)) {
            if (!module.minecraftVersions.contains(minecraftVersion)) continue;
            String label = capitalize(module.loaderId) + " " + module.loaderVersion
                    + " • " + module.name + " " + module.version;
            choices.add(new LoaderChoice(module.loaderId, module.loaderVersion,
                    module.id, module.version, label));
        }
        return Collections.unmodifiableList(choices);
    }

    public static synchronized Profile getProfile(Context context, String instanceId)
            throws IOException {
        String safeId = requireInstanceId(instanceId);
        File home = GameInstanceManager.getInstanceHome(context, safeId);
        File file = profileFile(home);
        if (!file.isFile()) {
            Profile initial = new Profile(safeId, OfficialMinecraftInstaller.VERSION,
                    "vanilla", "", "", "");
            writeProfile(file, initial);
            return initial;
        }
        return readProfile(file, safeId);
    }

    public static synchronized Profile configureProfile(
            Context context, String instanceId, String minecraftVersion, LoaderChoice choice)
            throws IOException {
        if (choice == null) throw new IOException("Escolha um perfil de jogo.");
        String safeId = requireInstanceId(instanceId);
        if (!isValidMinecraftVersion(minecraftVersion)) {
            throw new IOException("Versão do Minecraft inválida.");
        }

        Profile profile;
        if ("vanilla".equals(choice.loaderId)) {
            if (!choice.moduleId.isEmpty() || !choice.moduleVersion.isEmpty()) {
                throw new IOException("Um perfil Vanilla não pode depender de um módulo.");
            }
            profile = new Profile(safeId, minecraftVersion, "vanilla", "", "", "");
        } else {
            CompatibilityPluginManager.Module module = CompatibilityPluginManager.findModule(
                    context, choice.moduleId, choice.moduleVersion);
            if (!module.supports(minecraftVersion, choice.loaderId)
                    || !module.loaderVersion.equals(choice.loaderVersion)) {
                throw new IOException("O módulo selecionado não oferece suporte a este perfil.");
            }
            profile = new Profile(safeId, minecraftVersion, module.loaderId,
                    module.loaderVersion, module.id, module.version);
        }

        File home = GameInstanceManager.getInstanceHome(context, safeId);
        writeProfile(profileFile(home), profile);
        return profile;
    }

    /**
     * Validates the selected module and writes a native-runtime handoff descriptor.
     * The Horizon runtime must consume this descriptor to activate the compatibility bridge.
     */
    public static synchronized LaunchProfile prepareLaunch(Context context, String instanceId)
            throws IOException {
        Profile profile = getProfile(context, instanceId);
        File home = GameInstanceManager.getInstanceHome(context, profile.instanceId);
        File mods = safeDirectory(home, "mods");
        File profileDirectory = safeDirectory(home, PROFILE_DIRECTORY);
        File profileFile = safeChild(profileDirectory, PROFILE_FILE);
        File launchFile = safeChild(profileDirectory, LAUNCH_FILE);

        CompatibilityPluginManager.Module module = null;
        if (!profile.isVanilla()) {
            module = CompatibilityPluginManager.findModule(
                    context, profile.moduleId, profile.moduleVersion);
            if (!module.supports(profile.minecraftVersion, profile.loaderId)
                    || !module.loaderVersion.equals(profile.loaderVersion)) {
                throw new IOException("O módulo de compatibilidade não corresponde ao perfil selecionado.");
            }
        }

        JSONObject launch = new JSONObject();
        try {
            launch.put("format", FORMAT);
            launch.put("instance_id", profile.instanceId);
            launch.put("minecraft_version", profile.minecraftVersion);
            launch.put("loader", profile.loaderId);
            launch.put("loader_version", profile.loaderVersion);
            launch.put("mods_directory", mods.getAbsolutePath());
            if (module != null) {
                JSONObject plugin = new JSONObject();
                plugin.put("id", module.id);
                plugin.put("version", module.version);
                plugin.put("directory", module.directory.getAbsolutePath());
                plugin.put("entrypoint_jar", module.entrypointFile.getAbsolutePath());
                plugin.put("entrypoint_class", module.entrypointClass);
                plugin.put("api_version", CompatibilityPluginManager.PLUGIN_API_VERSION);
                launch.put("compatibility_module", plugin);
            } else {
                launch.put("compatibility_module", JSONObject.NULL);
            }
        } catch (JSONException impossible) {
            throw new IOException("Não foi possível montar o perfil de inicialização.", impossible);
        }
        writeJsonAtomically(launchFile, launch);
        return new LaunchProfile(profile, profileFile, launchFile, mods,
                module == null ? null : module.directory,
                module == null ? "" : module.entrypointFile.getAbsolutePath(),
                module == null ? "" : module.entrypointClass);
    }

    public static synchronized boolean isModuleReferenced(
            Context context, String moduleId, String moduleVersion) throws IOException {
        for (GameInstanceManager.Instance instance : GameInstanceManager.listInstances(context)) {
            File file = profileFile(instance.gameHome);
            if (!file.isFile()) continue;
            Profile profile = readProfile(file, instance.id);
            if (moduleId.equals(profile.moduleId) && moduleVersion.equals(profile.moduleVersion)) {
                return true;
            }
        }
        return false;
    }

    private static Profile readProfile(File file, String expectedInstanceId) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile()
                || file.length() <= 0L || file.length() > 64 * 1024) {
            throw new IOException("O perfil desta instância está ausente ou inválido.");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("O perfil da instância está incompleto.");
                offset += count;
            }
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (json.optInt("format", -1) != FORMAT) {
                throw new IOException("Formato de perfil de jogo não suportado.");
            }
            String instanceId = requireInstanceId(json.optString("instance_id", ""));
            if (!expectedInstanceId.equals(instanceId)) {
                throw new IOException("O perfil pertence a outra instância.");
            }
            String gameVersion = json.optString("minecraft_version", "");
            if (!isValidMinecraftVersion(gameVersion)) throw new IOException("Versão do Minecraft inválida.");
            String loader = json.optString("loader", "vanilla");
            String loaderVersion = json.optString("loader_version", "");
            String moduleId = json.optString("module_id", "");
            String moduleVersion = json.optString("module_version", "");
            if ("vanilla".equals(loader)) {
                if (!loaderVersion.isEmpty() || !moduleId.isEmpty() || !moduleVersion.isEmpty()) {
                    throw new IOException("Perfil Vanilla contém referência de módulo indevida.");
                }
            } else {
                try {
                    loader = CompatibilityPluginManager.requireLoaderId(loader);
                    if (loaderVersion.isEmpty() || moduleId.isEmpty() || moduleVersion.isEmpty()) {
                        throw new IOException("Perfil de compatibilidade incompleto.");
                    }
                    CompatibilityPluginManager.requireId(moduleId);
                    CompatibilityPluginManager.requireVersion(moduleVersion);
                } catch (IOException invalid) {
                    throw new IOException("Loader ou módulo do perfil inválido.", invalid);
                }
            }
            return new Profile(instanceId, gameVersion, loader, loaderVersion, moduleId, moduleVersion);
        } catch (JSONException error) {
            throw new IOException("O perfil desta instância não contém JSON válido.", error);
        }
    }

    private static void writeProfile(File file, Profile profile) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("format", FORMAT);
            json.put("instance_id", profile.instanceId);
            json.put("minecraft_version", profile.minecraftVersion);
            json.put("loader", profile.loaderId);
            json.put("loader_version", profile.loaderVersion);
            json.put("module_id", profile.moduleId);
            json.put("module_version", profile.moduleVersion);
        } catch (JSONException impossible) {
            throw new IOException("Não foi possível montar os metadados da instância.", impossible);
        }
        writeJsonAtomically(file, json);
    }

    private static void writeJsonAtomically(File target, JSONObject json) throws IOException {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Não foi possível criar os metadados da instância.");
        }
        if (Files.isSymbolicLink(parent.toPath()) || Files.isSymbolicLink(target.toPath())) {
            throw new IOException("Metadados de perfil não podem usar links simbólicos.");
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        if (Files.isSymbolicLink(temporary.toPath())) throw new IOException("Arquivo temporário inválido.");
        byte[] bytes;
        try {
            bytes = (json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IOException("Não foi possível serializar o perfil.", error);
        }
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static File profileFile(File home) throws IOException {
        File directory = safeDirectory(home, PROFILE_DIRECTORY);
        return safeChild(directory, PROFILE_FILE);
    }

    private static File safeDirectory(File root, String name) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File requested = new File(canonicalRoot, name);
        if (!requested.isDirectory() && !requested.mkdirs()) {
            throw new IOException("Não foi possível criar " + name + " da instância.");
        }
        if (Files.isSymbolicLink(requested.toPath())) {
            throw new IOException("Diretório de instância não pode ser link simbólico.");
        }
        File canonical = requested.getCanonicalFile();
        if (!canonicalRoot.equals(canonical.getParentFile())) {
            throw new IOException("Diretório da instância fora do local privado esperado.");
        }
        return canonical;
    }

    private static File safeChild(File root, String name) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File child = new File(canonicalRoot, name).getCanonicalFile();
        if (!canonicalRoot.equals(child.getParentFile())) {
            throw new IOException("Arquivo de perfil fora da pasta privada.");
        }
        return child;
    }

    private static String requireInstanceId(String value) throws IOException {
        if (!GameInstanceManager.isValidInstanceId(value)) {
            throw new IOException("Identificador da instância inválido.");
        }
        return value;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return "";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
