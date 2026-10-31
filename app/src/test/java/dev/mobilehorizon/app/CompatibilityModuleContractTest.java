package dev.mobilehorizon.app;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CompatibilityModuleContractTest {
    @Test
    public void acceptsSupportedMinecraftVersionNotation() {
        assertTrue(GameProfileManager.isValidMinecraftVersion("26.3"));
        assertTrue(GameProfileManager.isValidMinecraftVersion("1.20.4"));
        assertFalse(GameProfileManager.isValidMinecraftVersion("../26.3"));
        assertFalse(GameProfileManager.isValidMinecraftVersion("26.3-beta"));
        assertFalse(GameProfileManager.isValidMinecraftVersion(""));
    }

    @Test
    public void recognizesThePlannedLoaderIds() throws Exception {
        assertEquals("forge", CompatibilityPluginManager.requireLoaderId("Forge"));
        assertEquals("fabric", CompatibilityPluginManager.requireLoaderId("fabric"));
        assertEquals("neoforge", CompatibilityPluginManager.requireLoaderId("NeoForge"));
        assertEquals("quilt", CompatibilityPluginManager.requireLoaderId("quilt"));
    }

    @Test(expected = IOException.class)
    public void rejectsVanillaAsAnExternalModuleLoader() throws Exception {
        CompatibilityPluginManager.requireLoaderId("vanilla");
    }

    @Test(expected = IOException.class)
    public void rejectsUnsafeModuleIds() throws Exception {
        CompatibilityPluginManager.requireId("../forge");
    }

    @Test(expected = IOException.class)
    public void rejectsPathLikeModuleVersions() throws Exception {
        CompatibilityPluginManager.requireVersion("../1.0");
    }
}
