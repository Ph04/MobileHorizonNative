package dev.mobilehorizon.app;

import android.content.Context;
import android.content.res.AssetManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installs verified Loader/provider JARs outside mods/. It does not launch Knot. */
final class FabricLoaderRuntimeInstaller {
    static final String VERSION = "0.19.5";
    private static final String ASSET_JAR = "fabric-loader/fabric-loader-" + VERSION + ".jar";
    private static final String ASSET_PROVIDER = "fabric-loader-provider.jar";
    private static final String ASSET_LOCK = "fabric-loader-runtime.json";
    private static final String ASSET_DEPENDENCY_DIR = "fabric-loader/deps/";
    private static final String[] DEPENDENCY_FILES = {
            "asm-9.10.1.jar",
            "asm-analysis-9.10.1.jar",
            "asm-commons-9.10.1.jar",
            "asm-tree-9.10.1.jar",
            "asm-util-9.10.1.jar",
            "sponge-mixin-0.17.4+mixin.0.8.7.jar"
    };
    private static final String[] DEPENDENCY_COORDINATES = {
            "org.ow2.asm:asm:9.10.1",
            "org.ow2.asm:asm-analysis:9.10.1",
            "org.ow2.asm:asm-commons:9.10.1",
            "org.ow2.asm:asm-tree:9.10.1",
            "org.ow2.asm:asm-util:9.10.1",
            "net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7"
    };
    private static final String[] DEPENDENCY_REQUIRED_CLASSES = {
            "org/objectweb/asm/ClassReader.class",
            "org/objectweb/asm/tree/analysis/Analyzer.class",
            "org/objectweb/asm/commons/ClassRemapper.class",
            "org/objectweb/asm/tree/ClassNode.class",
            "org/objectweb/asm/util/CheckClassAdapter.class",
            "org/spongepowered/asm/launch/MixinBootstrap.class"
    };
    private static final long MAX_JAR_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_PROVIDER_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_DEPENDENCY_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final Set<String> REQUIRED_CLASSES = new HashSet<>();
    private static final Set<String> REQUIRED_PROVIDER_CLASSES = new HashSet<>();
    private static final String PROVIDER_SERVICE = "META-INF/services/net.fabricmc.loader.impl.game.GameProvider";
    private static final String PROVIDER_NAME = "dev.mobilehorizon.fabric.loader.HorizonGameProvider";

    static {
        REQUIRED_CLASSES.add("net/fabricmc/loader/impl/launch/knot/KnotClient.class");
        REQUIRED_CLASSES.add("net/fabricmc/loader/impl/game/GameProvider.class");
        REQUIRED_CLASSES.add("net/fabricmc/loader/api/FabricLoader.class");
        REQUIRED_PROVIDER_CLASSES.add("dev/mobilehorizon/fabric/loader/HorizonGameProvider.class");
        REQUIRED_PROVIDER_CLASSES.add("dev/mobilehorizon/fabric/loader/HorizonPreflightMain.class");
    }

    private FabricLoaderRuntimeInstaller() {}

    static File ensureInstalled(Context context) throws Exception {
        AssetManager assets = context.getAssets();
        JSONObject lock = readLock(assets);
        String expectedHash = lock.optString("sha256", "").toLowerCase(Locale.ROOT);
        String providerHash = lock.optString("provider_sha256", "").toLowerCase(Locale.ROOT);
        if (!("net.fabricmc:fabric-loader:" + VERSION).equals(lock.optString("artifact", ""))
                || !VERSION.equals(lock.optString("loader_version", ""))
                || !"26.3".equals(lock.optString("target_minecraft_version", ""))
                || !"bundled_verified_not_launched".equals(lock.optString("execution_status", ""))
                || !expectedHash.matches("[0-9a-f]{64}")
                || !providerHash.matches("[0-9a-f]{64}")
                || !PROVIDER_NAME.equals(lock.optString("provider_service", ""))
                || !"preflight_provider_bundled_not_launched_by_game".equals(lock.optString("provider_execution_status", ""))) {
            throw new IOException("O pacote Fabric Loader/provider não passou na validação do manifesto.");
        }
        validateRequiredClasses(lock.optJSONArray("required_classes"), REQUIRED_CLASSES, "Fabric Loader");
        JSONArray dependencies = validateDependencyManifest(lock.optJSONArray("dependencies"));
        File appFiles = context.getFilesDir().getCanonicalFile();
        File loaderDir = new File(new File(new File(appFiles, "horizon"), "fabric-bridge-runtime"), "loader");
        ensureDirectory(loaderDir);
        File loaderTarget = new File(loaderDir, "fabric-loader-" + VERSION + ".jar");
        File providerTarget = new File(loaderDir, "horizon-fabric-loader-provider.jar");
        installOne(assets, ASSET_JAR, loaderTarget, expectedHash, MAX_JAR_BYTES,
                FabricLoaderRuntimeInstaller::validateLoaderJar);
        installOne(assets, ASSET_PROVIDER, providerTarget, providerHash, MAX_PROVIDER_BYTES,
                FabricLoaderRuntimeInstaller::validateProviderJar);
        File dependencyDir = new File(loaderDir, "deps");
        ensureDirectory(dependencyDir);
        for (int i = 0; i < DEPENDENCY_FILES.length; i++) {
            JSONObject dependency = dependencies.optJSONObject(i);
            String filename = DEPENDENCY_FILES[i];
            String expectedDependencyHash = dependency.optString("sha256", "").toLowerCase(Locale.ROOT);
            File target = new File(dependencyDir, filename);
            final String requiredClass = DEPENDENCY_REQUIRED_CLASSES[i];
            installOne(assets, ASSET_DEPENDENCY_DIR + filename, target, expectedDependencyHash,
                    MAX_DEPENDENCY_BYTES, file -> validateDependencyJar(file, requiredClass));
        }
        return loaderTarget;
    }

    static File providerPath(Context context) throws IOException {
        File root = new File(new File(new File(context.getFilesDir(), "horizon"), "fabric-bridge-runtime"), "loader");
        return new File(root, "horizon-fabric-loader-provider.jar").getCanonicalFile();
    }

    static String runtimeClasspath(Context context, File loader, File provider) throws IOException {
        File root = new File(new File(new File(context.getFilesDir(), "horizon"), "fabric-bridge-runtime"), "loader");
        File dependencyDir = new File(root, "deps");
        StringBuilder classpath = new StringBuilder(loader.getAbsolutePath())
                .append(File.pathSeparator).append(provider.getAbsolutePath());
        for (String filename : DEPENDENCY_FILES) {
            File dependency = new File(dependencyDir, filename);
            if (!dependency.isFile() || dependency.length() <= 0
                    || dependency.length() > MAX_DEPENDENCY_BYTES) {
                throw new IOException("Dependência oficial do Fabric Loader não foi instalada: " + filename);
            }
            classpath.append(File.pathSeparator).append(dependency.getAbsolutePath());
        }
        return classpath.toString();
    }

    private static JSONArray validateDependencyManifest(JSONArray dependencies) throws IOException {
        if (dependencies == null || dependencies.length() != DEPENDENCY_FILES.length) {
            throw new IOException("O manifesto Fabric não contém o conjunto completo de dependências do Loader.");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < DEPENDENCY_FILES.length; i++) {
            JSONObject dependency = dependencies.optJSONObject(i);
            if (dependency == null
                    || !DEPENDENCY_FILES[i].equals(dependency.optString("file", ""))
                    || !DEPENDENCY_COORDINATES[i].equals(dependency.optString("artifact", ""))
                    || !seen.add(dependency.optString("file", ""))) {
                throw new IOException("O manifesto Fabric contém uma dependência desconhecida ou duplicada.");
            }
            String hash = dependency.optString("sha256", "").toLowerCase(Locale.ROOT);
            long size = dependency.optLong("size_bytes", -1L);
            if (!hash.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_DEPENDENCY_BYTES) {
                throw new IOException("O manifesto Fabric contém hash/tamanho inválido para " + DEPENDENCY_FILES[i]);
            }
        }
        return dependencies;
    }

    private static boolean validateDependencyJar(File jarFile, String requiredClass) {
        if (!jarFile.isFile() || jarFile.length() <= 0 || jarFile.length() > MAX_DEPENDENCY_BYTES) return false;
        try (ZipFile archive = new ZipFile(jarFile)) {
            ZipEntry entry = archive.getEntry(requiredClass);
            return entry != null && !entry.isDirectory() && entry.getSize() > 0
                    && entry.getSize() <= 2L * 1024L * 1024L;
        } catch (Exception error) {
            return false;
        }
    }

    private interface JarValidator { boolean validate(File path); }

    private static void installOne(AssetManager assets, String asset, File target,
                                   String expectedHash, long maxBytes, JarValidator validator) throws Exception {
        if (target.isFile() && target.length() > 0 && target.length() <= maxBytes
                && expectedHash.equals(sha256(target)) && validator.validate(target)) return;
        if (target.exists() && !validator.validate(target)) {
            throw new IOException("O destino reservado do runtime Fabric contém um arquivo inválido: " + target.getName());
        }
        File temporary = new File(target.getParentFile(), "." + target.getName() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Não foi possível remover a cópia temporária incompleta: " + temporary.getName());
        }
        try {
            copyAsset(assets, asset, temporary, maxBytes);
            if (temporary.length() <= 0 || temporary.length() > maxBytes
                    || !expectedHash.equals(sha256(temporary)) || !validator.validate(temporary)) {
                throw new IOException("O artefato Fabric copiado não corresponde ao manifesto verificado: " + asset);
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicMoveUnavailable) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!expectedHash.equals(sha256(target)) || !validator.validate(target)) {
                throw new IOException("O artefato Fabric instalado falhou na verificação final: " + target.getName());
            }
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static void validateRequiredClasses(JSONArray values, Set<String> expected, String label) throws IOException {
        if (values == null || values.length() != expected.size()) {
            throw new IOException(label + " não contém a lista completa de classes esperadas.");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            String name = values.optString(i, "");
            if (!expected.contains(name) || !seen.add(name)) {
                throw new IOException(label + " contém uma classe não reconhecida no manifesto.");
            }
        }
        if (!seen.equals(expected)) throw new IOException(label + " está incompleto.");
    }

    private static JSONObject readLock(AssetManager assets) throws Exception {
        try (InputStream input = assets.open(ASSET_LOCK)) {
            return new JSONObject(new String(readLimited(input, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
        }
    }

    private static void copyAsset(AssetManager assets, String asset, File destination, long maxBytes) throws IOException {
        try (InputStream input = new BufferedInputStream(assets.open(asset));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Artefato Fabric excede o limite configurado: " + asset);
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static boolean validateLoaderJar(File jarFile) {
        if (!jarFile.isFile() || jarFile.length() <= 0 || jarFile.length() > MAX_JAR_BYTES) return false;
        try (ZipFile archive = new ZipFile(jarFile)) {
            for (String name : REQUIRED_CLASSES) {
                ZipEntry entry = archive.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() <= 0
                        || entry.getSize() > 2L * 1024L * 1024L) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean validateProviderJar(File jarFile) {
        if (!jarFile.isFile() || jarFile.length() <= 0 || jarFile.length() > MAX_PROVIDER_BYTES) return false;
        try (ZipFile archive = new ZipFile(jarFile)) {
            for (String name : REQUIRED_PROVIDER_CLASSES) {
                ZipEntry entry = archive.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() <= 0) return false;
            }
            ZipEntry service = archive.getEntry(PROVIDER_SERVICE);
            if (service == null || service.isDirectory() || service.getSize() > 2048) return false;
            try (InputStream input = archive.getInputStream(service)) {
                return PROVIDER_NAME.equals(new String(readLimited(input, 2048), StandardCharsets.UTF_8).trim());
            }
        } catch (Exception error) {
            return false;
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > limit) throw new IOException("Manifesto Fabric excede o limite permitido.");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Não foi possível criar pasta do Fabric Loader: " + directory.getAbsolutePath());
        }
    }
}
