package dev.mobilehorizon.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public final class MinecraftServerListTest {
    @Test public void migratesOldGzipAndWritesPcCompatibleUtf8Nbt() throws Exception {
        File folder = Files.createTempDirectory("mh-servers-gzip").toFile();
        File file = new File(folder, "servers.dat");
        byte[] originalRaw = makeServers(List.of(
                server("Servidor antigo", "play.example:25565", false, 7123)));
        byte[] originalGzip = gzip(originalRaw);
        Files.write(file.toPath(), originalGzip);

        MinecraftServerList.merge(file,
                List.of(new MinecraftServerList.Server("Servidor São José ☕", "PLAY.EXAMPLE")),
                Collections.emptySet());

        byte[] written = Files.readAllBytes(file.toPath());
        assertEquals("Root NBT must be uncompressed TAG_Compound", 10, written[0] & 0xff);
        assertFalse("The app must not rewrite the file as GZIP",
                (written[0] & 0xff) == 0x1f && (written[1] & 0xff) == 0x8b);
        assertArrayEquals(originalGzip,
                Files.readAllBytes(new File(folder, "servers.dat_old").toPath()));

        List<Map<String, Object>> entries = readServers(written);
        assertEquals(1, entries.size());
        Map<String, Object> entry = entries.get(0);
        assertEquals("Servidor São José ☕", entry.get("name"));
        assertEquals("PLAY.EXAMPLE", entry.get("ip"));
        assertEquals((byte) 0, entry.get("hidden"));
        assertEquals((byte) 1, entry.get("mobilehorizonFavorite"));
        assertEquals(7123, entry.get("unknownNumber"));
    }

    @Test public void removesOnlyOldLauncherFavoritesAndKeepsManualAndHiddenServers() throws Exception {
        File folder = Files.createTempDirectory("mh-servers-reconcile").toFile();
        File file = new File(folder, "servers.dat");
        byte[] original = makeServers(List.of(
                server("Manual", "manual.example", false, 10),
                server("Old favorite", "old.example", false, 20),
                server("Direct connection", "direct.example", true, 30)));
        Files.write(file.toPath(), original);

        MinecraftServerList.merge(file,
                List.of(new MinecraftServerList.Server("New favorite", "new.example")),
                Set.of("old.example"));

        List<Map<String, Object>> entries = readServers(Files.readAllBytes(file.toPath()));
        assertEquals(3, entries.size());
        assertEquals("Manual", entries.get(0).get("name"));
        assertEquals("New favorite", entries.get(1).get("name"));
        assertEquals((byte) 1, entries.get(1).get("mobilehorizonFavorite"));
        assertEquals("Direct connection", entries.get(2).get("name"));
        assertEquals((byte) 1, entries.get(2).get("hidden"));
        assertTrue(new File(folder, "servers.dat_old").isFile());
    }

    private static Map<String, Object> server(String name, String ip, boolean hidden, int unknown) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("ip", ip);
        value.put("hidden", (byte) (hidden ? 1 : 0));
        value.put("unknownNumber", unknown);
        return value;
    }

    private static byte[] makeServers(List<Map<String, Object>> servers) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            writeString(out, "");
            out.writeByte(9);
            writeString(out, "servers");
            out.writeByte(10);
            out.writeInt(servers.size());
            for (Map<String, Object> server : servers) {
                writeStringTag(out, "name", (String) server.get("name"));
                writeStringTag(out, "ip", (String) server.get("ip"));
                out.writeByte(1); writeString(out, "hidden"); out.writeByte((Byte) server.get("hidden"));
                out.writeByte(3); writeString(out, "unknownNumber");
                out.writeInt((Integer) server.get("unknownNumber"));
                out.writeByte(0);
            }
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(raw); }
        return bytes.toByteArray();
    }

    private static void writeStringTag(DataOutputStream out, String name, String value) throws Exception {
        out.writeByte(8);
        writeString(out, name);
        writeString(out, value);
    }

    private static void writeString(DataOutputStream out, String value) throws Exception {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(data.length);
        out.write(data);
    }

    private static List<Map<String, Object>> readServers(byte[] bytes) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals(10, in.readUnsignedByte());
            readString(in);
            assertEquals(9, in.readUnsignedByte());
            assertEquals("servers", readString(in));
            assertEquals(10, in.readUnsignedByte());
            int count = in.readInt();
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Map<String, Object> entry = new LinkedHashMap<>();
                while (true) {
                    int type = in.readUnsignedByte();
                    if (type == 0) break;
                    String name = readString(in);
                    switch (type) {
                        case 1: entry.put(name, in.readByte()); break;
                        case 3: entry.put(name, in.readInt()); break;
                        case 8: entry.put(name, readString(in)); break;
                        default: throw new AssertionError("Unexpected test NBT tag: " + type);
                    }
                }
                result.add(entry);
            }
            assertEquals(0, in.readUnsignedByte());
            assertEquals(-1, in.read());
            return result;
        }
    }

    private static String readString(DataInputStream in) throws Exception {
        byte[] data = new byte[in.readUnsignedShort()];
        in.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }
}
