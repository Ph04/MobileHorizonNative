package dev.mobilehorizon.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Read-only validator for Horizon native source bundles (.hnmod and native .hmod).
 *
 * The bundle contains Rust/TOML source, not a loadable Android library. This class
 * never extracts or executes it; it mirrors the desktop horizon_mods::index::native
 * archive contract before the file is stored for a future native build step.
 */
final class HorizonNativeModArchive {
    static final String MANIFEST = "horizon-native.json";
    private static final String MIXIN_MANIFEST = "horizon-mixin.toml";
    private static final String CONTENT_MANIFEST = "horizon-mod.toml";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_NATIVE_FILES = 512;
    private static final long MAX_NATIVE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_CONTENT_BYTES = 512L * 1024L * 1024L;
    private static final Pattern TOML_STRING = Pattern.compile(
            "^([A-Za-z0-9_-]+)\\s*=\\s*(['\"])(.*?)\\2\\s*$");

    static final class Manifest {
        final String id;
        final String version;
        final String game;
        final String horizon;
        final String mixins;
        final Integer hooks;

        Manifest(String id, String version, String game, String horizon,
                 String mixins, Integer hooks) {
            this.id = id;
            this.version = version;
            this.game = game;
            this.horizon = horizon;
            this.mixins = mixins;
            this.hooks = hooks;
        }
    }

    private HorizonNativeModArchive() { }

    /** Returns null when a valid ZIP has no native manifest; malformed native bundles throw. */
    static Manifest readIfPresent(File archive) throws IOException {
        if (archive == null || !archive.isFile()) return null;
        try (ZipFile zip = new ZipFile(archive)) {
            if (zip.getEntry(MANIFEST) == null) return null;
        } catch (ZipException invalidZip) {
            return null;
        }
        return read(archive);
    }

    /** Reads and validates one complete .hnmod or native .hmod bundle. */
    static Manifest read(File archive) throws IOException {
        if (archive == null || !archive.isFile()) {
            throw new IOException("O arquivo do mod nativo não existe.");
        }
        String lowerName = archive.getName().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".hnmod") && !lowerName.endsWith(".hmod")) {
            throw new IOException("Mod nativo precisa usar .hnmod ou .hmod.");
        }

        try (ZipFile zip = new ZipFile(archive)) {
            byte[] manifestBytes = null;
            byte[] contentManifestBytes = null;
            long nativeBytes = 0;
            long contentBytes = 0;
            int nativeFiles = 0;
            Set<String> allNames = new HashSet<>();
            List<String> nativeNames = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                validatePath(name, entry.isDirectory());
                if (entry.isDirectory()) continue;
                if (!allNames.add(name)) {
                    throw new IOException("O pacote contém um caminho duplicado: " + name);
                }
                if (CONTENT_MANIFEST.equals(name)) {
                    contentManifestBytes = readLimited(zip.getInputStream(entry),
                            MAX_MANIFEST_BYTES, CONTENT_MANIFEST);
                    continue;
                }
                if (name.startsWith("assets/") || name.startsWith("data/")) {
                    contentBytes += drainLimited(zip.getInputStream(entry),
                            MAX_CONTENT_BYTES - contentBytes, name);
                    if (contentBytes > MAX_CONTENT_BYTES) {
                        throw new IOException("O conteúdo combinado do mod excede 512 MiB.");
                    }
                    continue;
                }
                if (MANIFEST.equals(name)) {
                    manifestBytes = readLimited(zip.getInputStream(entry), MAX_MANIFEST_BYTES,
                            MANIFEST);
                    nativeBytes += manifestBytes.length;
                    continue;
                }
                if ("mod.wasm".equals(name)) {
                    throw new IOException("mod.wasm não é mais aceito pelo Horizon; mods de código devem usar Rust nativo.");
                }
                nativeFiles++;
                if (nativeFiles > MAX_NATIVE_FILES) {
                    throw new IOException("O mod nativo contém mais de 512 arquivos.");
                }
                long consumed = drainLimited(zip.getInputStream(entry),
                        MAX_NATIVE_BYTES - nativeBytes, name);
                nativeBytes += consumed;
                if (nativeBytes > MAX_NATIVE_BYTES) {
                    throw new IOException("O código do mod nativo excede 64 MiB descompactado.");
                }
                nativeNames.add(name);
            }

            if (manifestBytes == null) {
                throw new IOException("Manifesto horizon-native.json ausente.");
            }
            Manifest manifest = parseManifest(new String(manifestBytes, StandardCharsets.UTF_8));
            if (contentManifestBytes != null) {
                String contentId = tomlString(
                        new String(contentManifestBytes, StandardCharsets.UTF_8), "", "id");
                if (contentId == null || !manifest.id.equals(contentId)) {
                    throw new IOException("horizon-mod.toml e horizon-native.json precisam declarar o mesmo ID.");
                }
            }

            String prefix = "mixins/" + manifest.id + "/";
            String mixinManifestPath = prefix + MIXIN_MANIFEST;
            boolean hasMixinManifest = false;
            for (String name : nativeNames) {
                if (!name.startsWith(prefix)) {
                    throw new IOException("Fonte nativa fora de " + prefix + ": " + name);
                }
                String relative = name.substring(prefix.length());
                if (!allowed(relative)) {
                    throw new IOException("Arquivo não permitido no bundle nativo: " + name);
                }
                if (mixinManifestPath.equals(name)) hasMixinManifest = true;
            }
            if (!hasMixinManifest) {
                throw new IOException("Arquivo mixins/" + manifest.id + "/" + MIXIN_MANIFEST + " ausente.");
            }

            ZipEntry mixinEntry = zip.getEntry(mixinManifestPath);
            byte[] mixinBytes = readLimited(zip.getInputStream(mixinEntry), MAX_MANIFEST_BYTES,
                    MIXIN_MANIFEST);
            String mixinToml = new String(mixinBytes, StandardCharsets.UTF_8);
            String mixinId = tomlString(mixinToml, "mod", "id");
            String mixinVersion = tomlString(mixinToml, "mod", "version");
            String mixinHorizon = tomlString(mixinToml, "compatibility", "horizon_revision");
            if (!manifest.id.equals(mixinId) || !manifest.version.equals(mixinVersion)
                    || !manifest.horizon.equals(mixinHorizon)) {
                throw new IOException("horizon-native.json e horizon-mixin.toml divergem em ID, versão ou revisão Horizon.");
            }
            return manifest;
        } catch (ZipException invalidArchive) {
            throw new IOException("O arquivo não é um ZIP .hnmod válido.", invalidArchive);
        }
    }

    private static Manifest parseManifest(String json) throws IOException {
        try {
            JSONObject object = new JSONObject(json);
            Set<String> allowed = new HashSet<>();
            allowed.add("schema"); allowed.add("id"); allowed.add("version");
            allowed.add("game"); allowed.add("horizon"); allowed.add("mixins");
            allowed.add("hooks");
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!allowed.contains(key)) {
                    throw new IOException("Campo desconhecido em horizon-native.json: " + key);
                }
            }
            if (object.optInt("schema", -1) != 1) {
                throw new IOException("Versão de manifesto nativo não suportada (schema 1 esperado).");
            }
            String id = requiredString(object, "id");
            String version = requiredString(object, "version");
            String game = requiredString(object, "game");
            String horizon = requiredString(object, "horizon");
            String mixins = requiredString(object, "mixins");
            if (!id.matches("[a-z0-9_]{1,64}")) {
                throw new IOException("ID nativo inválido; use letras minúsculas, números e _ (1–64 caracteres).");
            }
            if (!revision(horizon) || !revision(mixins)) {
                throw new IOException("As revisões Horizon e horizon-mixins precisam ser hashes SHA de 40 caracteres.");
            }
            Integer hooks = null;
            if (object.has("hooks") && !object.isNull("hooks")) {
                Object value = object.get("hooks");
                if (!(value instanceof Number)) {
                    throw new IOException("O campo hooks precisa ser um número inteiro positivo.");
                }
                double numeric = ((Number) value).doubleValue();
                int parsed = ((Number) value).intValue();
                if (numeric != parsed || parsed <= 0) {
                    throw new IOException("O campo hooks precisa ser um número inteiro positivo.");
                }
                hooks = parsed;
            }
            return new Manifest(id, version, game, horizon, mixins, hooks);
        } catch (org.json.JSONException error) {
            throw new IOException("horizon-native.json inválido: " + error.getMessage(), error);
        }
    }

    private static String requiredString(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IOException("Campo obrigatório ausente ou vazio em horizon-native.json: " + key);
        }
        return ((String) value).trim();
    }

    private static boolean revision(String value) {
        return value != null && value.matches("[0-9a-f]{40}");
    }

    private static boolean allowed(String rest) {
        if (rest.endsWith(".rs") || rest.endsWith(".toml")) return true;
        String[] parts = rest.split("/", -1);
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

    private static void validatePath(String name, boolean directory) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\\') >= 0
                || name.startsWith("/") || name.indexOf(':') >= 0) {
            throw new IOException("Caminho inválido no pacote nativo.");
        }
        String checked = directory && name.endsWith("/")
                ? name.substring(0, name.length() - 1) : name;
        if (checked.isEmpty()) throw new IOException("Caminho vazio no pacote nativo.");
        String[] parts = checked.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("Caminho inseguro no pacote nativo: " + name);
            }
        }
    }

    /** Reads one simple quoted TOML scalar in the requested table. */
    private static String tomlString(String text, String table, String key) {
        String current = "";
        String value = null;
        String[] lines = stripComments(text).split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).trim();
                continue;
            }
            if (!current.equals(table)) continue;
            Matcher matcher = TOML_STRING.matcher(line);
            if (matcher.matches() && key.equals(matcher.group(1))) {
                if (value != null) return null;
                value = matcher.group(3);
            }
        }
        if (table.isEmpty()) {
            for (String raw : lines) {
                Matcher matcher = TOML_STRING.matcher(raw.trim());
                if (matcher.matches() && key.equals(matcher.group(1))) {
                    if (value != null) return null;
                    value = matcher.group(3);
                }
            }
        }
        return value;
    }

    /** Removes comments while retaining # characters inside quoted TOML strings. */
    private static String stripComments(String text) {
        StringBuilder result = new StringBuilder(text.length());
        char quote = 0;
        boolean escaped = false;
        boolean comment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (comment) {
                if (c == '\n') {
                    comment = false;
                    result.append(c);
                }
                continue;
            }
            if (quote != 0) {
                result.append(c);
                if (quote == '"' && c == '\\' && !escaped) {
                    escaped = true;
                } else {
                    if (c == quote && !escaped) quote = 0;
                    escaped = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                result.append(c);
            } else if (c == '#') {
                comment = true;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static byte[] readLimited(InputStream input, int maximum, String label)
            throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (output.size() + count > maximum) {
                    throw new IOException(label + " excede 1 MiB.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static long drainLimited(InputStream input, long remaining, String name)
            throws IOException {
        try (InputStream in = input) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > remaining) {
                    throw new IOException(name + " ultrapassa o limite descompactado do pacote.");
                }
            }
            return total;
        }
    }
}
