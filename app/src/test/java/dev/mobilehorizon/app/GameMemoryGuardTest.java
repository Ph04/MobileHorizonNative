package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class GameMemoryGuardTest {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    @Test
    public void fourGigabyteDeviceGetsConservativeJavaBridgeLimits() {
        long ram = 4L * GIB;
        assertEquals(128, GameMemoryGuard.recommendedJavaHeapInitialMiB(ram));
        assertEquals(640, GameMemoryGuard.recommendedJavaHeapMaxMiB(ram));
        assertEquals(2, GameMemoryGuard.recommendedJavaProcessorCount(ram));
        assertEquals(1, GameMemoryGuard.recommendedJavaGcThreadCount(ram));
    }

    @Test
    public void largerDevicesGetGraduallyHigherJavaHeapAndCpuLimits() {
        assertEquals(192, GameMemoryGuard.recommendedJavaHeapInitialMiB(6L * GIB));
        assertEquals(1024, GameMemoryGuard.recommendedJavaHeapMaxMiB(6L * GIB));
        assertEquals(3, GameMemoryGuard.recommendedJavaProcessorCount(6L * GIB));
        assertEquals(1, GameMemoryGuard.recommendedJavaGcThreadCount(6L * GIB));
        assertEquals(256, GameMemoryGuard.recommendedJavaHeapInitialMiB(8L * GIB));
        assertEquals(1536, GameMemoryGuard.recommendedJavaHeapMaxMiB(8L * GIB));
        assertEquals(4, GameMemoryGuard.recommendedJavaProcessorCount(8L * GIB));
        assertEquals(2, GameMemoryGuard.recommendedJavaGcThreadCount(8L * GIB));
        assertEquals(384, GameMemoryGuard.recommendedJavaHeapInitialMiB(12L * GIB));
        assertEquals(2048, GameMemoryGuard.recommendedJavaHeapMaxMiB(12L * GIB));
        assertEquals(4, GameMemoryGuard.recommendedJavaProcessorCount(12L * GIB));
        assertEquals(2, GameMemoryGuard.recommendedJavaGcThreadCount(12L * GIB));
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
