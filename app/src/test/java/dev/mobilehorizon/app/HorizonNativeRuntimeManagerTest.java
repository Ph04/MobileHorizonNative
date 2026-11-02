package dev.mobilehorizon.app;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public final class HorizonNativeRuntimeManagerTest {
    @Test public void computesStableBuildKeyFromSortedNativeArchives() throws Exception {
        File home = Files.createTempDirectory("native-runtime-key-").toFile();
        File directory = new File(home, "horizon-native-mods");
        assertTrue(directory.mkdirs());
        writeBundle(new File(directory, "b.hnmod"), "mod_b");
        writeBundle(new File(directory, "a.hnmod"), "mod_a");

        String key = HorizonNativeRuntimeManager.buildKey(home);
        assertEquals(64, key.length());
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertEquals(key, HorizonNativeRuntimeManager.buildKey(home));
    }

    @Test public void detectsNativeFilesButNotOrdinaryContentHmods() throws Exception {
        File home = Files.createTempDirectory("native-runtime-detect-").toFile();
        File directory = new File(home, "horizon-native-mods");
        assertTrue(directory.mkdirs());
        assertFalse(HorizonNativeRuntimeManager.hasNativeMods(home));
        writeBundle(new File(directory, "native.hnmod"), "native_mod");
        assertTrue(HorizonNativeRuntimeManager.hasNativeMods(home));
    }

    private static void writeBundle(File target, String id) throws Exception {
        String revision = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String mixins = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String json = "{\"schema\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                + "\"game\":\"26.3\",\"horizon\":\"" + revision + "\","
                + "\"mixins\":\"" + mixins + "\",\"hooks\":1}";
        String toml = "[mod]\nid = \"" + id + "\"\nversion = \"1.0.0\"\n"
                + "[compatibility]\nhorizon_revision = \"" + revision + "\"\n";
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            put(zip, "horizon-native.json", json);
            put(zip, "mixins/" + id + "/horizon-mixin.toml", toml);
            put(zip, "mixins/" + id + "/hook.rs", "fn hook() {}\n");
        }
    }

    private static void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
