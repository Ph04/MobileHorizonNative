package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.*;

public final class PreparedResourceImporterTest {
    @Test public void acceptsPreparedZipCaseInsensitively() {
        PreparedResourceImporter.validatePreparedZipName("resources.zip");
        PreparedResourceImporter.validatePreparedZipName("RESOURCES.ZIP");
    }

    @Test public void rejectsHnmodAsPreparedResources() {
        try {
            PreparedResourceImporter.validatePreparedZipName("native-module.hnmod");
            fail(".hnmod must use the native module flow");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Módulos nativos"));
        }
    }

    @Test public void rejectsHmodAsPreparedResources() {
        try {
            PreparedResourceImporter.validatePreparedZipName("horizon-content.hmod");
            fail(".hmod must use the Horizon content module flow");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(".hmod"));
        }
    }

    @Test public void rejectsUnknownFileTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> PreparedResourceImporter.validatePreparedZipName("archive.jar"));
    }
}
