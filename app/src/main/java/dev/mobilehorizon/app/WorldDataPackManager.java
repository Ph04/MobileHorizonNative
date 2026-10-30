package dev.mobilehorizon.app;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Per-world vanilla data-pack storage with bounded ZIP inspection and atomic state changes. */
final class WorldDataPackManager {
    private static final String ENABLED = "datapacks";
    private static final String DISABLED = "datapacks-disabled";
    private static final long MAX_ARCHIVE_BYTES = 220L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 20000;
    private static final int MAX_METADATA = 256 * 1024;

    static final class Pack {
        final String fileName;
        final String description;
        final int packFormat;
        final boolean enabled;
        final long sizeBytes;

        Pack(String fileName, String description, int packFormat, boolean enabled, long sizeBytes) {
            this.fileName = fileName;
            this.description = description;
            this.packFormat = packFormat;
            this.enabled = enabled;
            this.sizeBytes = sizeBytes;
        }
    }

    private WorldDataPackManager() {}

    static List<Pack> list(Context context, String worldFolder) throws IOException {
        File world = world(context, worldFolder);
        ArrayList<Pack> result = new ArrayList<>();
        scan(new File(world, ENABLED), true, result);
        scan(new File(world, DISABLED), false, result);
        result.sort(Comparator.comparing((Pack p) -> !p.enabled)
                .thenComparing(p -> p.fileName.toLowerCase(Locale.ROOT)));
        return Collections.unmodifiableList(result);
    }

    static Pack installZip(Context context, String worldFolder, InputStream source,
                           String requestedName) throws IOException {
        if (source == null) throw new IOException("Arquivo de data pack indisponível.");
        String safeName = safePackName(requestedName);
        if (!safeName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("Data packs devem ser arquivos .zip.");
        }
        File world = world(context, worldFolder);
        File destinationRoot = new File(world, ENABLED);
        ensureDirectory(destinationRoot);
        File staging = new File(destinationRoot, ".mh-import-" + System.nanoTime() + ".part");
        long bytes = 0L;
        try (FileOutputStream output = new FileOutputStream(staging, false)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = source.read(buffer)) != -1) {
                bytes += read;
                if (bytes > MAX_ARCHIVE_BYTES) throw new IOException("Data pack excede 220 MiB.");
                output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            staging.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Falha ao copiar data pack.", error);
        }

        Pack metadata;
        try {
            metadata = inspectZip(staging, safeName, true);
            if (metadata == null) throw new IOException("O ZIP não contém pack.mcmeta válido na raiz.");
        } catch (Exception error) {
            staging.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Data pack ZIP inválido.", error);
        }

        File target = uniqueChild(destinationRoot, safeName);
        try {
            move(staging, target);
        } catch (IOException error) {
            staging.delete();
            throw error;
        }
        return new Pack(target.getName(), metadata.description, metadata.packFormat,
                true, target.length());
    }

    static void setEnabled(Context context, String worldFolder, String fileName,
                           boolean enabled) throws IOException {
        String safe = safePackName(fileName);
        File world = world(context, worldFolder);
        File fromRoot = new File(world, enabled ? DISABLED : ENABLED).getCanonicalFile();
        File toRoot = new File(world, enabled ? ENABLED : DISABLED).getCanonicalFile();
        if (fromRoot.equals(toRoot)) throw new IOException("Diretórios de data pack inválidos.");
        ensureDirectory(toRoot);
        File source = safeDirectChild(fromRoot, safe);
        if (!source.exists() || Files.isSymbolicLink(source.toPath())) {
            throw new IOException("Data pack não encontrado ou inseguro.");
        }
        File target = safeDirectChild(toRoot, safe);
        if (target.exists()) throw new IOException("Já existe um data pack com esse nome no destino.");
        move(source, target);
    }

    static void delete(Context context, String worldFolder, String fileName,
                       boolean currentlyEnabled) throws IOException {
        String safe = safePackName(fileName);
        File world = world(context, worldFolder);
        File root = new File(world, currentlyEnabled ? ENABLED : DISABLED).getCanonicalFile();
        File target = safeDirectChild(root, safe);
        if (!target.exists() || Files.isSymbolicLink(target.toPath())) {
            throw new IOException("Data pack não encontrado ou inseguro.");
        }
        deleteTree(target, root);
    }

    private static void scan(File root, boolean enabled, List<Pack> output) throws IOException {
        if (!root.isDirectory()) return;
        if (Files.isSymbolicLink(root.toPath())) throw new IOException("Diretório de data packs é um link simbólico.");
        File[] children = root.listFiles();
        if (children == null) throw new IOException("Não foi possível listar " + root.getName() + ".");
        for (File file : children) {
            if (file.getName().startsWith(".") || Files.isSymbolicLink(file.toPath())) continue;
            if (!file.isFile() && !file.isDirectory()) continue;
            Pack metadata = file.isDirectory()
                    ? inspectDirectory(file, enabled)
                    : inspectZip(file, file.getName(), enabled);
            if (metadata != null) output.add(new Pack(file.getName(), metadata.description,
                    metadata.packFormat, enabled, sizeOf(file)));
        }
    }

    private static Pack inspectDirectory(File directory, boolean enabled) throws IOException {
        File manifest = new File(directory, "pack.mcmeta");
        if (!manifest.isFile() || Files.isSymbolicLink(manifest.toPath())
                || manifest.length() <= 0 || manifest.length() > MAX_METADATA) return null;
        return parseManifest(Files.readAllBytes(manifest.toPath()), directory.getName(), enabled, sizeOf(directory));
    }

    private static Pack inspectZip(File file, String displayName, boolean enabled) throws IOException {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_ARCHIVE_BYTES) {
            throw new IOException("Arquivo ZIP ausente, vazio ou grande demais.");
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry manifest = zip.getEntry("pack.mcmeta");
            if (manifest == null || manifest.isDirectory()
                    || manifest.getSize() <= 0 || manifest.getSize() > MAX_METADATA) {
                return null;
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            long expanded = 0L;
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) throw new IOException("Data pack contém entradas demais.");
                String path = safeArchivePath(entry.getName());
                if (entry.getSize() > 0) {
                    expanded += entry.getSize();
                    if (expanded > MAX_EXPANDED_BYTES) {
                        throw new IOException("Data pack excede 512 MiB descompactados.");
                    }
                }
                if ("pack.mcmeta".equals(path)) continue;
                if (!entry.isDirectory() && !path.startsWith("data/")
                        && !"pack.mcmeta".equals(path) && !"pack.png".equals(path)) {
                    throw new IOException("Data pack possui caminho fora de pack.mcmeta/ e data/: " + path);
                }
            }
            try (InputStream input = zip.getInputStream(manifest)) {
                return parseManifest(readBounded(input, MAX_METADATA), displayName, enabled, file.length());
            }
        }
    }

    private static Pack parseManifest(byte[] bytes, String name, boolean enabled, long size)
            throws IOException {
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONObject pack = root.optJSONObject("pack");
            if (pack == null) throw new IOException("pack.mcmeta não contém o objeto pack.");
            int format = pack.optInt("pack_format", -1);
            if (format < 1) throw new IOException("pack.mcmeta não declara um pack_format válido.");
            Object description = pack.opt("description");
            String text = description == null ? "Data pack" : description.toString();
            if (text.length() > 512) text = text.substring(0, 512);
            return new Pack(name, text, format, enabled, size);
        } catch (JSONException error) {
            throw new IOException("pack.mcmeta contém JSON inválido.", error);
        }
    }

    private static File world(Context context, String folder) throws IOException {
        if (!GameWorldManager.safeFolder(folder)) throw new IOException("Nome do mundo inválido.");
        File home = GameInstanceManager.getSelectedInstanceHome(context).getCanonicalFile();
        File saves = new File(home, "saves").getCanonicalFile();
        if (!home.equals(saves.getParentFile())) throw new IOException("saves/ saiu da instância ativa.");
        File target = new File(saves, folder).getCanonicalFile();
        if (!saves.equals(target.getParentFile()) || !target.isDirectory()
                || Files.isSymbolicLink(target.toPath())
                || !new File(target, "level.dat").isFile()) {
            throw new IOException("Mundo ausente ou fora da instância ativa.");
        }
        return target;
    }

    private static File safeDirectChild(File root, String name) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File child = new File(canonicalRoot, name).getCanonicalFile();
        if (!canonicalRoot.equals(child.getParentFile())) {
            throw new IOException("Caminho saiu do diretório de data packs.");
        }
        return child;
    }

    private static File uniqueChild(File root, String requested) throws IOException {
        int dot = requested.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? requested.length() - 4 : -1;
        String stem = dot > 0 ? requested.substring(0, dot) : requested;
        String extension = dot > 0 ? requested.substring(dot) : ".zip";
        File candidate = safeDirectChild(root, requested);
        for (int index = 2; candidate.exists(); index++) {
            candidate = safeDirectChild(root, stem + " (" + index + ")" + extension);
        }
        return candidate;
    }

    private static String safePackName(String value) throws IOException {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 140 || name.startsWith(".")
                || name.contains("..") || name.contains("/") || name.contains("\\")
                || name.indexOf(':') >= 0 || hasControl(name)) {
            throw new IOException("Nome do data pack inválido.");
        }
        return name;
    }

    private static String safeArchivePath(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 512 || value.startsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || hasControl(value)) {
            throw new IOException("Caminho inválido no data pack.");
        }
        StringBuilder result = new StringBuilder();
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.length() > 180) {
                throw new IOException("Data pack contém caminho inseguro.");
            }
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    private static byte[] readBounded(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > max) throw new IOException("pack.mcmeta é grande demais.");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static long sizeOf(File root) throws IOException {
        ArrayList<File> pending = new ArrayList<>();
        pending.add(root);
        long bytes = 0L;
        int count = 0;
        while (!pending.isEmpty()) {
            File current = pending.remove(pending.size() - 1);
            if (++count > MAX_ENTRIES || Files.isSymbolicLink(current.toPath())) {
                throw new IOException("Data pack contém entradas demais ou link simbólico.");
            }
            if (current.isFile()) {
                bytes += current.length();
                if (bytes > MAX_EXPANDED_BYTES) throw new IOException("Data pack excede 512 MiB.");
            } else if (current.isDirectory()) {
                File[] children = current.listFiles();
                if (children == null) throw new IOException("Diretório de data pack ilegível.");
                Collections.addAll(pending, children);
            }
        }
        return bytes;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (Files.isSymbolicLink(directory.toPath())
                || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Não foi possível criar o diretório " + directory.getName() + ".");
        }
    }

    private static void move(File source, File target) throws IOException {
        if (Files.isSymbolicLink(source.toPath()) || Files.isSymbolicLink(target.toPath())) {
            throw new IOException("Movimento de data pack com link simbólico recusado.");
        }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    private static void deleteTree(File target, File root) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File canonicalTarget = target.getCanonicalFile();
        if (!canonicalTarget.equals(canonicalRoot)
                && !canonicalTarget.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IOException("Exclusão saiu da pasta de data packs.");
        }
        if (Files.isSymbolicLink(target.toPath())) throw new IOException("Link simbólico recusado.");
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Pasta de data pack ilegível.");
            for (File child : children) deleteTree(child, canonicalRoot);
        }
        if (!target.delete()) throw new IOException("Não foi possível excluir " + target.getName() + ".");
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
}
