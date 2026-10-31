package dev.mobilehorizon.app;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Prepares the external bridge's expected mods-java/ folder without duplicating mod JARs. */
final class FabricModManager {
    static final long MAX_MOD_JAR_BYTES = 256L * 1024L * 1024L;

    private FabricModManager() { }

    static int prepareInstance(Context context, String instanceId) throws IOException {
        File home = GameInstanceManager.getInstanceHome(context, instanceId).getCanonicalFile();
        File mods = childDirectory(home, "mods");
        File modsJava = childDirectory(home, "mods-java");

        File[] entries = mods.listFiles();
        if (entries == null) throw new IOException("Não foi possível listar os mods Fabric da instância.");
        List<File> jars = new ArrayList<>();
        for (File entry : entries) {
            if (!entry.isFile() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            File canonical = entry.getCanonicalFile();
            if (!canonical.getPath().startsWith(mods.getCanonicalPath() + File.separator)
                    || Files.isSymbolicLink(entry.toPath())) {
                throw new IOException("Um mod Fabric aponta para fora da pasta privada da instância: "
                        + entry.getName());
            }
            if (canonical.length() <= 0 || canonical.length() > MAX_MOD_JAR_BYTES) {
                throw new IOException("Tamanho inválido para o mod Fabric " + entry.getName());
            }
            jars.add(canonical);
        }
        if (jars.isEmpty()) {
            throw new IOException("Esta instância Fabric ainda não tem mods .jar. Abra Gerenciar mods Fabric e importe pelo menos um mod.");
        }

        File[] existing = modsJava.listFiles();
        if (existing != null) {
            for (File file : existing) {
                if (Files.isSymbolicLink(file.toPath())) {
                    if (!file.delete()) throw new IOException("Não foi possível atualizar o link do mod " + file.getName());
                }
            }
        }
        for (File jar : jars) {
            File link = new File(modsJava, jar.getName());
            if (link.exists()) {
                if (Files.isSymbolicLink(link.toPath())) {
                    if (!link.delete()) throw new IOException("Não foi possível substituir o link " + jar.getName());
                } else {
                    throw new IOException("Já existe um arquivo em mods-java com o nome "
                            + jar.getName() + ". Remova-o antes de atualizar os links da instância.");
                }
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
