package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HorizonNativeModManagerIdentityTest {
    @Test
    public void acceptsCurrentEightCharacterCommitPrefix() {
        String version = "horizon-nightly-c67dad0d-o8a869a3e";
        String commit = "67dad0d1" + "a".repeat(32);

        assertTrue(HorizonNativeModManager.baseRuntimeIdentityMatches(
                version, commit, "8a869a3e"));
        assertTrue(HorizonNativeModManager.matchesBaseRuntimeRevision(commit, version));
    }

    @Test
    public void acceptsLongerNineCharacterCommitPrefix() {
        String version = "horizon-nightly-c67dad0d1-o1234abcd";
        String commit = "67dad0d1a" + "b".repeat(31);

        assertTrue(HorizonNativeModManager.baseRuntimeIdentityMatches(
                version, commit, "1234abcd"));
        assertTrue(HorizonNativeModManager.matchesBaseRuntimeRevision(commit, version));
    }

    @Test
    public void rejectsDifferentHorizonRevisionAndOverlay() {
        String version = "horizon-nightly-c67dad0d1-o8a869a3e";
        String baseCommit = "67dad0d1" + "a".repeat(32);
        String differentCommit = "9d979caa" + "0".repeat(32);

        assertFalse(HorizonNativeModManager.baseRuntimeIdentityMatches(
                version, differentCommit, "8a869a3e"));
        assertFalse(HorizonNativeModManager.baseRuntimeIdentityMatches(
                version, baseCommit, "00000000"));
        assertFalse(HorizonNativeModManager.matchesBaseRuntimeRevision(
                differentCommit, version));
    }
}
