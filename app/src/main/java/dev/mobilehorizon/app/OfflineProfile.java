package dev.mobilehorizon.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local player identities. These names never authenticate a Microsoft account. */
final class OfflineProfile {
    static final String DEFAULT_NAME = "Jogador";
    private static final String PREFERENCES = "offline_profile";
    private static final String KEY_PLAYER_NAME = "player_name";
    private static final String KEY_PROFILES = "profiles_json";
    private static final String KEY_SELECTED = "selected_profile";
    private static final int MAX_PROFILES = 12;

    private OfflineProfile() {}

    static String validateName(String rawName) {
        if (rawName == null) throw new IllegalArgumentException("Digite um nick para o perfil offline.");
        String name = rawName.trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("O nick precisa ter de 3 a 16 caracteres e usar somente letras sem acento, números ou sublinhado.");
        }
        return name;
    }

    static synchronized List<String> listNames(Context context) {
        SharedPreferences p = preferences(context);
        JSONArray stored = readProfiles(p);
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < stored.length(); i++) {
            String value = stored.optString(i, "");
            try {
                String valid = validateName(value);
                if (!containsIgnoreCase(names, valid)) names.add(valid);
            } catch (IllegalArgumentException ignored) { }
        }
        if (names.isEmpty()) names.add(DEFAULT_NAME);
        return Collections.unmodifiableList(names);
    }

    static synchronized String loadName(Context context) {
        SharedPreferences p = preferences(context);
        ensureProfiles(p);
        String selected = p.getString(KEY_SELECTED, p.getString(KEY_PLAYER_NAME, DEFAULT_NAME));
        for (String name : listNames(context)) {
            if (name.equalsIgnoreCase(selected)) return name;
        }
        String fallback = listNames(context).get(0);
        p.edit().putString(KEY_SELECTED, fallback).putString(KEY_PLAYER_NAME, fallback).apply();
        return fallback;
    }

    static synchronized String saveName(Context context, String rawName) {
        String name = validateName(rawName);
        SharedPreferences p = preferences(context);
        ArrayList<String> names = new ArrayList<>(listNames(context));
        String selected = loadName(context);
        if (!selected.equalsIgnoreCase(name) && containsIgnoreCase(names, name)) {
            throw new IllegalArgumentException("Já existe um perfil offline com esse nick.");
        }
        int index = indexOfIgnoreCase(names, selected);
        if (index >= 0) names.set(index, name);
        else names.add(name);
        persist(p, names, name);
        return name;
    }

    static synchronized String createProfile(Context context, String rawName) {
        String name = validateName(rawName);
        ArrayList<String> names = new ArrayList<>(listNames(context));
        if (names.size() >= MAX_PROFILES) throw new IllegalStateException("O limite de 12 perfis offline foi atingido.");
        if (containsIgnoreCase(names, name)) throw new IllegalArgumentException("Já existe um perfil offline com esse nick.");
        names.add(name);
        persist(preferences(context), names, loadName(context));
        return name;
    }

    static synchronized void selectProfile(Context context, String rawName) {
        String name = validateName(rawName);
        ArrayList<String> names = new ArrayList<>(listNames(context));
        if (!containsIgnoreCase(names, name)) throw new IllegalArgumentException("O perfil offline não existe.");
        persist(preferences(context), names, name);
    }

    static synchronized void deleteProfile(Context context, String rawName) {
        String name = validateName(rawName);
        ArrayList<String> names = new ArrayList<>(listNames(context));
        if (names.size() <= 1) throw new IllegalStateException("Mantenha pelo menos um perfil offline.");
        String selected = loadName(context);
        if (selected.equalsIgnoreCase(name)) throw new IllegalStateException("Selecione outro perfil antes de excluir este.");
        names.removeIf(value -> value.equalsIgnoreCase(name));
        persist(preferences(context), names, selected);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void ensureProfiles(SharedPreferences p) {
        JSONArray data = readProfiles(p);
        if (data.length() > 0) return;
        String legacy = p.getString(KEY_PLAYER_NAME, DEFAULT_NAME);
        try { legacy = validateName(legacy); } catch (IllegalArgumentException ignored) { legacy = DEFAULT_NAME; }
        JSONArray migrated = new JSONArray();
        migrated.put(legacy);
        p.edit().putString(KEY_PROFILES, migrated.toString())
                .putString(KEY_SELECTED, legacy).putString(KEY_PLAYER_NAME, legacy).commit();
    }

    private static JSONArray readProfiles(SharedPreferences p) {
        try { return new JSONArray(p.getString(KEY_PROFILES, "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    private static void persist(SharedPreferences p, List<String> names, String selected) {
        JSONArray json = new JSONArray();
        for (String name : names) json.put(name);
        boolean saved = p.edit().putString(KEY_PROFILES, json.toString())
                .putString(KEY_SELECTED, selected).putString(KEY_PLAYER_NAME, selected).commit();
        if (!saved) throw new IllegalStateException("O Android não conseguiu salvar os perfis offline.");
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        return indexOfIgnoreCase(values, target) >= 0;
    }

    private static int indexOfIgnoreCase(List<String> values, String target) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).equalsIgnoreCase(target)) return i;
        return -1;
    }
}
