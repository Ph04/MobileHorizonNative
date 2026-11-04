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

    @Test public void acceptsJeiStyleHookApiV2BundleWithASeparateTestedHorizonRevision() throws Exception {
        String horizon = "1da354b67a509812c4141b67de2affd6de65c342";
        String mixins = "0d6b103c70633d8fd9443d8d0acd3aadaacedb06";
        File archive = Files.createTempFile("jei-hooks-v2-", ".hnmod").toFile();
        String manifest = "{\"game\":\"26.3\",\"hooks\":2,"
                + "\"horizon\":\"" + horizon + "\","
                + "\"id\":\"jei\",\"mixins\":\"" + mixins + "\","
                + "\"schema\":1,\"version\":\"0.1.0\"}";
        String mixinToml = "[mod]\nid = \"jei\"\nversion = \"0.1.0\"\n"
                + "priority = 100\n[compatibility]\nhorizon_revision = \"" + horizon + "\"\n"
                + "[[module]]\nid = \"runtime\"\nfile = \"crates/horizon-client/src/game.rs\"\n"
                + "name = \"jei\"\nsource = \"runtime.rs\"\n"
                + "[[injection]]\nid = \"frame\"\nkind = \"HEAD\"\n"
                + "target = \"horizon_client::game::native_hooks::Game<'_>::native_frame\"\n"
                + "helper = \"frame.rs\"\n";
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            put(zip, "horizon-native.json", manifest.getBytes(StandardCharsets.UTF_8));
            put(zip, "mixins/jei/horizon-mixin.toml", mixinToml.getBytes(StandardCharsets.UTF_8));
            for (String source : new String[]{"click.rs", "command.rs", "event.rs", "frame.rs",
                    "glue.rs", "list.rs", "runtime.rs", "settings.rs"}) {
                put(zip, "mixins/jei/" + source,
                        ("// fixture for " + source + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            HorizonNativeModArchive.Descriptor descriptor = HorizonNativeModArchive.read(archive);
            assertEquals("jei", descriptor.id);
            assertEquals(Integer.valueOf(2), descriptor.hooks);
            assertEquals("26.3", descriptor.game);
            assertEquals(horizon, descriptor.horizonRevision);
            assertEquals(mixins, descriptor.mixinsRevision);
        } finally {
            archive.delete();
        }
    }

    @Test public void validatesPartialStagingCopyWithoutWeakeningPublicExtensionCheck() throws Exception {
        File source = createArchive("veinminer", HORIZON, MIXINS, null);
        File staged = Files.createTempFile("horizon-native-import-", ".partial").toFile();
        try {
            Files.copy(source.toPath(), staged.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            try {
                HorizonNativeModArchive.read(staged);
                fail("public importer must reject a non-.hnmod filename");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("Selecione um pacote nativo .hnmod"));
            }

            HorizonNativeModArchive.Descriptor descriptor =
                    HorizonNativeModArchive.readStaged(staged);
            assertEquals("veinminer", descriptor.id);
            assertEquals(HORIZON, descriptor.horizonRevision);
        } finally {
            source.delete();
            staged.delete();
        }
    }

    @Test public void directoryEntriesDoNotCountTowardNativeFileLimit() throws Exception {
        File archive = Files.createTempFile("horizon-native-directories-", ".hnmod").toFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            String manifest = "{\"schema\":1,\"id\":\"veinminer\",\"version\":\"0.3.0\","
                    + "\"game\":\"26.3\",\"horizon\":\"" + HORIZON
                    + "\",\"mixins\":\"" + MIXINS + "\"}";
            put(zip, "horizon-native.json", manifest.getBytes(StandardCharsets.UTF_8));
            put(zip, "mixins/veinminer/horizon-mixin.toml",
                    mixinToml("veinminer", "0.3.0", HORIZON).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 600; i++) {
                zip.putNextEntry(new ZipEntry(String.format(
                        java.util.Locale.ROOT, "mixins/veinminer/generated-%03d/", i)));
                zip.closeEntry();
            }
        }
        try {
            HorizonNativeModArchive.Descriptor descriptor = HorizonNativeModArchive.read(archive);
            assertEquals("veinminer", descriptor.id);
        } finally {
            archive.delete();
        }
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
                String entryName = "mod.wasm".equals(override.name)
                        ? "mod.wasm" : "mixins/" + id + "/" + override.name;
                put(zip, entryName, override.bytes);
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
