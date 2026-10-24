package dev.mobilehorizon.app;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class MicrosoftAuthenticationTest {
    @Test
    public void acceptsOnlyGuidShapedClientIds() {
        assertTrue(MicrosoftAuthentication.isValidClientId("12345678-1234-abcd-ABCD-1234567890ab"));
        assertFalse(MicrosoftAuthentication.isValidClientId(null));
        assertFalse(MicrosoftAuthentication.isValidClientId(""));
        assertFalse(MicrosoftAuthentication.isValidClientId("official-launcher-id"));
        assertFalse(MicrosoftAuthentication.isValidClientId("123456781234abcdABCD1234567890ab"));
        assertFalse(MicrosoftAuthentication.isValidClientId("gggggggg-1234-abcd-abcd-1234567890ab"));
    }

    @Test
    public void clientIdValidationRejectsUnconfiguredBuild() {
        try {
            MicrosoftAuthentication.requireClientId("");
            fail("An empty application ID must fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("HORIZON_MSA_CLIENT_ID"));
        }
    }

    @Test
    public void recognizesOnlyJavaEditionEntitlementNames() {
        assertTrue(MicrosoftAuthentication.isJavaEntitlementName("game_minecraft"));
        assertTrue(MicrosoftAuthentication.isJavaEntitlementName("product_minecraft"));
        assertFalse(MicrosoftAuthentication.isJavaEntitlementName("minecraft_dungeons"));
        assertFalse(MicrosoftAuthentication.isJavaEntitlementName("minecraft_legends"));
        assertFalse(MicrosoftAuthentication.isJavaEntitlementName(null));
        assertFalse(MicrosoftAuthentication.isJavaEntitlementName(""));
    }
}
