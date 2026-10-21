package dev.mobilehorizon.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class HorizonPrepareManifestLockTest {
    @Test
    public void addsReceiptVerifiedRepublishedManifestToHorizonLockIdempotently() throws Exception {
        File root = Files.createTempDirectory("mobilehorizon-horizon-lock-test").toFile();
        File workspace = new File(root, "workspace");
        assertTrue(workspace.mkdirs());
        File versionDir = new File(root, "versions/26.3");
        assertTrue(versionDir.mkdirs());
        File manifestFile = new File(versionDir, "26.3.json");

        byte[] manifestBytes = validManifest().getBytes(StandardCharsets.UTF_8);
        Files.write(manifestFile.toPath(), manifestBytes);
        String hash = sha1(manifestBytes);
        writeReceipt(manifestFile, hash);

        File lockFile = new File(workspace, "research-lock.json");
        Files.write(lockFile.toPath(), lockJson().getBytes(StandardCharsets.UTF_8));

        assertEquals(hash, HorizonPrepareRunner.synchronizeVerifiedManifestWithHorizonLock(
                workspace, manifestFile));

        JSONObject lock = new JSONObject(new String(
                Files.readAllBytes(lockFile.toPath()), StandardCharsets.UTF_8));
        JSONObject minecraft = lock.getJSONObject("minecraft");
        assertEquals("11f1aefe473eefba0af62e3286c33b692807b2eb",
                minecraft.getString("manifest_sha1"));
        JSONArray accepted = minecraft.getJSONArray("accepted_manifest_sha1");
        assertTrue(contains(accepted, "4fe1aa1ef8da1cb95c5bad1fb98890ca56dd8ca3"));
        assertTrue(contains(accepted, hash));

        HorizonPrepareRunner.synchronizeVerifiedManifestWithHorizonLock(workspace, manifestFile);
        JSONObject secondRead = new JSONObject(new String(
                Files.readAllBytes(lockFile.toPath()), StandardCharsets.UTF_8));
        JSONArray secondAccepted = secondRead.getJSONObject("minecraft")
                .getJSONArray("accepted_manifest_sha1");
        assertEquals(1, occurrences(secondAccepted, hash));
        deleteTree(root);
    }

    @Test
    public void rejectsManifestWithoutValidVerificationReceipt() throws Exception {
        File root = Files.createTempDirectory("mobilehorizon-horizon-lock-invalid").toFile();
        File workspace = new File(root, "workspace");
        assertTrue(workspace.mkdirs());
        File versionDir = new File(root, "versions/26.3");
        assertTrue(versionDir.mkdirs());
        File manifestFile = new File(versionDir, "26.3.json");

        byte[] manifestBytes = validManifest().getBytes(StandardCharsets.UTF_8);
        Files.write(manifestFile.toPath(), manifestBytes);
        writeReceipt(manifestFile, "0000000000000000000000000000000000000000");
        File lockFile = new File(workspace, "research-lock.json");
        String originalLock = lockJson();
        Files.write(lockFile.toPath(), originalLock.getBytes(StandardCharsets.UTF_8));

        try {
            HorizonPrepareRunner.synchronizeVerifiedManifestWithHorizonLock(workspace, manifestFile);
            fail("A manifest without a matching official verification receipt must not be accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("O Horizon não reconhece este SHA-1"));
        }

        assertEquals(originalLock, new String(
                Files.readAllBytes(lockFile.toPath()), StandardCharsets.UTF_8));
        deleteTree(root);
    }

    private static String validManifest() {
        return "{"
                + "\"id\":\"26.3\","
                + "\"javaVersion\":{\"majorVersion\":25},"
                + "\"downloads\":{"
                + "\"client\":{\"sha1\":\"" + OfficialMinecraftInstaller.CLIENT_SHA1 + "\"},"
                + "\"server\":{\"sha1\":\"" + OfficialMinecraftInstaller.SERVER_SHA1 + "\"}"
                + "}}";
    }

    private static String lockJson() {
        return "{\"minecraft\":{"
                + "\"version\":\"26.3\","
                + "\"manifest_sha1\":\"11f1aefe473eefba0af62e3286c33b692807b2eb\","
                + "\"accepted_manifest_sha1\":["
                + "\"4fe1aa1ef8da1cb95c5bad1fb98890ca56dd8ca3\","
                + "\"702fe59163c6ee6578607daa85811d9bc9c7cc40\"],"
                + "\"client_sha1\":\"" + OfficialMinecraftInstaller.CLIENT_SHA1 + "\","
                + "\"server_sha1\":\"" + OfficialMinecraftInstaller.SERVER_SHA1 + "\","
                + "\"java_major\":25}}";
    }

    private static void writeReceipt(File manifestFile, String hash) throws Exception {
        String receipt = OfficialMinecraftInstaller.VERIFIED_VERSION_RECEIPT_MAGIC
                + "\n" + OfficialMinecraftInstaller.VERSION + "\n" + hash + "\n";
        File receiptFile = new File(manifestFile.getParentFile(),
                manifestFile.getName() + ".verified-sha1");
        Files.write(receiptFile.toPath(), receipt.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean contains(JSONArray values, String target) {
        return occurrences(values, target) > 0;
    }

    private static int occurrences(JSONArray values, String target) {
        int count = 0;
        for (int i = 0; i < values.length(); i++) {
            if (target.equals(values.optString(i, ""))) count++;
        }
        return count;
    }

    private static String sha1(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
        StringBuilder text = new StringBuilder(digest.length * 2);
        for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}