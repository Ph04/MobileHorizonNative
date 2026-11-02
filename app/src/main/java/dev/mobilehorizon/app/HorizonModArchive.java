package dev.mobilehorizon.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lightweight, read-only validation for Horizon .hmod content archives.
 * The archive format and accepted manifest fields follow horizon-mods::Package::read
 * and Manifest::parse in the PC launcher. No archive entries are extracted here.
 */
final class HorizonModArchive {
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_WASM_BYTES = 32L * 1024L * 1024L;

    private static final Pattern ID = Pattern.compile(
            "(?m)^\\s*id\\s*=\\s*\"([a-z0-9_]{1,64})\"\\s*(?:#.*)?$");
    private static final Pattern API = Pattern.compile(
            "(?m)^\\s*api\\s*=\\s*\"(0\\.1|0\\.2|0\\.3)\"\\s*(?:#.*)?$");
    private static final Pattern CAPABILITIES = Pattern.compile(
            "(?s)\\bcapabilities\\s*=\\s*\\[(.*?)\\]");
    private static final Pattern REGISTRIES = Pattern.compile("\"registries\"");

    private HorizonModArchive() { }

    /** Returns the mod ID when the archive is valid enough to install, otherwise null. */
    static String readId(File archive) throws IOException {
        if (archive == null || !archive.isFile()
                || !archive.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hmod")) {
            return null;
        }

        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry manifest = zip.getEntry("horizon-mod.toml");
            if (manifest == null || manifest.isDirectory()) return null;

            String text;
            try (InputStream input = zip.getInputStream(manifest)) {
                text = decodeUtf8(readLimited(input, MAX_MANIFEST_BYTES));
            }

            Matcher id = ID.matcher(text);
            Matcher api = API.matcher(text);
            if (!id.find() || !api.find()) return null;

            ZipEntry wasm = zip.getEntry("mod.wasm");
            if (wasm != null && !wasm.isDirectory()) {
                if (wasm.getSize() > MAX_WASM_BYTES) return null;
                // Validate the decompressed size too; ZipEntry.getSize() may be unknown.
                try (InputStream input = zip.getInputStream(wasm)) {
                    drainLimited(input, MAX_WASM_BYTES);
                }
                return id.group(1);
            }

            // PC Horizon permits a code-free package only when its manifest declares
            // the registries capability. Its parser accepts TOML comments after values.
            Matcher capabilities = CAPABILITIES.matcher(text);
            if (!capabilities.find() || !REGISTRIES.matcher(capabilities.group(1)).find()) {
                return null;
            }

            boolean hasContent = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.startsWith("assets/") || name.startsWith("data/")) {
                    hasContent = true;
                    break;
                }
            }
            return hasContent ? id.group(1) : null;
        } catch (java.util.zip.ZipException invalidArchive) {
            return null;
        }
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maximum) {
                throw new IOException("horizon-mod.toml excede 64 KiB.");
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
