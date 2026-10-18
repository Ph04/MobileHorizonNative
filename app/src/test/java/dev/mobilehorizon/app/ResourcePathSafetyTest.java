package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ResourcePathSafetyTest {
    @Test
    public void acceptsNormalRelativeAssetNames() {
        assertTrue(HorizonResourceConverter.isSafeAssetPath("minecraft/lang/en_us.json"));
        assertTrue(HorizonResourceConverter.isSafeAssetPath("minecraft/textures/block/stone.png"));
        assertTrue(OfficialMinecraftInstaller.isSafeRelativePath("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar"));
    }

    @Test
    public void rejectsTraversalAbsoluteAndPlatformSpecificPaths() {
        assertFalse(HorizonResourceConverter.isSafeAssetPath("../outside.png"));
        assertFalse(HorizonResourceConverter.isSafeAssetPath("minecraft/../../outside.png"));
        assertFalse(HorizonResourceConverter.isSafeAssetPath("/absolute/file"));
        assertFalse(HorizonResourceConverter.isSafeAssetPath("C:/private/file"));
        assertFalse(HorizonResourceConverter.isSafeAssetPath("minecraft:textures/stone.png"));
        assertFalse(OfficialMinecraftInstaller.isSafeRelativePath("assets//empty.png"));
        assertFalse(OfficialMinecraftInstaller.isSafeRelativePath("assets/./file.png"));
        assertFalse(OfficialMinecraftInstaller.isSafeRelativePath("assets/../file.png"));
        assertFalse(OfficialMinecraftInstaller.isSafeRelativePath("assets\\\\file.png"));
    }
}
