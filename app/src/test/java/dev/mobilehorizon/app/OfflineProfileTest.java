package dev.mobilehorizon.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class OfflineProfileTest {
    @Test
    public void trimsValidNickname() {
        assertEquals("Alex_123", OfflineProfile.validateName("  Alex_123  "));
    }

    @Test
    public void acceptsBoundaryLengths() {
        assertEquals("Abc", OfflineProfile.validateName("Abc"));
        assertEquals("abcdefghijklmnop", OfflineProfile.validateName("abcdefghijklmnop"));
    }

    @Test
    public void rejectsShortLongAndUnsupportedNicknames() {
        reject("ab");
        reject("abcdefghijklmnopq");
        reject("álex");
        reject("nick with spaces");
        reject("nick!");
        reject("");
        reject(null);
    }

    private static void reject(String value) {
        try {
            OfflineProfile.validateName(value);
            fail("Expected nickname to be rejected: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected: offline names are deliberately limited to Java username characters.
        }
    }
}
