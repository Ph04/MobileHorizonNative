package dev.mobilehorizon.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class ServerFavoriteStore {
    static final class Favorite {
        final String name; final String address;
        Favorite(String name, String address) { this.name = name; this.address = address; }
    }
    private ServerFavoriteStore() {}

    static List<Favorite> list(Context context) {
        JSONArray array = read(context); ArrayList<Favorite> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i); if (o == null) continue;
            String name = o.optString("name", ""), address = o.optString("address", "");
            try { result.add(new Favorite(validateName(name), validateAddress(address))); }
            catch (IllegalArgumentException ignored) { }
        }
        return Collections.unmodifiableList(result);
    }

    static void add(Context context, String name, String address) {
        String safeName = validateName(name), safeAddress = validateAddress(address);
        ArrayList<Favorite> values = new ArrayList<>(list(context));
        for (Favorite value : values) if (value.address.equalsIgnoreCase(safeAddress)) {
            throw new IllegalArgumentException("Esse endereço já está salvo.");
        }
        if (values.size() >= 100) throw new IllegalStateException("O limite de 100 servidores favoritos foi atingido.");
        values.add(new Favorite(safeName, safeAddress)); write(context, values);
    }

    static void delete(Context context, String address) {
        ArrayList<Favorite> values = new ArrayList<>(list(context));
        values.removeIf(value -> value.address.equalsIgnoreCase(address));
        write(context, values);
    }

    private static String validateName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 48) throw new IllegalArgumentException("O nome deve ter de 1 a 48 caracteres.");
        return value;
    }

    static String validateAddress(String address) {
        String value = address == null ? "" : address.trim();
        if (value.isEmpty() || value.length() > 255 || value.contains("://") || value.contains("/") || value.contains(" ")) {
            throw new IllegalArgumentException("Endereço inválido. Use domínio ou IP com porta opcional.");
        }
        String host = value; String port = null;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 2) throw new IllegalArgumentException("Endereço IPv6 inválido.");
            host = value.substring(1, end);
            if (end + 1 < value.length()) {
                if (value.charAt(end + 1) != ':') throw new IllegalArgumentException("Porta inválida.");
                port = value.substring(end + 2);
            }
            if (!host.matches("[0-9a-fA-F:]+")) throw new IllegalArgumentException("Endereço IPv6 inválido.");
        } else {
            int colon = value.lastIndexOf(':');
            if (colon > 0 && value.indexOf(':') == colon) { host = value.substring(0, colon); port = value.substring(colon + 1); }
            if (!host.matches("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) throw new IllegalArgumentException("Domínio ou IP inválido.");
        }
        if (port != null) {
            if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Porta inválida.");
            int n = Integer.parseInt(port);
            if (n < 1 || n > 65535) throw new IllegalArgumentException("A porta deve ficar entre 1 e 65535.");
        }
        return value;
    }

    private static JSONArray read(Context context) {
        String id;
        try { id = GameInstanceManager.getSelectedInstance(context).id; }
        catch (Exception error) { id = GameInstanceManager.DEFAULT_ID; }
        SharedPreferences p = context.getSharedPreferences("server_favorites_" + id, Context.MODE_PRIVATE);
        try { return new JSONArray(p.getString("servers", "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    private static void write(Context context, List<Favorite> values) {
        String id;
        try { id = GameInstanceManager.getSelectedInstance(context).id; }
        catch (Exception error) { throw new IllegalStateException("Não foi possível identificar a instância ativa.", error); }
        JSONArray array = new JSONArray();
        try { for (Favorite value : values) array.put(new JSONObject().put("name", value.name).put("address", value.address)); }
        catch (JSONException error) { throw new IllegalStateException("Não foi possível gravar servidores favoritos.", error); }
        if (!context.getSharedPreferences("server_favorites_" + id, Context.MODE_PRIVATE)
                .edit().putString("servers", array.toString()).commit()) {
            throw new IllegalStateException("Não foi possível salvar servidores favoritos.");
        }
    }
}
