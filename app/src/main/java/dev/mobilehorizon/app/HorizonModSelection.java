package dev.mobilehorizon.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared Horizon launcher selection file. Native and sandboxed .hmod mods use the
 * same mod.<id>:true convention as the desktop launcher.
 */
final class HorizonModSelection {
    private static final String FILE_NAME = "horizon-mods.txt";

    private HorizonModSelection() { }

    static TreeSet<String> readEnabledIds(File instanceHome) throws IOException {
        TreeSet<String> enabled = new TreeSet<>();
        if (instanceHome == null || !instanceHome.isDirectory()) return enabled;
        File file = new File(instanceHome, FILE_NAME);
        if (Files.isSymbolicLink(file.toPath())) {
            throw new IOException(FILE_NAME + " não pode ser um link simbólico.");
        }
        if (!file.isFile()) return enabled;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0 || !"true".equals(line.substring(colon + 1).trim())) continue;
            String key = line.substring(0, colon).trim();
            if (!key.startsWith("mod.")) continue;
            String id = key.substring(4);
            if (validId(id)) enabled.add(id);
        }
        return enabled;
    }

    static boolean isEnabled(File instanceHome, String modId) throws IOException {
        return readEnabledIds(instanceHome).contains(modId);
    }

    static void setEnabled(File instanceHome, String modId, boolean enabled) throws IOException {
        if (!validId(modId)) throw new IOException("ID de mod inválido: " + modId);
        TreeSet<String> ids = readEnabledIds(instanceHome);
        if (enabled) ids.add(modId); else ids.remove(modId);
        writeEnabledIds(instanceHome, ids);
    }

    static void writeEnabledIds(File instanceHome, Set<String> ids) throws IOException {
        if (instanceHome == null || !instanceHome.isDirectory()
                || Files.isSymbolicLink(instanceHome.toPath())) {
            throw new IOException("A pasta da instância não é segura para gravar a seleção de mods.");
        }
        File target = new File(instanceHome, FILE_NAME);
        if (Files.isSymbolicLink(target.toPath())) {
            throw new IOException(FILE_NAME + " não pode ser um link simbólico.");
        }
        StringBuilder content = new StringBuilder();
        for (String id : new TreeSet<>(ids)) {
            if (!validId(id)) throw new IOException("ID de mod inválido: " + id);
            content.append("mod.").append(id).append(":true\n");
        }
        File temporary = new File(instanceHome, "." + FILE_NAME + ".tmp");
        if (Files.isSymbolicLink(temporary.toPath())) {
            throw new IOException("O arquivo temporário da seleção de mods não é seguro.");
        }
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Não foi possível limpar a seleção temporária de mods.");
        }
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(content.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.-]{1,128}");
    }
}
