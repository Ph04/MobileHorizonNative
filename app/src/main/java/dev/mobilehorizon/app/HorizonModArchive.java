package dev.mobilehorizon.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only validation for Horizon .hmod packages.
 *
 * Mirrors the manifest constraints in crates/horizon-mods/src/manifest.rs from
 * the PC launcher. The package is never extracted while validating it.
 */
final class HorizonModArchive {
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final long MAX_WASM_BYTES = 32L * 1024L * 1024L;
    private static final Set<String> ALLOWED_KEYS = new HashSet<>(Arrays.asList(
            "id", "name", "version", "description", "authors", "api",
            "side", "capabilities", "namespaces", "experimental"));
    private static final Set<String> ALLOWED_CAPABILITIES = new HashSet<>(Arrays.asList(
            "game-read", "chat", "hud", "storage", "command", "registries",
            "ui", "render", "input", "players", "world", "recipes"));
    private static final Set<String> EXPERIMENTAL_CAPABILITIES = new HashSet<>(
            Arrays.asList("ui", "render", "input", "recipes"));
    private static final Pattern KEY = Pattern.compile("(?m)^\\s*([A-Za-z0-9_-]+)\\s*=");
    private static final Pattern QUOTED_VALUE = Pattern.compile(
            "(?m)^\\s*%s\\s*=\\s*(['\"])(.*?)\\1\\s*$");
    private static final Pattern ARRAY_START = Pattern.compile(
            "(?ms)^\\s*%s\\s*=\\s*\\[(.*?)\\]");
    private static final Pattern STRING_TOKEN = Pattern.compile("(['\"])(.*?)\\1");

    private HorizonModArchive() { }

    /** Returns the manifest ID if the archive follows PC Horizon's package rules. */
    static String readId(File archive) throws IOException {
        if (archive == null || !archive.isFile()
                || !archive.getName().toLowerCase(Locale.ROOT).endsWith(".hmod")) {
            return null;
        }

        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry manifestEntry = zip.getEntry("horizon-mod.toml");
            if (manifestEntry == null || manifestEntry.isDirectory()) return null;

            String manifest;
            try (InputStream input = zip.getInputStream(manifestEntry)) {
                manifest = decodeUtf8(readLimited(input, MAX_MANIFEST_BYTES));
            }

            Manifest parsed = parseManifest(manifest);
            if (parsed == null) return null;

            ZipEntry wasm = zip.getEntry("mod.wasm");
            if (wasm != null && !wasm.isDirectory()) {
                if (wasm.getSize() > MAX_WASM_BYTES) return null;
                try (InputStream input = zip.getInputStream(wasm)) {
                    drainLimited(input, MAX_WASM_BYTES);
                }
            } else if (!parsed.capabilities.contains("registries")) {
                // PC Horizon allows a code-free package only for content/registry mods.
                return null;
            }
            return parsed.id;
        } catch (java.util.zip.ZipException invalidArchive) {
            return null;
        }
    }

    private static final class Manifest {
        final String id;
        final Set<String> capabilities;

        Manifest(String id, Set<String> capabilities) {
            this.id = id;
            this.capabilities = capabilities;
        }
    }

    private static Manifest parseManifest(String raw) {
        String text = stripComments(raw);
        for (String key : ALLOWED_KEYS) {
            // Presence is checked below for required fields; this set documents
            // and enforces the same deny_unknown_fields contract as PC Horizon.
        }
        Matcher keys = KEY.matcher(text);
        while (keys.find()) {
            if (!ALLOWED_KEYS.contains(keys.group(1))) return null;
        }

        String id = scalar(text, "id");
        String name = scalar(text, "name");
        String version = scalar(text, "version");
        String api = scalar(text, "api");
        String side = scalar(text, "side");
        if (side == null) side = "client";

        if (id == null || !id.matches("[a-z0-9_]{1,64}")
                || name == null || name.trim().isEmpty()
                || version == null || version.trim().isEmpty()
                || api == null || !Arrays.asList("0.1", "0.2", "0.3").contains(api)
                || !Arrays.asList("client", "server", "both").contains(side)) {
            return null;
        }

        List<String> capabilityList = stringArray(text, "capabilities");
        List<String> experimentalList = stringArray(text, "experimental");
        List<String> namespaces = stringArray(text, "namespaces");
        if (capabilityList == null || experimentalList == null || namespaces == null) {
            return null;
        }

        Set<String> capabilities = new HashSet<>(capabilityList);
        Set<String> experimental = new HashSet<>(experimentalList);
        if (!ALLOWED_CAPABILITIES.containsAll(capabilities)
                || !ALLOWED_CAPABILITIES.containsAll(experimental)) {
            return null;
        }

        boolean server = "server".equals(side);
        if (server != "0.3".equals(api)) return null;
        if (capabilities.contains("registries") && !"0.2".equals(api)) return null;
        if (server && containsAny(capabilities, "game-read", "chat", "hud", "command",
                "registries", "ui", "render", "input", "recipes")) return null;
        if (!server && containsAny(capabilities, "players", "world")) return null;
        if (!experimental.containsAll(intersection(capabilities, EXPERIMENTAL_CAPABILITIES))) {
            return null;
        }
        if (!namespaces.isEmpty() && !capabilities.contains("registries")) return null;

        Set<String> seenNamespaces = new HashSet<>();
        for (String namespace : namespaces) {
            if (!namespace.matches("[a-z0-9_.-]{1,64}")
                    || "minecraft".equals(namespace)
                    || "c".equals(namespace)
                    || "horizon".equals(namespace)
                    || id.equals(namespace)
                    || !seenNamespaces.add(namespace)) {
                return null;
            }
        }
        return new Manifest(id, capabilities);
    }

    private static boolean containsAny(Set<String> values, String... candidates) {
        for (String candidate : candidates) if (values.contains(candidate)) return true;
        return false;
    }

    private static Set<String> intersection(Set<String> first, Set<String> second) {
        Set<String> result = new HashSet<>(first);
        result.retainAll(second);
        return result;
    }

    private static String scalar(String text, String key) {
        Pattern pattern = Pattern.compile(String.format(Locale.ROOT,
                "(?m)^\\s*%s\\s*=\\s*(['\"])(.*?)\\1\\s*$",
                Pattern.quote(key)));
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(2) : null;
    }

    /** Parses simple TOML string arrays, including multiline arrays and trailing commas. */
    private static List<String> stringArray(String text, String key) {
        Matcher array = Pattern.compile(String.format(Locale.ROOT,
                "(?ms)^\\s*%s\\s*=\\s*\\[(.*?)\\]",
                Pattern.quote(key))).matcher(text);
        if (!array.find()) return containsKey(text, key) ? null : new ArrayList<>();
        String body = array.group(1);
        Matcher values = STRING_TOKEN.matcher(body);
        List<String> result = new ArrayList<>();
        while (values.find()) result.add(values.group(2));
        String residue = STRING_TOKEN.matcher(body).replaceAll("")
                .replace(",", "").trim();
        if (!residue.isEmpty()) return null;
        return result;
    }

    private static boolean containsKey(String text, String key) {
        return Pattern.compile(String.format(Locale.ROOT, "(?m)^\\s*%s\\s*=",
                Pattern.quote(key))).matcher(text).find();
    }

    /** Removes TOML comments without stripping # characters inside quoted strings. */
    private static String stripComments(String text) {
        StringBuilder result = new StringBuilder(text.length());
        char quote = 0;
        boolean escaped = false;
        boolean comment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (comment) {
                if (c == '\\n') {
                    comment = false;
                    result.append(c);
                }
                continue;
            }
            if (quote != 0) {
                result.append(c);
                if (quote == '"' && c == '\\\\' && !escaped) {
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

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maximum) {
                throw new IOException("horizon-mod.toml excede 1 MiB.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException("horizon-mod.toml não está em UTF-8 válido.", invalidUtf8);
        }
    }

    private static void drainLimited(InputStream input, long maximum) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maximum) throw new IOException("mod.wasm excede 32 MiB.");
        }
    }
}
