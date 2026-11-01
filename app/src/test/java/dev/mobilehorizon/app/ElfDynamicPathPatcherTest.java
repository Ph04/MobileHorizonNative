package dev.mobilehorizon.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ElfDynamicPathPatcherTest {
    private static final String OLD_RUNPATH =
            "/data/data/com.termux/files/usr/lib/jvm/java-25-openjdk/lib"
                    + ":/data/data/com.termux/files/usr/lib";
    private static final String NEW_RUNPATH = "$ORIGIN/..:$ORIGIN/../../../..";

    @Test
    public void relocatesTermuxRunpathToRuntimeRelativeDirectories() throws Exception {
        Path root = Files.createTempDirectory("horizon-java25-");
        File javaHome = root.resolve("usr/lib/jvm/java-25-openjdk").toFile();
        File javaLib = new File(javaHome, "lib");
        File library = new File(javaLib, "server/libjvm.so");
        assertTrue(library.getParentFile().mkdirs());
        Files.createDirectories(root.resolve("usr/lib"));

        writeSyntheticElf(library, OLD_RUNPATH);

        assertTrue(ElfDynamicPathPatcher.relocateTermuxRunpath(library, root.toFile()));
        assertEquals(NEW_RUNPATH, ElfDynamicPathPatcher.readRunpath(library));
        assertTrue(ElfDynamicPathPatcher.runpathIncludesDirectory(
                library, new File(root.toFile(), "usr/lib")));
        assertTrue(ElfDynamicPathPatcher.runpathIncludesDirectory(library, javaLib));
    }

    @Test
    public void relocationIsIdempotent() throws Exception {
        Path root = Files.createTempDirectory("horizon-java25-idempotent-");
        File library = root.resolve("usr/lib/jvm/java-25-openjdk/lib/server/libjvm.so").toFile();
        assertTrue(library.getParentFile().mkdirs());
        writeSyntheticElf(library, OLD_RUNPATH);

        assertTrue(ElfDynamicPathPatcher.relocateTermuxRunpath(library, root.toFile()));
        assertFalse(ElfDynamicPathPatcher.relocateTermuxRunpath(library, root.toFile()));
        assertEquals(NEW_RUNPATH, ElfDynamicPathPatcher.readRunpath(library));
    }

    @Test
    public void keepsUnrelatedRunpathEntriesUntouched() throws Exception {
        Path root = Files.createTempDirectory("horizon-java25-unrelated-");
        File library = root.resolve("usr/lib/jvm/java-25-openjdk/lib/server/libjvm.so").toFile();
        assertTrue(library.getParentFile().mkdirs());
        String unrelated = "/system/lib64:/vendor/lib64";
        writeSyntheticElf(library, unrelated);

        assertFalse(ElfDynamicPathPatcher.relocateTermuxRunpath(library, root.toFile()));
        assertEquals(unrelated, ElfDynamicPathPatcher.readRunpath(library));
    }

    private static void writeSyntheticElf(File file, String runpath) throws Exception {
        byte[] data = new byte[2048];
        ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        data[0] = 0x7f;
        data[1] = 'E';
        data[2] = 'L';
        data[3] = 'F';
        data[4] = 2; // ELF64
        data[5] = 1; // little-endian
        data[6] = 1; // ELF version
        bytes.putShort(16, (short) 3); // ET_DYN
        bytes.putShort(18, (short) 183); // EM_AARCH64
        bytes.putInt(20, 1);
        bytes.putLong(32, 64); // e_phoff
        bytes.putShort(54, (short) 56); // e_phentsize
        bytes.putShort(56, (short) 2); // e_phnum

        // PT_LOAD: maps the whole synthetic file at virtual address 0x400000.
        int load = 64;
        bytes.putInt(load, 1);
        bytes.putLong(load + 8, 0);
        bytes.putLong(load + 16, 0x400000);
        bytes.putLong(load + 32, data.length);
        bytes.putLong(load + 40, data.length);
        bytes.putLong(load + 48, 0x1000);

        // PT_DYNAMIC points at the four Elf64_Dyn entries below.
        int dynamic = 64 + 56;
        bytes.putInt(dynamic, 2);
        bytes.putLong(dynamic + 8, 0x200);
        bytes.putLong(dynamic + 16, 0x400200);
        bytes.putLong(dynamic + 32, 64);
        bytes.putLong(dynamic + 40, 64);
        bytes.putLong(dynamic + 48, 8);

        // DT_STRTAB, DT_STRSZ, DT_RUNPATH, DT_NULL.
        putDynamic(bytes, 0x200, 5, 0x400400);
        putDynamic(bytes, 0x210, 10, 512);
        putDynamic(bytes, 0x220, 29, 1);
        putDynamic(bytes, 0x230, 0, 0);

        byte[] path = runpath.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(path, 0, data, 0x401, path.length);
        data[0x401 + path.length] = 0;
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.write(data);
        }
    }

    private static void putDynamic(ByteBuffer bytes, int offset, long tag, long value) {
        bytes.putLong(offset, tag);
        bytes.putLong(offset + 8, value);
    }
}
