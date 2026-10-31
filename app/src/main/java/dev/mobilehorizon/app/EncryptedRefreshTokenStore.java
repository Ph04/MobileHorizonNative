package dev.mobilehorizon.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.system.Os;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Locale;

/** Encrypted per-account refresh-token vault backed by non-exportable Android Keystore keys. */
final class EncryptedRefreshTokenStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String LEGACY_ALIAS = "dev.mobilehorizon.auth.refresh.v1";
    private static final byte FORMAT_VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int MAX_STORED_BYTES = 16 * 1024;

    private EncryptedRefreshTokenStore() {}

    static void save(Context context, String token) throws Exception {
        saveTo(context, LEGACY_ALIAS, new File(context.getFilesDir(), ".local/auth/microsoft-refresh.enc"), token);
    }

    static void save(Context context, String accountId, String token) throws Exception {
        String id = normalizeId(accountId);
        saveTo(context, aliasFor(id), tokenFile(context, id), token);
    }

    static String load(Context context) throws Exception {
        return loadFrom(LEGACY_ALIAS, new File(context.getFilesDir(), ".local/auth/microsoft-refresh.enc"));
    }

    static String load(Context context, String accountId) throws Exception {
        String id = normalizeId(accountId);
        File target = tokenFile(context, id);
        if (target.isFile()) return loadFrom(aliasFor(id), target);
        // Migrate installations created before account-scoped tokens were introduced.
        File legacy = new File(context.getFilesDir(), ".local/auth/microsoft-refresh.enc");
        if (!legacy.isFile()) return null;
        String legacyAccount = context.getSharedPreferences("microsoft_account_state", Context.MODE_PRIVATE)
                .getString("accountUuid", "");
        if (!id.equalsIgnoreCase(legacyAccount)) return null;
        String old = loadFrom(LEGACY_ALIAS, legacy);
        if (old != null) {
            save(context, id, old);
            clearLegacy(context);
        }
        return old;
    }

    static void clear(Context context) throws Exception {
        clearFile(new File(context.getFilesDir(), ".local/auth/microsoft-refresh.enc"), LEGACY_ALIAS);
    }

    static void clear(Context context, String accountId) throws Exception {
        String id = normalizeId(accountId);
        clearFile(tokenFile(context, id), aliasFor(id));
        // Remove a pre-multi-account token only when its legacy metadata identifies this account.
        String legacyId = context.getSharedPreferences("microsoft_account_state", Context.MODE_PRIVATE)
                .getString("accountUuid", "");
        if (id.equalsIgnoreCase(legacyId)) clearLegacy(context);
    }

    private static void saveTo(Context context, String alias, File file, String token) throws Exception {
        if (token == null || token.isEmpty() || token.length() > 10_000) {
            throw new IllegalArgumentException("O token de renovação está vazio ou excede o limite.");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias));
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_LENGTH) throw new IllegalStateException("O Android Keystore retornou um IV inesperado.");
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Não foi possível criar o diretório privado de autenticação.");
        }
        Os.chmod(parent.getAbsolutePath(), 0700);
        File temporary = new File(parent, file.getName() + ".part");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(FORMAT_VERSION); output.write(iv); output.write(encrypted);
            output.flush(); output.getFD().sync();
        } catch (Exception error) {
            temporary.delete(); throw error;
        }
        if (file.exists() && !file.delete()) {
            temporary.delete(); throw new IllegalStateException("Não foi possível atualizar a sessão Microsoft criptografada.");
        }
        if (!temporary.renameTo(file)) {
            temporary.delete(); throw new IllegalStateException("Não foi possível ativar a sessão Microsoft criptografada.");
        }
        Os.chmod(file.getAbsolutePath(), 0600);
    }

    private static String loadFrom(String alias, File file) throws Exception {
        if (!file.isFile()) return null;
        if (file.length() <= 1 + IV_LENGTH + 16 || file.length() > MAX_STORED_BYTES) {
            throw new IllegalStateException("O token Microsoft criptografado está inválido. Entre novamente.");
        }
        byte[] bytes;
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_STORED_BYTES) throw new IllegalStateException("O arquivo de sessão criptografada excede o limite.");
                output.write(buffer, 0, read);
            }
            bytes = output.toByteArray();
        }
        if (bytes[0] != FORMAT_VERSION) throw new IllegalStateException("A versão do arquivo de sessão não é compatível. Entre novamente.");
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(bytes, 1, iv, 0, IV_LENGTH);
        byte[] encrypted = new byte[bytes.length - 1 - IV_LENGTH];
        System.arraycopy(bytes, 1 + IV_LENGTH, encrypted, 0, encrypted.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), new GCMParameterSpec(128, iv));
            String token = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            if (token.isEmpty()) throw new IllegalStateException("A sessão Microsoft está vazia.");
            return token;
        } catch (Exception error) {
            throw new IllegalStateException("Não foi possível desbloquear a sessão Microsoft. Entre novamente.", error);
        }
    }

    private static void clearLegacy(Context context) throws Exception {
        clear(context);
    }

    private static void clearFile(File file, String alias) throws Exception {
        if (file.exists() && !file.delete()) throw new IllegalStateException("Não foi possível remover a sessão Microsoft.");
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(alias)) store.deleteEntry(alias);
    }

    private static SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(alias, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build());
        return generator.generateKey();
    }

    private static String normalizeId(String raw) {
        if (raw == null || !raw.matches("(?i)[0-9a-f]{32}")) throw new IllegalArgumentException("ID da conta Microsoft inválido.");
        return raw.toLowerCase(Locale.ROOT);
    }

    private static String aliasFor(String id) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder("dev.mobilehorizon.auth.refresh.");
        for (int i = 0; i < 16; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i]));
        return out.toString();
    }

    private static File tokenFile(Context context, String id) throws Exception {
        File root = new File(context.getFilesDir(), ".local/auth");
        return new File(root, "microsoft-refresh-" + id + ".enc");
    }
}
