package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OfficialManifestHashTest {
    @Test
    public void acceptsCurrentManifestHashWhenAdvertisedByGlobalManifest() {
        String hash = "61fff1316bd50312624a858bbb5eb18fa45208ed";
        assertTrue(OfficialMinecraftInstaller.matchesAdvertisedVersionSha1(hash, hash));
    }

    @Test
    public void acceptsCaseDifferencesInHexSha1() {
        assertTrue(OfficialMinecraftInstaller.matchesAdvertisedVersionSha1(
                "61FFF1316BD50312624A858BBB5EB18FA45208ED",
                "61fff1316bd50312624a858bbb5eb18fa45208ed"));
    }

    @Test
    public void rejectsIncorrectHashOrMalformedMetadata() {
        assertFalse(OfficialMinecraftInstaller.matchesAdvertisedVersionSha1(
                "61fff1316bd50312624a858bbb5eb18fa45208ed",
                "11f1aefe473eefba0af62e3286c33b692807b2eb"));
        assertFalse(OfficialMinecraftInstaller.matchesAdvertisedVersionSha1("not-a-sha1", "not-a-sha1"));
        assertFalse(OfficialMinecraftInstaller.matchesAdvertisedVersionSha1(null, null));
    }
}
