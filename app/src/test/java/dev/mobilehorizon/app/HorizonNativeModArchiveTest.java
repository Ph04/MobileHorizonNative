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
    private static final String ID = "example_native";
    private static final String HORIZON = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String MIXINS = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test public void acceptsPcNativeBundleAndReadsCompatibilityPins() throws Exception {
        File archive = create(".hnmod", nativeManifest(ID, HORIZON, MIXINS),
                mixinManifest(ID, "0.3.0", HORIZON), new String[0]);
        HorizonNativeModArchive.Manifest manifest = HorizonNativeModArchive.read(archive);
        assertEquals(ID, manifest.id);
        assertEquals("0.3.0", manifest.version);
        assertEquals("26.3", manifest.game);
        assertEquals(HORIZON, manifest.horizon);
        assertEquals(MIXINS, manifest.mixins);
        assertEquals(Integer.valueOf(1), manifest.hooks);
    }

    @Test public void acceptsValidBundleCopiedToTemporaryPartFile() throws Exception {
        File source = create(".hnmod", nativeManifest(ID, HORIZON, MIXINS),
                mixinManifest(ID, "0.3.0", HORIZON), new String[0]);
        File staging = Files.createTempFile("mh-resource-", ".part").toFile();
        Files.copy(source.toPath(), staging.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        HorizonNativeModArchive.Manifest manifest = HorizonNativeModArchive.read(staging);
        assertEquals(ID, manifest.id);
    }

    @Test public void detectsNativePayloadInsideHmodSoImporterCanRejectIt() throws Exception {
        String content = "id = \"" + ID + "\"\nname = \"Example\"\n"
                + "version = \"1.0\"\napi = \"0.2\"\ncapabilities = []\n";
        File archive = create(".hmod", nativeManifest(ID, HORIZON, MIXINS),
                mixinManifest(ID, "0.3.0", HORIZON),
                new String[]{"horizon-mod.toml", content});
        HorizonNativeModArchive.Manifest manifest = HorizonNativeModArchive.readIfPresent(archive);
        assertNotNull(manifest);
        assertEquals(ID, manifest.id);
    }

    @Test public void rejectsInvalidRevisionAndUnknownManifestKeys() throws Exception {
        File badRevision = create(".hnmod", nativeManifest(ID, "not-a-revision", MIXINS),
                mixinManifest(ID, "0.3.0", "not-a-revision"), new String[0]);
        assertThrows(Exception.class, () -> HorizonNativeModArchive.read(badRevision));

        String unknown = nativeManifest(ID, HORIZON, MIXINS)
                .replace("\"mixins\":", "\"unexpected\":true,\"mixins\":");
        File unknownKey = create(".hnmod", unknown,
                mixinManifest(ID, "0.3.0", HORIZON), new String[0]);
        assertThrows(Exception.class, () -> HorizonNativeModArchive.read(unknownKey));
    }

    @Test public void rejectsPathTraversalAndUnexpectedFiles() throws Exception {
        File traversal = create(".hnmod", nativeManifest(ID, HORIZON, MIXINS),
                mixinManifest(ID, "0.3.0", HORIZON),
                new String[]{"mixins/" + ID + "/data/../escape.bin", "x"});
        assertThrows(Exception.class, () -> HorizonNativeModArchive.read(traversal));

        File binary = create(".hnmod", nativeManifest(ID, HORIZON, MIXINS),
                mixinManifest(ID, "0.3.0", HORIZON),
                new String[]{"mixins/" + ID + "/payload.dll", "binary"});
        assertThrows(Exception.class, () -> HorizonNativeModArchive.read(binary));
    }

    @Test public void rejectsMismatchBetweenNativeAndMixinManifests() throws Exception {
        File archive = create(".hnmod", nativeManifest(ID, HORIZON, MIXINS),
                mixinManifest("other_mod", "0.3.0", HORIZON), new String[0]);
        assertThrows(Exception.class, () -> HorizonNativeModArchive.read(archive));
    }

    @Test public void readIfPresentLeavesOrdinaryContentHmodAlone() throws Exception {
        File archive = Files.createTempFile("ordinary-horizon-mod-", ".hmod").toFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            put(zip, "horizon-mod.toml", "id = \"content_mod\"\n".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(HorizonNativeModArchive.readIfPresent(archive));
    }

    private static String nativeManifest(String id, String horizon, String mixins) {
        return "{\"schema\":1,\"id\":\"" + id + "\",\"version\":\"0.3.0\","
                + "\"game\":\"26.3\",\"horizon\":\"" + horizon + "\","
                + "\"mixins\":\"" + mixins + "\",\"hooks\":1}";
    }

    private static String mixinManifest(String id, String version, String horizon) {
        return "[mod]\nid = \"" + id + "\"\nversion = \"" + version + "\"\n"
                + "[compatibility]\nhorizon_revision = \"" + horizon + "\"\n";
    }

    /** Extras are path/content pairs; the mandatory mixin manifest is always added. */
    private static File create(String extension, String nativeJson, String mixinToml,
                               String[] extras) throws Exception {
        File archive = Files.createTempFile("horizon-native-", extension).toFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            put(zip, "horizon-native.json", nativeJson.getBytes(StandardCharsets.UTF_8));
            put(zip, "mixins/" + ID + "/horizon-mixin.toml", mixinToml.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i + 1 < extras.length; i += 2) {
                put(zip, extras[i], extras[i + 1].getBytes(StandardCharsets.UTF_8));
            }
        }
        return archive;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
