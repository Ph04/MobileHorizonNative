package dev.mobilehorizon.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Metadata for multiple Microsoft/Minecraft accounts; refresh tokens stay in the Keystore-backed vault. */
final class MicrosoftAccountStore {
    private static final String PREFS = "microsoft_account_registry";
    private static final String KEY_ACCOUNTS = "accounts_json";
    private static final String KEY_ACTIVE = "active_uuid";

    static final class Account {
        final String uuid;
        final String name;
        final String xuid;
        final long verifiedAt;
        Account(String uuid, String name, String xuid, long verifiedAt) {
            this.uuid = uuid; this.name = name; this.xuid = xuid; this.verifiedAt = verifiedAt;
        }
    }

    private MicrosoftAccountStore() {}

    static synchronized List<Account> list(Context context) {
        migrateLegacy(context);
        JSONArray data = read(context);
        ArrayList<Account> result = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) continue;
            String uuid = o.optString("uuid", "");
            String name = o.optString("name", "");
            if (validId(uuid) && !name.isEmpty()) {
                result.add(new Account(uuid.toLowerCase(java.util.Locale.ROOT), name,
                        o.optString("xuid", ""), o.optLong("verifiedAt", 0L)));
            }
        }
        return Collections.unmodifiableList(result);
    }

    static synchronized Account active(Context context) {
        List<Account> accounts = list(context);
        String id = prefs(context).getString(KEY_ACTIVE, "");
        for (Account account : accounts) if (account.uuid.equalsIgnoreCase(id)) return account;
        if (!accounts.isEmpty()) {
            select(context, accounts.get(0).uuid);
            return accounts.get(0);
        }
        return null;
    }

    static synchronized void upsert(Context context, String uuid, String name, String xuid, long verifiedAt) {
        if (!validId(uuid) || name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("O perfil Microsoft retornado não é válido.");
        }
        JSONArray old = read(context);
        JSONArray next = new JSONArray();
        String normalized = uuid.toLowerCase(java.util.Locale.ROOT);
        try {
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null || normalized.equalsIgnoreCase(item.optString("uuid", ""))) continue;
                next.put(item);
            }
            next.put(new JSONObject().put("uuid", normalized).put("name", name)
                    .put("xuid", xuid == null ? "" : xuid).put("verifiedAt", verifiedAt));
        } catch (JSONException error) {
            throw new IllegalStateException("Não foi possível atualizar a lista de contas Microsoft.", error);
        }
        commit(context, next, normalized);
        legacyPrefs(context).edit().putBoolean("hasAccount", true).putString("accountName", name)
                .putString("accountUuid", normalized).putString("accountXuid", xuid == null ? "" : xuid)
                .putLong("accountVerifiedAt", verifiedAt).commit();
    }

    static synchronized void select(Context context, String uuid) {
        Account match = null;
        for (Account account : list(context)) if (account.uuid.equalsIgnoreCase(uuid)) match = account;
        if (match == null) throw new IllegalArgumentException("A conta Microsoft não está salva neste dispositivo.");

        // If this installation still has a legacy single-account token, migrate it for
        // the UUID currently recorded with that token before changing the compatibility
        // metadata to another account. This prevents the old token being attributed to
        // the newly selected profile.
        String legacyId = legacyPrefs(context).getString("accountUuid", "");
        if (validId(legacyId)) {
            try {
                EncryptedRefreshTokenStore.load(context, legacyId);
            } catch (Exception error) {
                throw new IllegalStateException(
                        "Não foi possível migrar a sessão da conta atual antes de alternar. Entre novamente com essa conta e tente outra vez.",
                        error);
            }
        }
        prefs(context).edit().putString(KEY_ACTIVE, match.uuid).commit();
        legacyPrefs(context).edit().putBoolean("hasAccount", true).putString("accountName", match.name)
                .putString("accountUuid", match.uuid).putString("accountXuid", match.xuid)
                .putLong("accountVerifiedAt", match.verifiedAt).commit();
    }

    static synchronized Account remove(Context context, String uuid) {
        JSONArray old = read(context);
        JSONArray next = new JSONArray();
        try {
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !uuid.equalsIgnoreCase(item.optString("uuid", ""))) next.put(item);
            }
        } catch (Exception ignored) { }
        String activeId = prefs(context).getString(KEY_ACTIVE, "");
        String newActive = activeId.equalsIgnoreCase(uuid) ? "" : activeId;
        if (newActive.isEmpty() && next.length() > 0) newActive = next.optJSONObject(0).optString("uuid", "");
        commit(context, next, newActive);
        if (newActive.isEmpty()) {
            legacyPrefs(context).edit().clear().commit();
            return null;
        }
        for (int i = 0; i < next.length(); i++) {
            JSONObject item = next.optJSONObject(i);
            if (item != null && newActive.equalsIgnoreCase(item.optString("uuid", ""))) {
                select(context, newActive);
                return active(context);
            }
        }
        return null;
    }

    static synchronized void migrateLegacy(Context context) {
        if (read(context).length() > 0) return;
        SharedPreferences legacy = legacyPrefs(context);
        if (!legacy.getBoolean("hasAccount", false)) return;
        String id = legacy.getString("accountUuid", "");
        String name = legacy.getString("accountName", "");
        if (!validId(id) || name.isEmpty()) return;
        JSONArray migrated = new JSONArray();
        try {
            migrated.put(new JSONObject().put("uuid", id.toLowerCase(java.util.Locale.ROOT))
                    .put("name", name).put("xuid", legacy.getString("accountXuid", ""))
                    .put("verifiedAt", legacy.getLong("accountVerifiedAt", 0L)));
        } catch (JSONException ignored) { return; }
        commit(context, migrated, id);
    }

    private static JSONArray read(Context context) {
        try { return new JSONArray(prefs(context).getString(KEY_ACCOUNTS, "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    private static void commit(Context context, JSONArray data, String active) {
        if (!prefs(context).edit().putString(KEY_ACCOUNTS, data.toString())
                .putString(KEY_ACTIVE, active == null ? "" : active).commit()) {
            throw new IllegalStateException("Não foi possível salvar as contas Microsoft.");
        }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("(?i)[0-9a-f]{32}");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences legacyPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("microsoft_account_state", Context.MODE_PRIVATE);
    }
}
