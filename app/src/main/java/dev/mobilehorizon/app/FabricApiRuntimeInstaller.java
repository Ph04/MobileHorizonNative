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
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Stages the complete official Fabric API artifact as one original mod JAR.
 * It verifies the build-generated SHA-256 lock and never executes or explodes the archive.
 */
final class FabricApiRuntimeInstaller {
    static final String VERSION = "0.165.0+26.3";
    static final String MOD_ID = "fabric-api";
    private static final String ASSET_JAR = "fabric-bridge/fabric-api-" + VERSION + ".jar";
    private static final String ASSET_LOCK = "fabric-api-runtime.json";
    private static final long MAX_JAR_BYTES = 12L * 1024L * 1024L;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_NESTED_JARS = 100;
    private static final long MAX_NESTED_JAR_BYTES = 8L * 1024L * 1024L;

    private FabricApiRuntimeInstaller() {}

    static File ensureInstalled(Context context) throws Exception {
        AssetManager assets = context.getAssets();
        JSONObject lock = readLock(assets);
        String expectedHash = lock.optString("sha256", "").toLowerCase(Locale.ROOT);
        if (!("net.fabricmc.fabric-api:fabric-api:" + VERSION).equals(lock.optString("artifact", ""))
                || !VERSION.equals(lock.optString("fabric_api_version", ""))
                || !"26.3".equals(lock.optString("target_minecraft_version", ""))
                || !MOD_ID.equals(lock.optString("mod_id", ""))
                || !"single_original_mod_jar".equals(lock.optString("bundle_mode", ""))
                || !expectedHash.matches("[0-9a-f]{64}")) {
            throw new IOException("O pacote oficial Fabric API embutido não passou na validação do manifesto.");
        }

        File appFiles = context.getFilesDir().getCanonicalFile();
        File gameHome = new File(appFiles, "horizon");
        File javaMods = new File(new File(gameHome, "fabric-bridge-runtime"), "mods");
        javaMods.mkdirs();
        if (!javaMods.isDirectory()) throw new IOException("Não foi possível criar a pasta Java do perfil Fabric Bridge.");

        File target = new File(javaMods, "fabric-api-" + VERSION + ".jar");
        if (target.isFile() && !expectedHash.equals(sha256(target)) && !validateJar(target, lock)) {
            throw new IOException("O nome reservado do JAR Fabric API já existe com conteúdo diferente; nada foi sobrescrito.");
        }
        File[] otherJars = javaMods.listFiles((directory, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        && !name.equals(target.getName()));
        if (otherJars != null) {
            for (File other : otherJars) {
                if (isFabricApiMod(other)) {
                    throw new IOException("Há outra versão do Fabric API no perfil Java. Remova a cópia antiga antes de ativar o Bridge.");
                }
            }
        }
        if (target.isFile() && target.length() <= MAX_JAR_BYTES
                && expectedHash.equals(sha256(target)) && validateJar(target, lock)) {
            return target;
        }

        File temporary = new File(javaMods, ".fabric-api-" + VERSION + ".jar.part");
        temporary.delete();
        try {
            copyAsset(assets, temporary);
            if (temporary.length() <= 0 || temporary.length() > MAX_JAR_BYTES
                    || !expectedHash.equals(sha256(temporary))) {
                throw new IOException("A cópia local da Fabric API falhou na verificação SHA-256.");
            }
            if (!validateJar(temporary, lock)) {
                throw new IOException("O JAR Fabric API completo não passou na validação de metadados/módulos.");
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicMoveUnavailable) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!expectedHash.equals(sha256(target))) {
                throw new IOException("A Fabric API instalada não corresponde ao hash validado.");
            }
            return target;
        } finally {
            temporary.delete();
        }
    }

    private static JSONObject readLock(AssetManager assets) throws Exception {
        try (InputStream input = assets.open(ASSET_LOCK)) {
            byte[] raw = readLimited(input, 64 * 1024);
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        }
    }

    private static void copyAsset(AssetManager assets, File destination) throws IOException {
        try (InputStream input = new BufferedInputStream(assets.open(ASSET_JAR));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_JAR_BYTES) throw new IOException("Fabric API excede o limite do pacote.");
                output.write(buffer, 0, read);
            }
        }
    }

    private static boolean validateJar(File jarFile, JSONObject lock) {
        try (ZipFile archive = new ZipFile(jarFile)) {
            ZipEntry entry = archive.getEntry("fabric.mod.json");
            if (entry == null || entry.isDirectory() || entry.getSize() < 0
                    || entry.getSize() > MAX_METADATA_BYTES) return false;
            JSONObject metadata;
            try (InputStream input = archive.getInputStream(entry)) {
                metadata = new JSONObject(new String(readLimited(input, MAX_METADATA_BYTES), StandardCharsets.UTF_8));
            }
            if (!MOD_ID.equals(metadata.optString("id", ""))
                    || !VERSION.equals(metadata.optString("version", ""))) return false;
            JSONArray declarations = metadata.optJSONArray("jars");
            JSONArray lockedPaths = lock.optJSONArray("nested_module_paths");
            int expectedCount = lock.optInt("nested_module_jars_declared", -1);
            if (declarations == null || lockedPaths == null || declarations.length() != expectedCount
                    || lockedPaths.length() != expectedCount || expectedCount <= 0
                    || expectedCount > MAX_NESTED_JARS) return false;
            java.util.HashSet<String> locked = new java.util.HashSet<>();
            for (int index = 0; index < lockedPaths.length(); index++) {
                String raw = lockedPaths.optString(index, "");
                if (!safeRelativePath(raw) || !locked.add(raw)) return false;
                ZipEntry nested = archive.getEntry(raw);
                if (nested == null || nested.isDirectory() || nested.getSize() <= 0
                        || nested.getSize() > MAX_NESTED_JAR_BYTES) return false;
            }
            for (int index = 0; index < declarations.length(); index++) {
                JSONObject declaration = declarations.optJSONObject(index);
                if (declaration == null || !locked.contains(declaration.optString("file", ""))) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean isFabricApiMod(File candidate) {
        try (ZipFile archive = new ZipFile(candidate)) {
            ZipEntry entry = archive.getEntry("fabric.mod.json");
            if (entry == null || entry.getSize() <= 0 || entry.getSize() > MAX_METADATA_BYTES) return false;
            try (InputStream input = archive.getInputStream(entry)) {
                JSONObject metadata = new JSONObject(new String(
                        readLimited(input, MAX_METADATA_BYTES), StandardCharsets.UTF_8));
                return MOD_ID.equals(metadata.optString("id", ""));
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean safeRelativePath(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith("/") || raw.contains("\\")
                || raw.indexOf('\0') >= 0) return false;
        for (String part : raw.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        }
        return raw.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > limit) throw new IOException("Metadados Fabric excedem o limite.");
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
}
