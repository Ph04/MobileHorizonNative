package dev.mobilehorizon.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Seeds conservative Horizon chunk-streaming limits once on low-memory Android devices.
 * Existing non-default player settings are preserved, and later edits are never overwritten.
 */
final class MobileGraphicsProfile {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long MAX_SETTINGS_BYTES = 512L * 1024L;
    private static final String SETTINGS_FILE = "horizon-graphics.txt";
    private static final String MARKER_FILE = ".mobilehorizon-graphics-profile-v1";

    private MobileGraphicsProfile() { }

    static final class Limits {
        final int chunkUpdates;
        final int uploadBudgetKiB;
        final int workers;
        final boolean disableHdr;

        Limits(int chunkUpdates, int uploadBudgetKiB, int workers) {
            this(chunkUpdates, uploadBudgetKiB, workers, false);
        }

        Limits(int chunkUpdates, int uploadBudgetKiB, int workers, boolean disableHdr) {
            this.chunkUpdates = chunkUpdates;
            this.uploadBudgetKiB = uploadBudgetKiB;
            this.workers = workers;
            this.disableHdr = disableHdr;
        }
    }

    static Limits limitsForRam(long totalRamBytes) {
        if (totalRamBytes <= 0 || totalRamBytes <= 5L * GIB) {
            return new Limits(8, 1024, 2, true);
        }
        if (totalRamBytes <= 7L * GIB) {
            return new Limits(12, 2048, 3);
        }
        return new Limits(16, 4096, 4);
    }

    /**
     * Applies once per game home. Values of 0 mean unlimited/automatic in Horizon;
     * those defaults are bounded for mobile to avoid bursts of chunk upload and workers.
     */
    static boolean applyIfNeeded(File gameHome, long totalRamBytes) throws IOException {
        if (gameHome == null) throw new IOException("Diretório do jogo ausente.");
        File home = gameHome.getCanonicalFile();
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("Não foi possível criar o diretório do jogo.");
        }
        File marker = new File(home, MARKER_FILE);
        if (Files.isSymbolicLink(marker.toPath())) {
            throw new IOException("Marcador do perfil gráfico móvel não é seguro.");
        }
        if (marker.isFile()) return false;

        File settings = new File(home, SETTINGS_FILE);
        if (Files.isSymbolicLink(settings.toPath())) {
            throw new IOException("horizon-graphics.txt não pode ser um link simbólico.");
        }
        if (settings.exists() && (!settings.isFile() || settings.length() > MAX_SETTINGS_BYTES)) {
            throw new IOException("horizon-graphics.txt não é um arquivo regular ou excede 512 KiB.");
        }
        String original = settings.isFile()
                ? new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8)
                : "";
        Limits limits = limitsForRam(totalRamBytes);
        String updated = mergeDefaults(original, limits);
        if (!updated.equals(original)) {
            File temporary = new File(home, "." + SETTINGS_FILE + ".partial");
            if (Files.isSymbolicLink(temporary.toPath())) {
                throw new IOException("Arquivo temporário do perfil gráfico não é seguro.");
            }
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(updated.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), settings.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), settings.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                if (temporary.exists()) temporary.delete();
            }
        }

        byte[] markerBytes = ("version=1\nchunkUpdates=" + limits.chunkUpdates
                + "\nuploadBudgetKiB=" + limits.uploadBudgetKiB
                + "\nworkers=" + limits.workers
                + "\ndisableHdr=" + limits.disableHdr + "\n").getBytes(StandardCharsets.UTF_8);
        File markerTemp = new File(home, "." + MARKER_FILE + ".partial");
        try (FileOutputStream output = new FileOutputStream(markerTemp, false)) {
            output.write(markerBytes);
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(markerTemp.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(markerTemp.toPath(), marker.toPath());
        } finally {
            if (markerTemp.exists()) markerTemp.delete();
        }
        return true;
    }

    static String mergeDefaults(String original, Limits limits) {
        String[] keys = { "chunkUpdates", "uploadBudgetKiB", "workers", "framePacing", "hdr" };
        String[] values = {
                Integer.toString(limits.chunkUpdates),
                Integer.toString(limits.uploadBudgetKiB),
                Integer.toString(limits.workers),
                "efficient",
                "false"
        };
        Set<String> seen = new HashSet<>();
        List<String> output = new ArrayList<>();
        String[] lines = original.split("\\r?\\n", -1);
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                String key = line.substring(0, separator).trim();
                int index = indexOf(keys, key);
                if (index >= 0) {
                    seen.add(key);
                    String current = line.substring(separator + 1).trim();
                    boolean isDefault = current.isEmpty()
                            || ((index <= 2) && "0".equals(current))
                            || (index == 4 && limits.disableHdr
                                    && "true".equalsIgnoreCase(current));
                    if (isDefault) {
                        output.add(keys[index] + ":" + values[index]);
                    } else {
                        output.add(line);
                    }
                    continue;
                }
            }
            if (!line.isEmpty()) {
                output.add(line);
            } else if (!output.isEmpty() && !output.get(output.size() - 1).isEmpty()) {
                output.add("");
            }
        }
        for (int i = 0; i < keys.length; i++) {
            if (!seen.contains(keys[i])) output.add(keys[i] + ":" + values[i]);
        }
        while (!output.isEmpty() && output.get(output.size() - 1).isEmpty()) {
            output.remove(output.size() - 1);
        }
        return String.join("\n", output) + "\n";
    }

    private static int indexOf(String[] values, String key) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(key)) return i;
        }
        return -1;
    }
}
