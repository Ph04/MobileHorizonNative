package dev.mobilehorizon.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs and catalogs optional Horizon compatibility modules without changing the APK.
 *
 * A module package is a ZIP with:
 *   mobilehorizon-plugin.json
 *   payload/<entrypoint jar and optional runtime files>
 *
 * The Android launcher stores modules once. GameProfileManager selects which module is
 * associated with each isolated game instance and NativeGameActivity passes that profile
 * to the native runtime. The native Horizon bridge remains responsible for executing the
 * module entrypoint; installing a package alone does not claim that a loader is operational.
 */
public final class CompatibilityPluginManager {
    public static final int PLUGIN_API_VERSION = 1;
    public static final String DESCRIPTOR_NAME = "mobilehorizon-plugin.json";
    private static final String ROOT_NAME = "compatibility-modules";
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_TOTAL_UNPACKED_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 12000;
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;

    public static final class Module {
        public final String id;
        public final String name;
        public final String version;
        public final String loaderId;
        public final String loaderVersion;
        public final String entrypointJar;
        public final String entrypointClass;
        public final String description;
        public final List<String> minecraftVersions;
        public final File directory;
        public final File entrypointFile;

        private Module(JSONObject json, File directory) throws IOException {
            id = requireId(json.optString("id", ""));
            name = requireText(json.optString("name", ""), "nome", 64);
            version = requireVersion(json.optString("version", ""));
            loaderId = requireLoaderId(json.optString("loader", ""));
            loaderVersion = requireText(json.optString("loaderVersion", ""), "versão do loader", 48);
            entrypointJar = requireRelativePayloadPath(json.optString("entrypointJar", ""));
            entrypointClass = requireClassName(json.optString("entrypointClass", ""));
            description = safeOptionalText(json.optString("description", ""), 240);
            JSONArray versions = json.optJSONArray("minecraftVersions");
            if (versions == null || versions.length() == 0 || versions.length() > 32) {
                throw new IOException("O módulo precisa declarar pelo menos uma versão do Minecraft.");
            }
            ArrayList<String> supported = new ArrayList<>();
            for (int i = 0; i < versions.length(); i++) {
                String gameVersion = versions.optString(i, "");
                if (!GameProfileManager.isValidMinecraftVersion(gameVersion)) {
                    throw new IOException("O módulo declara uma versão de Minecraft inválida.");
                }
                if (!supported.contains(gameVersion)) supported.add(gameVersion);
            }
            minecraftVersions = Collections.unmodifiableList(supported);
            this.directory = directory.getCanonicalFile();
            entrypointFile = safeChild(this.directory, "payload/" + entrypointJar);
            if (!entrypointFile.isFile() || entrypointFile.length() <= 0L) {
                throw new IOException("O JAR de entrada declarado pelo módulo não existe.");
            }
        }

        public boolean supports(String gameVersion, String requestedLoader) {
            return loaderId.equals(requestedLoader) && minecraftVersions.contains(gameVersion);
        }

        public String displayLabel() {
            return name + " " + version + " (" + loaderId + " " + loaderVersion + ")";
        }
    }

    private CompatibilityPluginManager() {}

    /** Installs a user-selected .mhplugin ZIP into app-private storage. */
    public static synchronized Module install(Context context, InputStream source) throws IOException {
        if (source == null) throw new IOException("O arquivo do módulo não pôde ser aberto.");
        File root = validatedRoot(context);
        File staging = new File(root, ".staging-" + UUID.randomUUID().toString().replace("-", ""));
        if (!staging.mkdirs()) throw new IOException("Não foi possível criar a área temporária do módulo.");

        byte[] descriptorBytes = null;
        Set<String> writtenPaths = new HashSet<>();
        long totalBytes = 0L;
        long archiveBytes = 0L;
        int entryCount = 0;
        boolean installed = false;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) throw new IOException("O pacote contém entradas demais.");
                String name = normalizeZipPath(entry.getName());
                if (entry.isDirectory()) {
                    if (name.startsWith("payload/")) {
                        File directory = safeChild(staging, name);
                        if (!directory.isDirectory() && !directory.mkdirs()) {
                            throw new IOException("Não foi possível criar uma pasta do módulo.");
                        }
                    }
                    zip.closeEntry();
                    continue;
                }

                if (!writtenPaths.add(name)) throw new IOException("O pacote contém caminhos duplicados.");
                if (DESCRIPTOR_NAME.equals(name)) {
                    if (descriptorBytes != null) throw new IOException("O descritor do módulo está duplicado.");
                    descriptorBytes = readEntry(zip, MAX_DESCRIPTOR_BYTES, buffer);
                    archiveBytes += descriptorBytes.length;
                } else if (name.startsWith("payload/") && name.length() > "payload/".length()) {
                    File destination = safeChild(staging, name);
                    File parent = destination.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Não foi possível preparar os arquivos do módulo.");
                    }
                    long written = 0L;
                    try (FileOutputStream output = new FileOutputStream(destination)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            written += count;
                            totalBytes += count;
                            archiveBytes += count;
                            if (written > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_UNPACKED_BYTES
                                    || archiveBytes > MAX_ARCHIVE_BYTES) {
                                throw new IOException("O pacote excede os limites de tamanho permitidos.");
                            }
                            output.write(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                } else {
                    throw new IOException("O pacote contém um arquivo fora da estrutura permitida: " + name);
                }
                zip.closeEntry();
            }
        } catch (IOException error) {
            deleteTree(staging);
            throw error;
        } catch (RuntimeException error) {
            deleteTree(staging);
            throw new IOException("Não foi possível ler o pacote do módulo.", error);
        }

        try {
            if (descriptorBytes == null) throw new IOException("O pacote não contém " + DESCRIPTOR_NAME + ".");
            JSONObject descriptor = new JSONObject(new String(descriptorBytes, StandardCharsets.UTF_8));
            Module parsed = parseModule(descriptor, staging);
            File targetParent = safeChild(root, parsed.id);
            if (!targetParent.isDirectory() && !targetParent.mkdirs()) {
                throw new IOException("Não foi possível criar a pasta do módulo instalado.");
            }
            File target = safeChild(targetParent, parsed.version);
            if (!targetParent.getCanonicalFile().equals(target.getParentFile())) {
                throw new IOException("O destino do módulo está fora da pasta permitida.");
            }
            if (target.exists()) throw new IOException("Esta versão do módulo já está instalada.");
            writeBytes(new File(staging, DESCRIPTOR_NAME), descriptorBytes);
            if (!staging.renameTo(target)) {
                throw new IOException("Não foi possível finalizar a instalação do módulo.");
            }
            try {
                Module installedModule = readModule(target);
                installed = true;
                return installedModule;
            } catch (IOException verificationError) {
                deleteTree(target);
                throw verificationError;
            }
        } catch (JSONException error) {
            throw new IOException("O descritor do módulo não contém JSON válido.", error);
        } finally {
            if (!installed && staging.exists()) deleteTree(staging);
        }
    }

    public static synchronized List<Module> listModules(Context context) throws IOException {
        File root = validatedRoot(context);
        ArrayList<Module> modules = new ArrayList<>();
        File[] pluginFolders = root.listFiles();
        if (pluginFolders == null) return Collections.emptyList();
        for (File pluginFolder : pluginFolders) {
            if (!pluginFolder.isDirectory() || Files.isSymbolicLink(pluginFolder.toPath())
                    || pluginFolder.getName().startsWith(".")) continue;
            if (!pluginFolder.getName().matches("[a-z0-9][a-z0-9.-]{2,95}")) continue;
            File[] versions = pluginFolder.listFiles();
            if (versions == null) continue;
            for (File versionFolder : versions) {
                if (!versionFolder.isDirectory() || Files.isSymbolicLink(versionFolder.toPath())
                        || versionFolder.getName().startsWith(".")) continue;
                try {
                    Module module = readModule(versionFolder);
                    if (pluginFolder.getName().equals(module.id)
                            && versionFolder.getName().equals(module.version)) {
                        modules.add(module);
                    }
                } catch (IOException invalid) {
                    // A damaged package is omitted from the active catalog, never auto-executed.
                }
            }
        }
        Collections.sort(modules, (left, right) -> {
            int byName = left.name.compareToIgnoreCase(right.name);
            return byName != 0 ? byName : right.version.compareTo(left.version);
        });
        return Collections.unmodifiableList(modules);
    }

    public static synchronized Module findModule(Context context, String id, String version)
            throws IOException {
        String safeId = requireId(id);
        String safeVersion = requireVersion(version);
        File root = validatedRoot(context);
        File folder = safeChild(root, safeId + "/" + safeVersion);
        Module module = readModule(folder);
        if (!safeId.equals(module.id) || !safeVersion.equals(module.version)) {
            throw new IOException("O módulo não corresponde ao caminho instalado.");
        }
        return module;
    }

    public static synchronized List<Module> findForLoader(
            Context context, String gameVersion, String loaderId) throws IOException {
        if (!GameProfileManager.isValidMinecraftVersion(gameVersion)) {
            throw new IOException("Versão do Minecraft inválida.");
        }
        String safeLoader = requireLoaderId(loaderId);
        ArrayList<Module> result = new ArrayList<>();
        for (Module module : listModules(context)) {
            if (module.supports(gameVersion, safeLoader)) result.add(module);
        }
        return Collections.unmodifiableList(result);
    }

    public static synchronized void uninstall(
            Context context, String id, String version) throws IOException {
        Module module = findModule(context, id, version);
        if (GameProfileManager.isModuleReferenced(context, module.id, module.version)) {
            throw new IOException("Este módulo está associado a uma instância. Remova-o do perfil antes de desinstalar.");
        }
        File root = validatedRoot(context);
        File target = safeChild(root, module.id + "/" + module.version);
        deleteTree(target);
        File parent = target.getParentFile();
        File[] remaining = parent.listFiles();
        if (remaining != null && remaining.length == 0) parent.delete();
    }

    static File getRoot(Context context) throws IOException {
        return validatedRoot(context);
    }

    static String requireLoaderId(String value) throws IOException {
        if (value == null || !value.matches("(?i)(fabric|forge|neoforge|quilt)")) {
            throw new IOException("Loader de compatibilidade não suportado pelo contrato de módulos.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    static String requireId(String value) throws IOException {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{2,95}")
                || value.startsWith(".") || value.endsWith(".")) {
            throw new IOException("ID do módulo inválido.");
        }
        return value;
    }

    static String requireVersion(String value) throws IOException {
        if (value == null || !value.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")
                || value.contains("..")) {
            throw new IOException("Versão do módulo inválida.");
        }
        return value;
    }

    private static Module parseModule(JSONObject json, File directory) throws IOException {
        if (json.optInt("format", -1) != 1) throw new IOException("Formato de módulo não suportado.");
        if (json.optInt("apiVersion", -1) != PLUGIN_API_VERSION) {
            throw new IOException("A versão da API de módulos deste pacote não é compatível.");
        }
        if (!"compatibility".equals(json.optString("kind", ""))) {
            throw new IOException("Este pacote não é um módulo de compatibilidade Horizon.");
        }
        return new Module(json, directory);
    }

    private static Module readModule(File directory) throws IOException {
        if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) {
            throw new IOException("Pasta do módulo ausente ou inválida.");
        }
        File descriptor = new File(directory, DESCRIPTOR_NAME);
        if (!descriptor.isFile() || descriptor.length() <= 0
                || descriptor.length() > MAX_DESCRIPTOR_BYTES
                || Files.isSymbolicLink(descriptor.toPath())) {
            throw new IOException("Descritor do módulo ausente ou inválido.");
        }
        try (FileInputStream input = new FileInputStream(descriptor)) {
            byte[] bytes = new byte[(int) descriptor.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("Descritor do módulo incompleto.");
                offset += count;
            }
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return parseModule(json, directory);
        } catch (JSONException error) {
            throw new IOException("Descritor do módulo inválido.", error);
        }
    }

    private static byte[] readEntry(InputStream input, int maximum, byte[] buffer) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maximum) throw new IOException("Descritor do módulo muito grande.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String normalizeZipPath(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 240
                || value.startsWith("/") || value.startsWith("\\")
                || value.matches("^[A-Za-z]:.*") || value.indexOf('\0') >= 0) {
            throw new IOException("O pacote contém um caminho inválido.");
        }
        String normalized = value.replace('\\', '/');
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                if (segment.isEmpty() && normalized.endsWith("/")) continue;
                throw new IOException("O pacote contém tentativa de escapar da pasta de instalação.");
            }
        }
        return normalized;
    }

    private static String requireRelativePayloadPath(String value) throws IOException {
        String normalized = normalizeZipPath(value);
        if (!normalized.endsWith(".jar") || normalized.startsWith("payload/")) {
            throw new IOException("entrypointJar deve indicar um JAR relativo dentro de payload/.");
        }
        return normalized;
    }

    private static String requireClassName(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new IOException("Classe de entrada do módulo inválida.");
        }
        return value;
    }

    private static String requireText(String value, String label, int maximum) throws IOException {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty() || safe.length() > maximum) throw new IOException("Campo " + label + " inválido.");
        for (int i = 0; i < safe.length(); i++) {
            if (Character.isISOControl(safe.charAt(i))) throw new IOException("Campo " + label + " contém controles.");
        }
        return safe;
    }

    private static String safeOptionalText(String value, int maximum) {
        if (value == null || value.length() > maximum) return "";
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return "";
        return value.trim();
    }

    private static File validatedRoot(Context context) throws IOException {
        File filesRoot = context.getApplicationContext().getFilesDir().getCanonicalFile();
        File requested = new File(filesRoot, ROOT_NAME);
        if (!requested.isDirectory() && !requested.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta de módulos.");
        }
        File canonical = requested.getCanonicalFile();
        if (!ROOT_NAME.equals(canonical.getName()) || !filesRoot.equals(canonical.getParentFile())) {
            throw new IOException("A pasta de módulos não está no armazenamento privado esperado.");
        }
        return canonical;
    }

    private static File safeChild(File root, String relative) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File child = new File(canonicalRoot, relative).getCanonicalFile();
        String prefix = canonicalRoot.getPath() + File.separator;
        if (!child.getPath().startsWith(prefix)) throw new IOException("Caminho de módulo fora da pasta permitida.");
        return child;
    }

    private static void writeBytes(File target, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void deleteTree(File target) throws IOException {
        if (!target.exists()) return;
        if (Files.isSymbolicLink(target.toPath())) {
            if (!target.delete()) throw new IOException("Não foi possível remover link simbólico.");
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Não foi possível listar a pasta temporária.");
            for (File child : children) deleteTree(child);
        }
        if (!target.delete()) throw new IOException("Não foi possível limpar os arquivos temporários do módulo.");
    }
}
