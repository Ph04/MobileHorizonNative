package dev.mobilehorizon.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class BundledServerJarExtractionTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void extractsServerDataFromBundledInnerJar() throws Exception {
        File serverJar = new File(temporary.getRoot(), "server.jar");
        byte[] innerJar = createJar(
                "data/minecraft/dimension_type/overworld.json", "{\"key\":\"overworld\"}",
                "data/minecraft/timeline/day.json", "{\"key\":\"day\"}");
        writeJar(serverJar,
                "META-INF/versions/26.3/server-26.3.jar", innerJar,
                "META-INF/versions.list", "26.3\tignored\tserver-26.3.jar");

        File staging = new File(temporary.getRoot(), "staging");
        assertTrue(staging.mkdirs());
        File destination = new File(staging, "server/data/minecraft");
        HorizonResourceConverter.ServerDataExtraction result =
                HorizonResourceConverter.extractServerDataArchive(
                        serverJar, staging, destination, new AtomicBoolean(false), null);

        assertEquals(2, result.extraction.files);
        assertEquals(
                "server.jar!/META-INF/versions/26.3/server-26.3.jar!/data/minecraft",
                result.sourceEntry);
        assertEquals("{\"key\":\"overworld\"}",
                new String(Files.readAllBytes(new File(destination,
                        "dimension_type/overworld.json").toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(destination, "timeline/day.json").isFile());
        assertTrue(!new File(staging, "server-inner.tmp.jar").exists());
    }

    @Test
    public void stillSupportsFlatServerJarLayout() throws Exception {
        File serverJar = new File(temporary.getRoot(), "flat-server.jar");
        writeJar(serverJar,
                "data/minecraft/dimension_type/overworld.json", "{\"key\":\"overworld\"}",
                "data/minecraft/timeline/day.json", "{\"key\":\"day\"}");

        File staging = new File(temporary.getRoot(), "staging-flat");
        assertTrue(staging.mkdirs());
        File destination = new File(staging, "server/data/minecraft");
        HorizonResourceConverter.ServerDataExtraction result =
                HorizonResourceConverter.extractServerDataArchive(
                        serverJar, staging, destination, new AtomicBoolean(false), null);

        assertEquals(2, result.extraction.files);
        assertEquals("server.jar!/data/minecraft", result.sourceEntry);
        assertTrue(new File(destination, "dimension_type/overworld.json").isFile());
    }

    @Test
    public void reportsActionableErrorWhenNoServerDataOrBundledJarExists() throws Exception {
        File serverJar = new File(temporary.getRoot(), "unexpected-server.jar");
        writeJar(serverJar, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0");

        File staging = new File(temporary.getRoot(), "staging-missing");
        assertTrue(staging.mkdirs());
        try {
            HorizonResourceConverter.extractServerDataArchive(
                    serverJar, staging, new File(staging, "data/minecraft"),
                    new AtomicBoolean(false), null);
            fail("Expected an error for an archive without game data");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("JAR interno"));
            assertTrue(expected.getMessage().contains("META-INF/versions"));
        }
    }

    private static byte[] createJar(String name1, String value1, String name2, String value2)
            throws Exception {
        return createJarBytes(name1, value1.getBytes(StandardCharsets.UTF_8),
                name2, value2.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] createJar(String name1, String value1, String name2, byte[] value2)
            throws Exception {
        return createJarBytes(name1, value1.getBytes(StandardCharsets.UTF_8), name2, value2);
    }

    private static byte[] createJarBytes(String name1, byte[] value1, String name2, byte[] value2)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name1));
            zip.write(value1);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name2));
            zip.write(value2);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void writeJar(File target, Object... nameAndContent) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                String name = (String) nameAndContent[i];
                Object content = nameAndContent[i + 1];
                byte[] bytes = content instanceof byte[]
                        ? (byte[]) content
                        : content.toString().getBytes(StandardCharsets.UTF_8);
                zip.putNextEntry(new ZipEntry(name));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }
}
