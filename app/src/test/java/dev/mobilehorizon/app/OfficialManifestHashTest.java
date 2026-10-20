package dev.mobilehorizon.app;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OfficialManifestHashTest {
    @Test
    public void acceptsCurrentManifestHashWhenAdvertisedByGlobalManifest() {
        String hash = "61fff1316bd50312624a858bbb5eb18fa45208ed";
        assertTrue(OfficialMinecraftInstaller.matchesAdvertisedVersionSha1(hash, hash));
        assertTrue(OfficialMinecraftInstaller.isAcceptedVersionJsonSha1(hash));
    }

    @Test
    public void acceptsCaseDifferencesInHexSha1() {
        assertTrue(OfficialMinecraftInstaller.matchesAdvertisedVersionSha1(
                "61FFF1316BD50312624A858BBB5EB18FA45208ED",
                "61fff1316bd50312624a858bbb5eb18fa45208ed"));
    }

    @Test
    public void acceptsVerifiedReceiptForUpdatedOfficialManifest() throws Exception {
        File dir = Files.createTempDirectory("mobilehorizon-manifest-test").toFile();
        dir.deleteOnExit();
        File versionJson = new File(dir, "26.3.json");
        byte[] jsonBytes = "{}".getBytes(StandardCharsets.UTF_8);
        Files.write(versionJson.toPath(), jsonBytes);
        versionJson.deleteOnExit();

        byte[] digest = MessageDigest.getInstance("SHA-1").digest(jsonBytes);
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format("%02x", value & 0xff));

        File receipt = new File(dir, "26.3.json.verified-sha1");
        String content = OfficialMinecraftInstaller.VERIFIED_VERSION_RECEIPT_MAGIC + "\n"
                + OfficialMinecraftInstaller.VERSION + "\n" + hash + "\n";
        Files.write(receipt.toPath(), content.getBytes(StandardCharsets.UTF_8));
        receipt.deleteOnExit();

        assertTrue(OfficialMinecraftInstaller.hasVerifiedVersionJsonSha1(versionJson));
        Files.write(versionJson.toPath(), "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8));
        assertFalse(OfficialMinecraftInstaller.hasVerifiedVersionJsonSha1(versionJson));
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
