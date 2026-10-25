package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HorizonRuntimeUpdateServiceTest {
    @Test
    public void recognizesSemanticVersionTags() {
        assertTrue(HorizonRuntimeUpdateService.isValidUpstreamTag("v0.2.57"));
        assertEquals("v0.2.57",
                HorizonRuntimeUpdateService.upstreamTagFromRuntimeVersion(
                        "horizon-v0.2.57-c1234abcd-o89abcdef"));
    }

    @Test
    public void recognizesCurrentOfficialSemanticRelease() {
        assertTrue(HorizonRuntimeUpdateService.isValidUpstreamTag("v0.2.59"));
        assertEquals("v0.2.59",
                HorizonRuntimeUpdateService.upstreamTagFromRuntimeVersion(
                        "horizon-v0.2.59-c1234abcd-o89abcdef"));
    }

    @Test
    public void recognizesDateBasedHorizonReleaseTags() {
        assertTrue(HorizonRuntimeUpdateService.isValidUpstreamTag("v2026.10.22.07"));
        assertEquals("v2026.10.22.07",
                HorizonRuntimeUpdateService.upstreamTagFromRuntimeVersion(
                        "horizon-v2026.10.22.07-c1234abcd-o89abcdef"));
    }

    @Test
    public void rejectsLauncherTagsAndMalformedRuntimeIds() {
        assertFalse(HorizonRuntimeUpdateService.isValidUpstreamTag("launcher@0.2.7"));
        assertEquals("",
                HorizonRuntimeUpdateService.upstreamTagFromRuntimeVersion(
                        "horizon-v2026.10.22.07-c1234-o89abcdef"));
        assertEquals("",
                HorizonRuntimeUpdateService.upstreamTagFromRuntimeVersion(
                        "horizon-launcher@0.2.7-c1234abcd-o89abcdef"));
    }

    @Test
    public void onlyTreatsRuntimeForExactOfficialTagAsCurrent() {
        String runtimeV266 = "horizon-v0.2.66-c1234abcd-o89abcdef";
        String runtimeV265 = "horizon-v0.2.65-c1234abc0-o89abcdef";

        assertTrue(HorizonRuntimeUpdateService.runtimeVersionMatchesRelease(runtimeV266, "v0.2.66"));
        assertFalse(HorizonRuntimeUpdateService.runtimeVersionMatchesRelease(runtimeV265, "v0.2.66"));
        assertFalse(HorizonRuntimeUpdateService.runtimeVersionMatchesRelease(runtimeV266, "v0.2.65"));
        assertFalse(HorizonRuntimeUpdateService.runtimeVersionMatchesRelease(runtimeV266, "not-a-release"));
    }

    @Test
    public void comparesPublicationTimeInsteadOfDifferentTagSchemes() {
        String olderSemanticRelease = "2026-10-25T07:15:00Z";
        String newerDateRelease = "2026-10-25T13:47:23Z";
        assertTrue(HorizonRuntimeUpdateService.comparePublicationDates(
                newerDateRelease, olderSemanticRelease) > 0);
        assertTrue(HorizonRuntimeUpdateService.comparePublicationDates(
                olderSemanticRelease, newerDateRelease) < 0);
        assertEquals(0, HorizonRuntimeUpdateService.comparePublicationDates(
                newerDateRelease, newerDateRelease));
    }

    @Test
    public void rejectsTagsThatWouldExceedRuntimeVersionLengthLimit() {
        String tooLong = "v1." + "1".repeat(60);
        assertFalse(HorizonRuntimeUpdateService.isValidUpstreamTag(tooLong));
    }

}
