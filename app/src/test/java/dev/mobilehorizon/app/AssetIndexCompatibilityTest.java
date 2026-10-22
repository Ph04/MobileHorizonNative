package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class AssetIndexCompatibilityTest {
    private static final String INDEX_A = "abfaa525f923f807df8b4e4d29c1b5e3a104adbe";
    private static final String INDEX_B = "1e4e4a68dece1c0f51b8f93b45da8fe8da51bcd3";
    private static final String UNKNOWN_INDEX = "ffffffffffffffffffffffffffffffffffffffff";

    @Test
    public void matchingIndexNeedsNoWarning() {
        assertNull(OfficialMinecraftInstaller.assetIndexSha1Warning(INDEX_A, INDEX_A));
    }

    @Test
    public void knownRepublishedIndexWarnsInsteadOfRejecting() {
        String warning = OfficialMinecraftInstaller.assetIndexSha1Warning(INDEX_A, INDEX_B);
        assertTrue(warning.contains(INDEX_A));
        assertTrue(warning.contains(INDEX_B));
        assertTrue(warning.contains("verificação SHA-1 individual"));
    }

    @Test
    public void unknownWellFormedIndexGetsADiagnosticWarning() {
        String warning = OfficialMinecraftInstaller.assetIndexSha1Warning(INDEX_A, UNKNOWN_INDEX);
        assertTrue(warning.contains(UNKNOWN_INDEX));
        assertTrue(warning.contains("cada asset object"));
    }

    @Test
    public void malformedIndexSha1IsStillRejected() {
        try {
            OfficialMinecraftInstaller.assetIndexSha1Warning("not-a-sha1", INDEX_B);
            fail("A malformed expected hash must not be accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("SHA-1 inválido"));
        }
    }
}
