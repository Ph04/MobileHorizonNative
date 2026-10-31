package dev.mobilehorizon.app;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Per-instance Fabric mod storage and links for the external bridge. */
final class FabricModManager {
    static final long MAX_MOD_JAR_BYTES = 256L * 1024L * 1024L;
    static final String ENABLED_DIRECTORY = "mods";
    static final String DISABLED_DIRECTORY = "mods-disabled";
    static final String BRIDGE_DIRECTORY = "mods-java";

    private FabricModManager() { }

    static File getDirectory(Context context, String instanceId, boolean enabled) throws IOException {
        File home = GameInstanceManager.getInstanceHome(context, instanceId).getCanonicalFile();
        return childDirectory(home, enabled ? ENABLED_DIRECTORY : DISABLED_DIRECTORY);
    }

    /**
     * Moves a mod between the enabled and disabled folders. The move is atomic when
     * supported by the filesystem, so the JAR is never left half-copied.
     */
    static void setEnabled(Context context, String instanceId, File jar, boolean enabled)
            throws IOException {
        File sourceDirectory = getDirectory(context, instanceId, !enabled);
        File destinationDirectory = getDirectory(context, instanceId, enabled);
        File canonicalJar = jar.getCanonicalFile();
        if (Files.isSymbolicLink(jar.toPath())
                || !sourceDirectory.equals(canonicalJar.getParentFile())
                || !canonicalJar.isFile()
                || !canonicalJar.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("O arquivo do mod não pertence à pasta esperada desta instância.");
        }
        if (canonicalJar.length() <= 0 || canonicalJar.length() > MAX_MOD_JAR_BYTES) {
            throw new IOException("Tamanho inválido para o mod " + canonicalJar.getName() + ".");
        }

        File target = new File(destinationDirectory, canonicalJar.getName());
        if (Files.isSymbolicLink(target.toPath())) {
            throw new IOException("O destino do mod usa um link simbólico.");
        }
        if (target.exists()) {
            throw new IOException("Já existe um mod com o nome " + target.getName() + " no destino.");
        }
        try {
            Files.move(canonicalJar.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(canonicalJar.toPath(), target.toPath());
        }
    }

    /**
     * Rebuilds mods-java/ using links only for enabled JARs. Zero enabled mods is
     * valid: Horizon starts normally, but upstream skips JVM/Fabric startup while this folder
     * has no JARs.
     */
    static int prepareInstance(Context context, String instanceId) throws IOException {
        File mods = getDirectory(context, instanceId, true);
        File modsJava = childDirectory(
                GameInstanceManager.getInstanceHome(context, instanceId).getCanonicalFile(),
                BRIDGE_DIRECTORY);

        File[] entries = mods.listFiles();
        if (entries == null) throw new IOException("Não foi possível listar os mods Fabric da instância.");
        List<File> jars = new ArrayList<>();
        for (File entry : entries) {
            if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (Files.isSymbolicLink(entry.toPath()) || !entry.isFile()) {
                throw new IOException("Entrada de mod inválida na pasta mods: " + entry.getName());
            }
            File canonical = entry.getCanonicalFile();
            if (!mods.equals(canonical.getParentFile())) {
                throw new IOException("Um mod Fabric saiu da pasta privada da instância: " + entry.getName());
            }
            if (canonical.length() <= 0 || canonical.length() > MAX_MOD_JAR_BYTES) {
                throw new IOException("Tamanho inválido para o mod Fabric " + entry.getName());
            }
            jars.add(canonical);
        }

        File[] existing = modsJava.listFiles();
        if (existing == null) throw new IOException("Não foi possível listar a pasta interna mods-java.");
        for (File file : existing) {
            if (Files.isSymbolicLink(file.toPath())) {
                if (!file.delete()) {
                    throw new IOException("Não foi possível limpar o link anterior " + file.getName());
                }
            }
        }

        for (File jar : jars) {
            File link = new File(modsJava, jar.getName());
            if (link.exists()) {
                throw new IOException("Já existe um arquivo em mods-java com o nome "
                        + jar.getName() + ". Remova-o antes de atualizar os links da instância.");
            }
            try {
                Os.symlink(jar.getAbsolutePath(), link.getAbsolutePath());
            } catch (Exception error) {
                throw new IOException("Não foi possível vincular " + jar.getName()
                        + " à pasta mods-java sem copiar o arquivo.", error);
            }
        }
        return jars.size();
    }

    private static File childDirectory(File root, String name) throws IOException {
        File requested = new File(root, name);
        if (Files.isSymbolicLink(requested.toPath())) {
            throw new IOException("A pasta " + name + " não pode ser um link simbólico.");
        }
        if (!requested.isDirectory() && !requested.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta " + name + ".");
        }
        File canonical = requested.getCanonicalFile();
        if (!root.equals(canonical.getParentFile())) {
            throw new IOException("A pasta " + name + " saiu do diretório privado da instância.");
        }
        return canonical;
    }
}
