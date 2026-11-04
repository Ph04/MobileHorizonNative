package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HorizonNativeModCompatibilityTest {
    private static final String TESTED_HORIZON =
            "1da354b67a509812c4141b67de2affd6de65c342";
    private static final String INSTALLED_HORIZON =
            "67dad0d1a5adca139b426c8114e74a33b376ba64";
    private static final String MIXINS =
            "0d6b103c70633d8fd9443d8d0acd3aadaacedb06";

    @Test
    public void jeiHooksV2IsCompatibleWithLaterInstalledNativeHooksApi() {
        assertTrue(HorizonNativeModManager.isNativeModCompatible(
                "26.3", TESTED_HORIZON, 2, INSTALLED_HORIZON, 359));
    }

    @Test
    public void nativeHookApiMustMeetTheModulesDeclaredMinimum() {
        assertFalse(HorizonNativeModManager.isNativeModCompatible(
                "26.3", TESTED_HORIZON, 2, INSTALLED_HORIZON, 1));
        assertFalse(HorizonNativeModManager.isNativeModCompatible(
                "26.3", TESTED_HORIZON, 3, INSTALLED_HORIZON, 2));
    }

    @Test
    public void moduleWithoutHooksKeepsExactHorizonCommitRequirement() {
        assertTrue(HorizonNativeModManager.isNativeModCompatible(
                "26.3", INSTALLED_HORIZON, null, INSTALLED_HORIZON, 359));
        assertFalse(HorizonNativeModManager.isNativeModCompatible(
                "26.3", TESTED_HORIZON, null, INSTALLED_HORIZON, 359));
    }

    @Test
    public void userInterfaceDoesNotRejectHookBundleOnlyForItsTestedCommit() {
        HorizonNativeModArchive.Descriptor descriptor =
                new HorizonNativeModArchive.Descriptor(
                        "jei", "0.1.0", "26.3", TESTED_HORIZON, MIXINS,
                        2, "a".repeat(64), 15835L);

        assertTrue(HorizonNativeModManager.isPotentiallyCompatible(
                descriptor, "horizon-nightly-c67dad0d1-o8a869a3e"));
        assertFalse(HorizonNativeModManager.isPotentiallyCompatible(
                descriptor, "horizon-nightly-c67dad0d1"));
    }

    @Test
    public void aPinnedModuleCannotUseOnlyTheShortRuntimeTagPrefixAsProof() {
        HorizonNativeModArchive.Descriptor descriptor =
                new HorizonNativeModArchive.Descriptor(
                        "veinminer", "0.3.0", "26.3", TESTED_HORIZON, MIXINS,
                        null, "b".repeat(64), 1000L);

        assertFalse(HorizonNativeModManager.isPotentiallyCompatible(
                descriptor, "horizon-nightly-c67dad0d1-o8a869a3e"));
        assertTrue(HorizonNativeModManager.isPotentiallyCompatible(
                new HorizonNativeModArchive.Descriptor(
                        "veinminer", "0.3.0", "26.3", INSTALLED_HORIZON, MIXINS,
                        null, "b".repeat(64), 1000L),
                "horizon-nightly-c67dad0d1-o8a869a3e"));
    }
}
