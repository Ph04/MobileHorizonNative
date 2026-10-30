package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GameInstanceManagerTest {
    @Test
    public void validatesStableInternalInstanceIds() {
        assertTrue(GameInstanceManager.isValidInstanceId("default"));
        assertTrue(GameInstanceManager.isValidInstanceId("i-0123456789abcdef0123456789abcdef"));
        assertFalse(GameInstanceManager.isValidInstanceId("../horizon"));
        assertFalse(GameInstanceManager.isValidInstanceId("i-xyz"));
    }

    @Test
    public void normalizesInstanceNames() {
        assertEquals("Sobrevivência", GameInstanceManager.validateName("  Sobrevivência  "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnEmptyInstanceName() {
        GameInstanceManager.validateName("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnOverlongInstanceName() {
        GameInstanceManager.validateName("1234567890123456789012345");
    }
}
