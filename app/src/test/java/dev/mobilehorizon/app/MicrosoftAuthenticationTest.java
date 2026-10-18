package dev.mobilehorizon.app;

import org.json.JSONArray;
import org.json.JSONObject;
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
    public void acceptsOnlyMinecraftJavaEntitlements() throws Exception {
        JSONArray valid = new JSONArray()
                .put(new JSONObject().put("name", "game_minecraft"));
        JSONArray gamePass = new JSONArray()
                .put(new JSONObject().put("name", "product_minecraft"));
        JSONArray unrelated = new JSONArray()
                .put(new JSONObject().put("name", "minecraft_dungeons"))
                .put(new JSONObject().put("name", "minecraft_legends"));
        assertTrue(MicrosoftAuthentication.hasJavaEntitlement(valid));
        assertTrue(MicrosoftAuthentication.hasJavaEntitlement(gamePass));
        assertFalse(MicrosoftAuthentication.hasJavaEntitlement(unrelated));
        assertFalse(MicrosoftAuthentication.hasJavaEntitlement(new JSONArray()));
        assertFalse(MicrosoftAuthentication.hasJavaEntitlement(null));
    }
}
