package dev.mobilehorizon.app;

import org.junit.Test;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public final class MinecraftServerListTest {
    @Test public void mergesFavoritesAndPreservesUnknownNbtFields() throws Exception {
        File folder = Files.createTempDirectory("mh-servers").toFile();
        File file = new File(folder, "servers.dat");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(
                new java.io.FileOutputStream(file)))) {
            out.writeByte(10); // root compound
            out.writeUTF("");
            out.writeByte(8); // an unrelated string tag that must survive the merge
            out.writeUTF("preservedField");
            out.writeUTF("keep-this-value");
            out.writeByte(0); // end compound
        }

        MinecraftServerList.merge(file, Collections.singletonList(
                new MinecraftServerList.Server("Example", "play.example.net:25565")));

        boolean preserved = false;
        boolean serverName = false;
        boolean serverAddress = false;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            assertEquals(10, in.readUnsignedByte());
            in.readUTF();
            while (true) {
                int type = in.readUnsignedByte();
                if (type == 0) break;
                String name = in.readUTF();
                if ("preservedField".equals(name)) {
                    assertEquals(8, type);
                    preserved = "keep-this-value".equals(in.readUTF());
                } else if ("servers".equals(name)) {
                    assertEquals(9, type);
                    int elementType = in.readUnsignedByte();
                    assertEquals(10, elementType);
                    int count = in.readInt();
                    for (int i = 0; i < count; i++) {
                        while (true) {
                            int fieldType = in.readUnsignedByte();
                            if (fieldType == 0) break;
                            String field = in.readUTF();
                            if (fieldType == 8) {
                                String value = in.readUTF();
                                if ("name".equals(field) && "Example".equals(value)) serverName = true;
                                if ("ip".equals(field) && "play.example.net:25565".equals(value)) serverAddress = true;
                            } else if (fieldType == 1) {
                                in.readByte();
                            } else {
                                fail("Unexpected server entry tag type " + fieldType);
                            }
                        }
                    }
                } else {
                    fail("Unexpected root tag " + name);
                }
            }
        }
        assertTrue(preserved);
        assertTrue(serverName);
        assertTrue(serverAddress);
    }
}
