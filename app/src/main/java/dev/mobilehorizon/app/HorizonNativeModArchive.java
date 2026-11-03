package dev.mobilehorizon.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only first-pass validator for Horizon Native Mod (.hnmod) source bundles.
 *
 * The authoritative validator is upstream horizon_mods::index::native::Bundle::read,
 * which is run again by the isolated GitHub Actions builder before any native source
 * reaches the compiler. This Android check prevents obviously malformed/oversized
 * archives from being installed into an instance.
 */
final class HorizonNativeModArchive {
    static final String MANIFEST = "horizon-native.json";
    static final long MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L;
    static final long MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L;
    static final int MAX_ENTRIES = 512;
    private static final int MAX_MANIFEST_BYTES = 1 << 20;
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");

    static final class Descriptor {
        final String id;
        final String version;
        final String game;
        final String horizonRevision;
        final String mixinsRevision;
        final Integer hooks;
        final String sha256;
        final long archiveBytes;

        Descriptor(String id, String version, String game, String horizonRevision,
                   String mixinsRevision, Integer hooks, String sha256, long archiveBytes) {
            this.id = id;
            this.version = version;
            this.game = game;
            this.horizonRevision = horizonRevision;
            this.mixinsRevision = mixinsRevision;
            this.hooks = hooks;
            this.sha256 = sha256;
            this.archiveBytes = archiveBytes;
        }

        String displayName() {
            return id + " " + version;
        }
    }

    private HorizonNativeModArchive() { }

    static Descriptor read(File archive) throws IOException {
        if (archive == null || !archive.isFile() || Files.isSymbolicLink(archive.toPath())) {
            throw new IOException("O arquivo .hnmod não existe ou não é um arquivo regular.");
        }
        long archiveSize = archive.length();
        if (archiveSize <= 0 || archiveSize > MAX_ARCHIVE_BYTES) {
            throw new IOException("O .hnmod deve ter entre 1 byte e 64 MiB.");
        }
        if (!"hnmod".equalsIgnoreCase(extension(archive.getName()))) {
            throw new IOException("Selecione um pacote nativo .hnmod. Arquivos .hmod e .jar são formatos diferentes.");
        }

        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST);
            if (manifestEntry == null || manifestEntry.isDirectory()
                    || manifestEntry.getSize() <= 0 || manifestEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw new IOException("O pacote não contém um horizon-native.json válido.");
            }
            byte[] manifestBytes = readBounded(zip.getInputStream(manifestEntry), MAX_MANIFEST_BYTES);
            JSONObject manifest;
            try {
                manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            } catch (RuntimeException error) {
                throw new IOException("horizon-native.json não contém JSON válido.", error);
            }
            rejectUnknownManifestKeys(manifest);

            int schema = manifest.optInt("schema", -1);
            String id = requiredString(manifest, "id");
            String version = requiredString(manifest, "version");
            String game = requiredString(manifest, "game");
            String horizon = requiredString(manifest, "horizon");
            String mixins = requiredString(manifest, "mixins");
            Integer hooks = manifest.has("hooks") && !manifest.isNull("hooks")
                    ? manifest.optInt("hooks", -1) : null;
            if (schema != 1 || !ID.matcher(id).matches()
                    || version.length() > 96 || game.length() > 32
                    || !REVISION.matcher(horizon).matches()
                    || !REVISION.matcher(mixins).matches()
                    || (hooks != null && hooks <= 0)) {
                throw new IOException("Manifesto nativo inválido: schema 1, ID, versão, jogo e revisões SHA-1 são obrigatórios.");
            }

            String mixinPath = "mixins/" + id + "/horizon-mixin.toml";
            ZipEntry mixinEntry = zip.getEntry(mixinPath);
            if (mixinEntry == null || mixinEntry.isDirectory()
                    || mixinEntry.getSize() <= 0 || mixinEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw new IOException("O pacote precisa conter " + mixinPath + ".");
            }
            String mixinToml = new String(
                    readBounded(zip.getInputStream(mixinEntry), MAX_MANIFEST_BYTES),
                    StandardCharsets.UTF_8);
            if (!id.equals(tomlString(mixinToml, "mod", "id"))
                    || !version.equals(tomlString(mixinToml, "mod", "version"))
                    || !horizon.equals(tomlString(mixinToml, "compatibility", "horizon_revision"))) {
                throw new IOException("O manifesto .hnmod e horizon-mixin.toml não possuem o mesmo ID, versão e revisão Horizon.");
            }

            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            long total = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new IOException("O .hnmod contém mais de 512 entradas.");
                }
                String name = entry.getName();
                validateArchivePath(name, entry.isDirectory());
                if (!names.add(name)) {
                    throw new IOException("O .hnmod contém um caminho duplicado: " + name);
                }
                if (entry.isDirectory()) continue;
                if ("mod.wasm".equals(name)) {
                    throw new IOException("WASM não é um .hnmod nativo. O Horizon exige código Rust/mixins.");
                }
                if (!MANIFEST.equals(name)) {
                    String prefix = "mixins/" + id + "/";
                    if (!name.startsWith(prefix)) {
                        throw new IOException("Arquivo inesperado no pacote nativo: " + name);
                    }
                    String relative = name.substring(prefix.length());
                    if (!allowed(relative)) {
                        throw new IOException("Tipo de arquivo não permitido no .hnmod: " + name);
                    }
                }
                long remaining = MAX_UNCOMPRESSED_BYTES - total;
                if (entry.getSize() > remaining) {
                    throw new IOException("O .hnmod expande para mais de 64 MiB.");
                }
                long read = drainBounded(zip.getInputStream(entry), remaining);
                total += read;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw new IOException("O .hnmod expande para mais de 64 MiB.");
                }
            }

            if (!names.contains(MANIFEST) || !names.contains(mixinPath)) {
                throw new IOException("O pacote está incompleto.");
            }
            return new Descriptor(id, version, game, horizon, mixins, hooks,
                    sha256(archive), archiveSize);
        }
    }

    private static void rejectUnknownManifestKeys(JSONObject manifest) throws IOException {
        Set<String> allowed = new HashSet<>();
        allowed.add("schema");
        allowed.add("id");
        allowed.add("version");
        allowed.add("game");
        allowed.add("horizon");
        allowed.add("mixins");
        allowed.add("hooks");
        for (Enumeration<String> keys = manifest.keys(); keys.hasMoreElements();) {
            String key = keys.nextElement();
            if (!allowed.contains(key)) throw new IOException("Campo desconhecido em horizon-native.json: " + key);
        }
    }

    private static String requiredString(JSONObject value, String key) throws IOException {
        Object raw = value.opt(key);
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
            throw new IOException("Campo ausente ou inválido em horizon-native.json: " + key);
        }
        return ((String) raw).trim();
    }

    private static String tomlString(String toml, String section, String key) {
        String current = "";
        for (String raw : toml.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).trim();
                continue;
            }
            if (!section.equals(current)) continue;
            Matcher matcher = Pattern.compile("^" + Pattern.quote(key)
                    + "\\s*=\\s*\"([^\"]*)\"\\s*(?:#.*)?$").matcher(line);
            if (matcher.matches()) return matcher.group(1);
        }
        return null;
    }

    private static boolean allowed(String relative) {
        if (relative.endsWith(".rs") || relative.endsWith(".toml")) return true;
        String[] parts = relative.split("/");
        if (parts.length >= 2 && "data".equals(parts[0])) return true;
        if (parts.length >= 3 && "data".equals(parts[1])) return true;
        if (parts.length <= 2) {
            String name = parts[parts.length - 1];
            return name.startsWith("README") || name.startsWith("LICENSE")
                    || name.startsWith("LICENCE") || name.startsWith("COPYING")
                    || name.startsWith("NOTICE") || ".gitignore".equals(name);
        }
        return false;
    }

    private static void validateArchivePath(String name, boolean directory) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new IOException("Caminho inseguro no .hnmod.");
        }
        String normalized = directory && name.endsWith("/")
                ? name.substring(0, name.length() - 1) : name;
        if (normalized.isEmpty()) throw new IOException("Caminho vazio no .hnmod.");
        for (String part : normalized.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("O .hnmod contém um caminho com travessia de diretório.");
            }
        }
    }

    private static byte[] readBounded(InputStream input, int max) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > max) throw new IOException("Entrada do .hnmod excede o limite permitido.");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static long drainBounded(InputStream input, long max) throws IOException {
        try (InputStream in = input) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > max) throw new IOException("O .hnmod expande para mais de 64 MiB.");
            }
            return total;
        }
    }

    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 não está disponível.", impossible);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
