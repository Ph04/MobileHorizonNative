package dev.mobilehorizon.app;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public final class HorizonModArchiveTest {
    @Test public void acceptsPcManifestWithInlineCommentsAndMultilineCapabilities() throws Exception {
        File archive = createArchive("example_mod",
                "api = \"0.2\" # content mod API\n"
                        + "capabilities = [\n  \"registries\", # enables content\n]\n",
                true, false);
        assertEquals("example_mod", HorizonModArchive.readId(archive));
    }

    @Test public void rejectsContentOnlyPackageWithoutRegistries() throws Exception {
        File archive = createArchive("example_mod",
                "api = \"0.2\"\ncapabilities = [\"hud\"]\n", true, false);
        assertNull(HorizonModArchive.readId(archive));
    }

    @Test public void rejectsRegistryContentUsingCodeOnlyApi() throws Exception {
        File archive = createArchive("example_mod",
                "api = \"0.1\"\ncapabilities = [\"registries\"]\n", true, false);
        assertNull(HorizonModArchive.readId(archive));
    }

    @Test public void rejectsIdsOutsidePcManifestRules() throws Exception {
        File archive = createArchive("Example-Mod",
                "api = \"0.2\"\ncapabilities = [\"registries\"]\n", true, false);
        assertNull(HorizonModArchive.readId(archive));
    }

    @Test public void rejectsContentOnlyArchiveWithoutAssetsOrData() throws Exception {
        File archive = createArchive("empty_mod",
                "api = \"0.2\"\ncapabilities = [\"registries\"]\n", false, false);
        assertNull(HorizonModArchive.readId(archive));
    }

    @Test public void acceptsCodeModWithWasm() throws Exception {
        File archive = createArchive("example_code",
                "api = \"0.1\"\ncapabilities = []\n", false, true);
        assertEquals("example_code", HorizonModArchive.readId(archive));
    }

    @Test public void acceptsValidTomlLiteralStringsAndRejectsUnknownManifestKeys() throws Exception {
        File valid = createArchive("literal_mod",
                "name = 'Literal mod'\\nversion = '1.0.0'\\n"
                        + "api = '0.2'\\ncapabilities = ['registries']\\n",
                true, false);
        assertEquals("literal_mod", HorizonModArchive.readId(valid));

        File invalid = createArchive("unknown_mod",
                "api = \"0.2\"\\ncapabilities = [\"registries\"]\\n"
                        + "unsupported_field = \"not accepted by PC Horizon\"\\n",
                true, false);
        assertNull(HorizonModArchive.readId(invalid));
    }

    private static File createArchive(String id, String extra, boolean content, boolean wasm)
            throws Exception {
        File archive = Files.createTempFile("horizon-mod-", ".hmod").toFile();
        String manifest = "id = \"" + id + "\" # stable id\n"
                + "name = \"Example Mod\"\n"
                + "version = \"1.0.0\"\n"
                + extra;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            put(zip, "horizon-mod.toml", manifest.getBytes(StandardCharsets.UTF_8));
            if (content) put(zip, "data/" + id.toLowerCase() + "/items/example.json",
                    "{}".getBytes(StandardCharsets.UTF_8));
            if (wasm) put(zip, "mod.wasm", new byte[]{0, 97, 115, 109});
        }
        return archive;
    }

    private static void put(ZipOutputStream zip, String name, byte[] value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value);
        zip.closeEntry();
    }
}
