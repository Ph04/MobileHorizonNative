package dev.mobilehorizon.app;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class HorizonPrepareCachePermissionsTest {
    @Test
    public void restoresReadOnlyExporterDirectoriesAndLogsWithoutDeletingCache() throws Exception {
        File root = Files.createTempDirectory("mobilehorizon-prepare-cache").toFile();
        File motion = new File(root, "horizon-client-motion");
        File classes = new File(motion, "classes");
        assertTrue(classes.mkdirs());
        File log = new File(motion, "export.log");
        Files.write(log.toPath(), "previous diagnostic\n".getBytes(StandardCharsets.UTF_8));

        Files.setPosixFilePermissions(motion.toPath(),
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(classes.toPath(),
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(log.toPath(),
                EnumSet.of(PosixFilePermission.OWNER_READ));

        HorizonPrepareRunner.repairWritableCache(root, new AtomicBoolean(false));

        assertOwnerWritableAndSearchable(motion, true);
        assertOwnerWritableAndSearchable(classes, true);
        assertOwnerWritableAndSearchable(log, false);
        assertEquals("previous diagnostic\n",
                new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));

        Files.write(log.toPath(), "recovered diagnostic\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("recovered diagnostic\n",
                new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
        deleteTree(root);
    }

    @Test
    public void refusesSymbolicLinksInsteadOfChangingExternalPaths() throws Exception {
        File root = Files.createTempDirectory("mobilehorizon-prepare-cache-link").toFile();
        File outside = Files.createTempDirectory("mobilehorizon-prepare-outside").toFile();
        File outsideFile = new File(outside, "keep.txt");
        Files.write(outsideFile.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        File link = new File(root, "linked-cache");
        Files.createSymbolicLink(link.toPath(), outside.toPath());

        try {
            HorizonPrepareRunner.repairWritableCache(root, new AtomicBoolean(false));
            fail("The cache repair must not follow symbolic links.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Link simbólico"));
        }

        assertEquals("keep", new String(Files.readAllBytes(outsideFile.toPath()),
                StandardCharsets.UTF_8));
        Files.deleteIfExists(link.toPath());
        deleteTree(root);
        deleteTree(outside);
    }

    private static void assertOwnerWritableAndSearchable(File file, boolean directory)
            throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file.toPath());
        assertTrue("owner read bit missing: " + file, permissions.contains(PosixFilePermission.OWNER_READ));
        assertTrue("owner write bit missing: " + file, permissions.contains(PosixFilePermission.OWNER_WRITE));
        if (directory) {
            assertTrue("owner execute/search bit missing: " + file,
                    permissions.contains(PosixFilePermission.OWNER_EXECUTE));
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}
