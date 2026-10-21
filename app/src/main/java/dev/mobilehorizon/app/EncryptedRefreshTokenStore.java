package dev.mobilehorizon.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

/** Stores only the Microsoft refresh token, encrypted with a non-exportable Android Keystore key. */
final class EncryptedRefreshTokenStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "dev.mobilehorizon.auth.refresh.v1";
    private static final byte FORMAT_VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int MAX_STORED_BYTES = 16 * 1024;

    private EncryptedRefreshTokenStore() {}

    static void save(Context context, String token) throws Exception {
        if (token == null || token.isEmpty() || token.length() > 10_000) {
            throw new IllegalArgumentException("O token de renovação está vazio ou excede o limite.");
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_LENGTH) {
            throw new IllegalStateException("O Android Keystore retornou um IV inesperado.");
        }
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        File file = tokenFile(context);
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Não foi possível criar o diretório privado de autenticação.");
        }
        File temporary = new File(parent, "refresh.enc.part");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(FORMAT_VERSION);
            output.write(iv);
            output.write(encrypted);
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            throw error;
        }
        if (file.exists() && !file.delete()) {
            temporary.delete();
            throw new IllegalStateException("Não foi possível atualizar a sessão Microsoft criptografada.");
        }
        if (!temporary.renameTo(file)) {
            temporary.delete();
            throw new IllegalStateException("Não foi possível ativar a sessão Microsoft criptografada.");
        }
    }

    static String load(Context context) throws Exception {
        File file = tokenFile(context);
        if (!file.isFile()) return null;
        if (file.length() <= 1 + IV_LENGTH + 16 || file.length() > MAX_STORED_BYTES) {
            throw new IllegalStateException("O token Microsoft criptografado está inválido. Entre novamente.");
        }
        byte[] bytes;
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_STORED_BYTES) {
                    throw new IllegalStateException("O arquivo de sessão criptografada excede o limite.");
                }
                output.write(buffer, 0, read);
            }
            bytes = output.toByteArray();
        }
        if (bytes[0] != FORMAT_VERSION) {
            throw new IllegalStateException("A versão do arquivo de sessão não é compatível. Entre novamente.");
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(bytes, 1, iv, 0, IV_LENGTH);
        byte[] encrypted = new byte[bytes.length - 1 - IV_LENGTH];
        System.arraycopy(bytes, 1 + IV_LENGTH, encrypted, 0, encrypted.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] clear = cipher.doFinal(encrypted);
            String token = new String(clear, StandardCharsets.UTF_8);
            if (token.isEmpty()) throw new IllegalStateException("A sessão Microsoft está vazia.");
            return token;
        } catch (Exception error) {
            throw new IllegalStateException("Não foi possível desbloquear a sessão Microsoft. Entre novamente.", error);
        }
    }

    static void clear(Context context) throws Exception {
        File file = tokenFile(context);
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Não foi possível remover a sessão Microsoft.");
        }
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static File tokenFile(Context context) {
        return new File(context.getFilesDir(), ".local/auth/microsoft-refresh.enc");
    }
}
