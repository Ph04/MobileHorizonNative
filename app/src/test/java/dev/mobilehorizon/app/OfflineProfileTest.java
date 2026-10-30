package dev.mobilehorizon.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OfflineProfileTest {
    @Test public void acceptsVanillaOfflineName() {
        assertEquals("Player_123", OfflineProfile.validateName(" Player_123 "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooShortName() {
        OfflineProfile.validateName("ab");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedCharacters() {
        OfflineProfile.validateName("Jogador!");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLongName() {
        OfflineProfile.validateName("ThisNameIsMuchTooLong");
    }
}
