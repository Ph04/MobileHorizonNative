package dev.mobilehorizon.app;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class VanillaDirectResourceExporterTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void exportsThreeDirectMapsAfterClientServerParityCheck() throws Exception {
        File planDir = temporary.newFolder("input");
        prepareMatchingSources(planDir);

        VanillaDirectResourceExporter.Summary result =
                VanillaDirectResourceExporter.export(planDir, new AtomicBoolean(false), null);

        assertEquals(3, result.outputFiles);
        assertEquals(3, result.sourceJsonFiles);
        assertEquals("VANILLA_DIRECT_EXPORTS_ONLY_NOT_RUNTIME_RESOURCES", result.state);
        File output = result.outputDirectory;
        assertTrue(new File(output, "block-tags.json").isFile());
        assertTrue(new File(output, "damage-types.json").isFile());
        assertTrue(new File(output, "damage-tags.json").isFile());

        JSONObject blockTags = new JSONObject(read(new File(output, "block-tags.json")));
        assertEquals("{\"replace\":false,\"values\":[\"minecraft:stone\"]}",
                blockTags.getString("minecraft:stone"));
        JSONObject damageTypes = new JSONObject(read(new File(output, "damage-types.json")));
        assertEquals("{\"message_id\":\"fall\",\"exhaustion\":0.0}",
                damageTypes.getString("minecraft:fall"));
        JSONObject damageTags = new JSONObject(read(new File(output, "damage-tags.json")));
        assertEquals("{\"replace\":false,\"values\":[\"minecraft:bypasses_armor\"]}",
                damageTags.getString("minecraft:bypasses_armor"));

        JSONObject manifest = new JSONObject(read(new File(output, "direct-export-manifest.json")));
        assertEquals(3, manifest.getInt("output_files"));
        assertEquals("verified", manifest.getString("client_server_parity"));
        assertEquals("VANILLA_DIRECT_EXPORTS_ONLY_NOT_RUNTIME_RESOURCES",
                manifest.getString("state"));
    }

    @Test
    public void refusesToExportDifferentClientAndServerResourceMaps() throws Exception {
        File planDir = temporary.newFolder("mismatch");
        prepareMatchingSources(planDir);
        write(new File(planDir, "vanilla/client/data/minecraft/tags/block/stone.json"),
                "{\"replace\":false,\"values\":[\"minecraft:granite\"]}");
        File active = new File(planDir, "direct-export");
        assertTrue(active.mkdirs());
        File sentinel = new File(active, "previous-valid-export.txt");
        write(sentinel, "keep");

        try {
            VanillaDirectResourceExporter.export(planDir, new AtomicBoolean(false), null);
            fail("Expected client/server parity error");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("divergem entre client.jar e server.jar"));
            assertTrue(expected.getMessage().contains("minecraft:stone"));
        }
        assertEquals("keep", read(sentinel));
    }

    @Test
    public void missingDataTreeDoesNotCreateAFalseExport() throws Exception {
        File planDir = temporary.newFolder("missing");
        try {
            VanillaDirectResourceExporter.export(planDir, new AtomicBoolean(false), null);
            fail("Expected missing-tree error");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("árvore data/minecraft"));
        }
        assertTrue(!new File(planDir, "direct-export/direct-export-manifest.json").exists());
    }

    private static void prepareMatchingSources(File planDir) throws Exception {
        write(new File(planDir, "vanilla/client/data/minecraft/tags/block/stone.json"),
                "{\"replace\":false,\"values\":[\"minecraft:stone\"]}");
        write(new File(planDir, "vanilla/server/data/minecraft/tags/block/stone.json"),
                "{\"replace\":false,\"values\":[\"minecraft:stone\"]}");
        write(new File(planDir, "vanilla/client/data/minecraft/damage_type/fall.json"),
                "{\"message_id\":\"fall\",\"exhaustion\":0.0}");
        write(new File(planDir, "vanilla/server/data/minecraft/damage_type/fall.json"),
                "{\"message_id\":\"fall\",\"exhaustion\":0.0}");
        write(new File(planDir, "vanilla/client/data/minecraft/tags/damage_type/bypasses_armor.json"),
                "{\"replace\":false,\"values\":[\"minecraft:bypasses_armor\"]}");
        write(new File(planDir, "vanilla/server/data/minecraft/tags/damage_type/bypasses_armor.json"),
                "{\"replace\":false,\"values\":[\"minecraft:bypasses_armor\"]}");
    }

    private static void write(File file, String contents) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("mkdir failed");
        Files.write(file.toPath(), contents.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
