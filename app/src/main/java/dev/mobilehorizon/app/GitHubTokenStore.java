package dev.mobilehorizon.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.security.KeyStore;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the user's GitHub fine-grained token encrypted with an Android Keystore AES key. */
final class GitHubTokenStore {
    private static final String PREFS = "github_native_builder_auth";
    private static final String KEY_ALIAS = "mobilehorizon_github_builder_aes_v1";
    private static final String VALUE = "encrypted_token";
    private static final String IV = "token_iv";

    private GitHubTokenStore() { }

    static void save(Context context, String rawToken) throws Exception {
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.length() < 30 || token.length() > 300 || token.matches(".*\\\\s+.*")) {
            throw new IOException("O token informado parece inválido. Cole o token Fine-grained completo.");
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean saved = preferences.edit()
                .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit();
        if (!saved) throw new IOException("Não foi possível salvar o token criptografado.");
    }

    static String load(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ivValue = preferences.getString(IV, null);
        String encryptedValue = preferences.getString(VALUE, null);
        if (ivValue == null || encryptedValue == null) return null;
        try {
            byte[] iv = Base64.decode(ivValue, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(encryptedValue, Base64.NO_WRAP);
            if (iv.length != 12 || encrypted.length < 16 || encrypted.length > 512) {
                throw new IOException("O token salvo está corrompido. Configure o acesso GitHub novamente.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (AEADBadTagException invalidated) {
            clear(context);
            throw new IOException("A chave protegida pelo Android Keystore mudou. Configure novamente o token GitHub.", invalidated);
        } catch (IllegalArgumentException | java.security.GeneralSecurityException error) {
            clear(context);
            throw new IOException("Não foi possível descriptografar o token GitHub salvo.", error);
        }
    }

    static boolean hasToken(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return preferences.contains(VALUE) && preferences.contains(IV);
    }

    static void clear(Context context) throws Exception {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
            if (key instanceof SecretKey) return (SecretKey) key;
            throw new IOException("A chave GitHub armazenada no Android Keystore é inválida.");
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
