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

/**
 * Verifies and stages the official Fabric Loader artifact outside mods/.
 * This class deliberately does not launch Knot or claim that a Fabric profile is playable.
 */
final class FabricLoaderRuntimeInstaller {
    static final String VERSION = "0.19.5";
    private static final String ASSET_JAR = "fabric-loader/fabric-loader-" + VERSION + ".jar";
    private static final String ASSET_LOCK = "fabric-loader-runtime.json";
    private static final long MAX_JAR_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final Set<String> REQUIRED_CLASSES = new HashSet<>();

    static {
        REQUIRED_CLASSES.add("net/fabricmc/loader/impl/launch/knot/KnotClient.class");
        REQUIRED_CLASSES.add("net/fabricmc/loader/impl/game/GameProvider.class");
        REQUIRED_CLASSES.add("net/fabricmc/loader/api/FabricLoader.class");
    }

    private FabricLoaderRuntimeInstaller() {}

    static File ensureInstalled(Context context) throws Exception {
        AssetManager assets = context.getAssets();
        JSONObject lock = readLock(assets);
        String expectedHash = lock.optString("sha256", "").toLowerCase(Locale.ROOT);
        if (!("net.fabricmc:fabric-loader:" + VERSION).equals(lock.optString("artifact", ""))
                || !VERSION.equals(lock.optString("loader_version", ""))
                || !"26.3".equals(lock.optString("target_minecraft_version", ""))
                || !"bundled_verified_not_launched".equals(lock.optString("execution_status", ""))
                || !expectedHash.matches("[0-9a-f]{64}")) {
            throw new IOException("O pacote oficial Fabric Loader não passou na validação do manifesto.");
        }
        JSONArray required = lock.optJSONArray("required_classes");
        if (required == null || required.length() != REQUIRED_CLASSES.size()) {
            throw new IOException("O manifesto do Fabric Loader não contém a lista de classes esperadas.");
        }
        Set<String> listed = new HashSet<>();
        for (int i = 0; i < required.length(); i++) {
            String name = required.optString(i, "");
            if (!REQUIRED_CLASSES.contains(name) || !listed.add(name)) {
                throw new IOException("O manifesto do Fabric Loader contém uma classe não reconhecida.");
            }
        }
        if (!listed.equals(REQUIRED_CLASSES)) {
            throw new IOException("O manifesto do Fabric Loader está incompleto.");
        }

        File appFiles = context.getFilesDir().getCanonicalFile();
        File gameHome = new File(appFiles, "horizon");
        File loaderDir = new File(new File(gameHome, "fabric-bridge-runtime"), "loader");
        if (!loaderDir.isDirectory() && !loaderDir.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta privada do Fabric Loader.");
        }
        File target = new File(loaderDir, "fabric-loader-" + VERSION + ".jar");
        if (target.isFile() && expectedHash.equals(sha256(target)) && validateJar(target)) return target;
        if (target.exists() && !validateJar(target)) {
            throw new IOException("O destino reservado do Fabric Loader contém um arquivo inválido; ele não foi sobrescrito.");
        }

        File temporary = new File(loaderDir, ".fabric-loader-" + VERSION + ".jar.part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Não foi possível remover a cópia temporária incompleta do Fabric Loader.");
        }
        try {
            copyAsset(assets, temporary);
            if (temporary.length() <= 0 || temporary.length() > MAX_JAR_BYTES
                    || !expectedHash.equals(sha256(temporary)) || !validateJar(temporary)) {
                throw new IOException("O Fabric Loader copiado não corresponde ao artefato oficial verificado.");
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicMoveUnavailable) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!expectedHash.equals(sha256(target)) || !validateJar(target)) {
                throw new IOException("O Fabric Loader instalado falhou na verificação final.");
            }
            return target;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static JSONObject readLock(AssetManager assets) throws Exception {
        try (InputStream input = assets.open(ASSET_LOCK)) {
            return new JSONObject(new String(readLimited(input, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
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
                if (total > MAX_JAR_BYTES) throw new IOException("Fabric Loader excede o limite de tamanho configurado.");
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static boolean validateJar(File jarFile) {
        if (!jarFile.isFile() || jarFile.length() <= 0 || jarFile.length() > MAX_JAR_BYTES) return false;
        try (ZipFile archive = new ZipFile(jarFile)) {
            for (String className : REQUIRED_CLASSES) {
                ZipEntry entry = archive.getEntry(className);
                if (entry == null || entry.isDirectory() || entry.getSize() <= 0
                        || entry.getSize() > 2L * 1024L * 1024L) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > limit) throw new IOException("Manifesto do Fabric Loader excede o limite permitido.");
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
