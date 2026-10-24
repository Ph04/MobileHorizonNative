package dev.mobilehorizon.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Local-only player identity. It does not represent a Microsoft account or grant online access. */
final class OfflineProfile {
    static final String DEFAULT_NAME = "Jogador";
    private static final String PREFERENCES = "offline_profile";
    private static final String KEY_PLAYER_NAME = "player_name";

    private OfflineProfile() {}

    static String validateName(String rawName) {
        if (rawName == null) {
            throw new IllegalArgumentException("Digite um nick para o perfil offline.");
        }
        String name = rawName.trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException(
                    "O nick precisa ter de 3 a 16 caracteres e usar somente letras sem acento, números ou sublinhado.");
        }
        return name;
    }

    static String loadName(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String stored = preferences.getString(KEY_PLAYER_NAME, DEFAULT_NAME);
        try {
            return validateName(stored);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_NAME;
        }
    }

    static String saveName(Context context, String rawName) {
        String name = validateName(rawName);
        boolean saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(KEY_PLAYER_NAME, name).commit();
        if (!saved) {
            throw new IllegalStateException("O Android não conseguiu salvar o nick offline.");
        }
        return name;
    }
}
