package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class GameMemoryGuardTest {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    @Test
    public void fourGigabyteDeviceGetsConservativeInitialCaps() {
        long ram = 4L * GIB;
        assertEquals(2, GameMemoryGuard.recommendedWorkerCap(ram));
        assertEquals(6, GameMemoryGuard.recommendedRenderDistanceCap(ram));
    }

    @Test
    public void largerDevicesGetGraduallyHigherCaps() {
        assertEquals(3, GameMemoryGuard.recommendedWorkerCap(6L * GIB));
        assertEquals(8, GameMemoryGuard.recommendedRenderDistanceCap(6L * GIB));
        assertEquals(4, GameMemoryGuard.recommendedWorkerCap(8L * GIB));
        assertEquals(10, GameMemoryGuard.recommendedRenderDistanceCap(8L * GIB));
        assertEquals(6, GameMemoryGuard.recommendedWorkerCap(12L * GIB));
        assertEquals(12, GameMemoryGuard.recommendedRenderDistanceCap(12L * GIB));
    }

    @Test
    public void highPressureUsesBothAvailableMemoryAndProcessPss() {
        long total = 4L * GIB;
        assertEquals(0, GameMemoryGuard.classifyPressure(
                total, 1200L * MIB, 500L * 1024L, 256, false));
        assertEquals(1, GameMemoryGuard.classifyPressure(
                total, 1200L * MIB, 1200L * 1024L, 256, false));
        assertEquals(2, GameMemoryGuard.classifyPressure(
                total, 1200L * MIB, 1500L * 1024L, 256, false));
        assertEquals(3, GameMemoryGuard.classifyPressure(
                total, 1200L * MIB, 1800L * 1024L, 256, false));
    }

    @Test
    public void androidLowMemoryCallbackImmediatelyMeansCritical() {
        assertEquals(3, GameMemoryGuard.classifyPressure(
                4L * GIB, 2L * GIB, 400L * 1024L, 256, true));
    }
}
