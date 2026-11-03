package dev.mobilehorizon.app;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public final class HorizonNativeModArchiveTest {
    private static final String HORIZON = "a".repeat(40);
    private static final String MIXINS = "b".repeat(40);

    @Test public void acceptsOfficialNativeBundleShape() throws Exception {
        File archive = createArchive("veinminer", HORIZON, MIXINS, null);
        HorizonNativeModArchive.Descriptor descriptor = HorizonNativeModArchive.read(archive);
        assertEquals("veinminer", descriptor.id);
        assertEquals("0.3.0", descriptor.version);
        assertEquals("26.3", descriptor.game);
        assertEquals(HORIZON, descriptor.horizonRevision);
        assertEquals(MIXINS, descriptor.mixinsRevision);
        assertEquals(64, descriptor.sha256.length());
        assertTrue(descriptor.archiveBytes > 0);
        archive.delete();
    }

    @Test public void rejectsPathTraversal() throws Exception {
        File archive = createArchive("veinminer", HORIZON, MIXINS,
                new Entry("../escape.rs", "unsafe".getBytes(StandardCharsets.UTF_8)));
        try {
            HorizonNativeModArchive.read(archive);
            fail("path traversal must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("travessia") 
                    || expected.getMessage().contains("Caminho"));
        } finally {
            archive.delete();
        }
    }

    @Test public void rejectsWasmPayload() throws Exception {
        File archive = createArchive("veinminer", HORIZON, MIXINS,
                new Entry("mod.wasm", new byte[]{0, 97, 115, 109}));
        try {
            HorizonNativeModArchive.read(archive);
            fail("WASM is not a native .hnmod payload");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("WASM"));
        } finally {
            archive.delete();
        }
    }

    @Test public void rejectsMismatchBetweenNativeAndMixinRevisions() throws Exception {
        File archive = createArchive("veinminer", HORIZON, MIXINS,
                new Entry("mixins/veinminer/horizon-mixin.toml",
                        mixinToml("veinminer", "0.3.0", "c".repeat(40))
                                .getBytes(StandardCharsets.UTF_8)));
        try {
            HorizonNativeModArchive.read(archive);
            fail("mismatched revision must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("mesmo ID"));
        } finally {
            archive.delete();
        }
    }

    @Test public void rejectsUnknownManifestKeys() throws Exception {
        File archive = createArchive("veinminer", HORIZON, MIXINS,
                new Entry("horizon-native.json",
                        ("{\"schema\":1,\"id\":\"veinminer\",\"version\":\"0.3.0\","
                                + "\"game\":\"26.3\",\"horizon\":\"" + HORIZON
                                + "\",\"mixins\":\"" + MIXINS
                                + "\",\"unexpected\":true}").getBytes(StandardCharsets.UTF_8)));
        try {
            HorizonNativeModArchive.read(archive);
            fail("unknown manifest keys must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Campo desconhecido"));
        } finally {
            archive.delete();
        }
    }

    @Test public void rejectsInvalidIdsAndUnsupportedExtensions() throws Exception {
        File invalidId = createArchive("../escape", HORIZON, MIXINS, null);
        try {
            HorizonNativeModArchive.read(invalidId);
            fail("invalid id must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Manifesto nativo inválido"));
        } finally {
            invalidId.delete();
        }

        File hmod = Files.createTempFile("native-mod-", ".hmod").toFile();
        try {
            assertThrows(java.io.IOException.class, () -> HorizonNativeModArchive.read(hmod));
        } finally {
            hmod.delete();
        }
    }

    private static File createArchive(String id, String horizon, String mixins, Entry override)
            throws Exception {
        File archive = Files.createTempFile("horizon-native-", ".hnmod").toFile();
        String manifest = "{\"schema\":1,\"id\":\"" + id + "\",\"version\":\"0.3.0\","
                + "\"game\":\"26.3\",\"horizon\":\"" + horizon
                + "\",\"mixins\":\"" + mixins + "\"}";
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            put(zip, "horizon-native.json",
                    override != null && "horizon-native.json".equals(override.name)
                            ? override.bytes : manifest.getBytes(StandardCharsets.UTF_8));
            String toml = mixinToml(id, "0.3.0", horizon);
            put(zip, "mixins/" + id + "/horizon-mixin.toml",
                    override != null && ("mixins/" + id + "/horizon-mixin.toml").equals(override.name)
                            ? override.bytes : toml.getBytes(StandardCharsets.UTF_8));
            if (override != null && !"horizon-native.json".equals(override.name)
                    && !("mixins/" + id + "/horizon-mixin.toml").equals(override.name)) {
                put(zip, "mixins/" + id + "/" + override.name, override.bytes);
            }
            if (override != null && override.name.startsWith("../")) {
                // The traversal entry is at the archive root, as an attacker would supply it.
                // Rebuild below with the exact unsafe ZIP name.
                zip.finish();
            }
        }
        if (override != null && override.name.startsWith("../")) {
            archive.delete();
            archive = Files.createTempFile("horizon-native-traversal-", ".hnmod").toFile();
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
                put(zip, "horizon-native.json", manifest.getBytes(StandardCharsets.UTF_8));
                put(zip, "mixins/" + id + "/horizon-mixin.toml",
                        mixinToml(id, "0.3.0", horizon).getBytes(StandardCharsets.UTF_8));
                put(zip, override.name, override.bytes);
            }
        }
        return archive;
    }

    private static String mixinToml(String id, String version, String horizon) {
        return "[mod]\nid = \"" + id + "\"\nversion = \"" + version
                + "\"\n[compatibility]\nhorizon_revision = \"" + horizon + "\"\n";
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static final class Entry {
        final String name;
        final byte[] bytes;
        Entry(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }
}
