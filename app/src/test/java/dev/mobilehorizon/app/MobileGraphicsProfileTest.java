package dev.mobilehorizon.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MobileGraphicsProfileTest {
    @Test
    public void fourGigabytePhonesUseBoundedChunkWork() {
        MobileGraphicsProfile.Limits limits =
                MobileGraphicsProfile.limitsForRam(4L * 1024L * 1024L * 1024L);
        assertEquals(8, limits.chunkUpdates);
        assertEquals(1024, limits.uploadBudgetKiB);
        assertEquals(2, limits.workers);
    }

    @Test
    public void largerDevicesReceiveGradualLimits() {
        MobileGraphicsProfile.Limits mid =
                MobileGraphicsProfile.limitsForRam(6L * 1024L * 1024L * 1024L);
        assertEquals(12, mid.chunkUpdates);
        assertEquals(3, mid.workers);
        MobileGraphicsProfile.Limits high =
                MobileGraphicsProfile.limitsForRam(8L * 1024L * 1024L * 1024L);
        assertEquals(16, high.chunkUpdates);
        assertEquals(4, high.workers);
    }

    @Test
    public void defaultUnlimitedAndAutomaticValuesAreBounded() {
        String merged = MobileGraphicsProfile.mergeDefaults(
                "renderDistance:12\nchunkUpdates:0\nuploadBudgetKiB:0\nworkers:0\n",
                new MobileGraphicsProfile.Limits(8, 1024, 2));
        assertTrue(merged.contains("chunkUpdates:8"));
        assertTrue(merged.contains("uploadBudgetKiB:1024"));
        assertTrue(merged.contains("workers:2"));
        assertTrue(merged.contains("hdr:false"));
        assertTrue(merged.contains("framePacing:efficient"));
    }

    @Test
    public void fourGigabyteProfileDisablesHdrToReduceGraphicsMemory() {
        MobileGraphicsProfile.Limits limits =
                MobileGraphicsProfile.limitsForRam(4L * 1024L * 1024L * 1024L);
        String merged = MobileGraphicsProfile.mergeDefaults(
                "hdr:true\\nchunkUpdates:0\\nuploadBudgetKiB:0\\nworkers:0\\n", limits);
        assertTrue(merged.contains("hdr:false"));
        assertFalse(merged.contains("hdr:true"));
    }

    @Test
    public void explicitPlayerValuesArePreserved() {
        String merged = MobileGraphicsProfile.mergeDefaults(
                "chunkUpdates:32\nuploadBudgetKiB:4096\nworkers:4\nhdr:true\n",
                new MobileGraphicsProfile.Limits(8, 1024, 2));
        assertTrue(merged.contains("chunkUpdates:32"));
        assertTrue(merged.contains("uploadBudgetKiB:4096"));
        assertTrue(merged.contains("workers:4"));
        assertTrue(merged.contains("hdr:true"));
        assertFalse(merged.contains("hdr:false"));
    }
}
