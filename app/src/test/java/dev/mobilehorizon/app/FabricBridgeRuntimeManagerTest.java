package dev.mobilehorizon.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FabricBridgeRuntimeManagerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void currentApkLibraryIsAcceptedOnlyWhenSizeAndSha256Match() throws Exception {
        File library = temporaryFolder.newFile("liblwjgl.so");
        byte[] elf = new byte[64];
        elf[0] = 0x7f;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        elf[4] = 2;       // ELFCLASS64
        elf[5] = 1;       // ELFDATA2LSB
        elf[18] = (byte) 183; // EM_AARCH64
        elf[19] = 0;
        Files.write(library.toPath(), elf);

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(elf);
        StringBuilder sha256 = new StringBuilder();
        for (byte value : digest) {
            sha256.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }

        assertTrue(FabricBridgeRuntimeManager.isCurrentLwjglLibrary(
                library, elf.length, sha256.toString()));
        assertFalse(FabricBridgeRuntimeManager.isCurrentLwjglLibrary(
                library, elf.length + 1, sha256.toString()));
        assertFalse(FabricBridgeRuntimeManager.isCurrentLwjglLibrary(
                library, elf.length, "0".repeat(64)));
    }

    @Test
    public void nonArm64OrNonElfLibraryIsRejectedEvenWithMatchingHash() throws Exception {
        File library = temporaryFolder.newFile("wrong-abi.so");
        byte[] bytes = new byte[64];
        Files.write(library.toPath(), bytes);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sha256 = new StringBuilder();
        for (byte value : digest) {
            sha256.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }

        assertFalse(FabricBridgeRuntimeManager.isCurrentLwjglLibrary(
                library, bytes.length, sha256.toString()));
    }
}
