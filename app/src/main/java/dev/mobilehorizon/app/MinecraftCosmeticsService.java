package dev.mobilehorizon.app;

import android.content.Context;
import android.net.Uri;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Official Minecraft profile skin/cape operations. Access tokens are passed only in HTTPS requests. */
final class MinecraftCosmeticsService {
    private static final String API = "https://api.minecraftservices.com";
    private MinecraftCosmeticsService() {}

    static MicrosoftAuthentication.MinecraftAccount refreshAccount(Context context) throws Exception {
        MicrosoftAccountStore.Account account = MicrosoftAccountStore.active(context);
        if (account == null) throw new IllegalStateException("Entre com uma conta Microsoft verificada primeiro.");
        String refresh = EncryptedRefreshTokenStore.load(context, account.uuid);
        if (refresh == null) throw new IllegalStateException("A sessão salva expirou. Entre novamente com a Microsoft.");
        MicrosoftAuthentication.MinecraftAccount fresh = MicrosoftAuthentication.refreshAndVerify(
                BuildConfig.HORIZON_MSA_CLIENT_ID, refresh, null);
        EncryptedRefreshTokenStore.save(context, fresh.uuid, fresh.refreshToken);
        MicrosoftAccountStore.upsert(context, fresh.uuid, fresh.name, fresh.xuid, System.currentTimeMillis());
        return fresh;
    }

    static JSONObject fetchProfile(String accessToken) throws Exception {
        return request("GET", "/minecraft/profile", accessToken, null, null);
    }

    static android.graphics.Bitmap fetchSkinTexture(String textureUrl) throws Exception {
        textureUrl = secureTextureUrl(textureUrl);
        HttpURLConnection connection = (HttpURLConnection) LauncherNetworkSettings.open(new URL(textureUrl));
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 2 * 1024 * 1024)
                    throw new IllegalStateException("A textura da skin excede o limite.");
                output.write(buffer, 0, read);
            }
            byte[] bytes = output.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth != 64 || bounds.outHeight != 64
                    || !"image/png".equalsIgnoreCase(bounds.outMimeType))
                throw new IllegalStateException("A textura oficial da skin não possui formato 64×64 PNG.");
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inScaled = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject uploadSkin(Context context, Uri uri, String accessToken, String variant) throws Exception {
        if (!"classic".equals(variant) && !"slim".equals(variant)) {
            throw new IllegalArgumentException("Modelo de skin inválido.");
        }
        byte[] png;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("Não foi possível abrir a imagem selecionada.");
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 2 * 1024 * 1024) {
                    throw new IllegalArgumentException("A skin excede o limite de 2 MiB.");
                }
                output.write(buffer, 0, read);
            }
            png = output.toByteArray();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth != 64 || bounds.outHeight != 64
                || !"image/png".equalsIgnoreCase(bounds.outMimeType)) {
            throw new IllegalArgumentException("Use uma skin PNG de 64 × 64 pixels.");
        }
        String boundary = "MH" + Long.toHexString(System.nanoTime());
        HttpURLConnection connection = open("/minecraft/profile/skins", accessToken);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = connection.getOutputStream()) {
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n" + variant + "\r\n");
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n");
            out.write(png);
            write(out, "\r\n--" + boundary + "--\r\n");
        }
        return response(connection);
    }

    static JSONObject selectSkin(String accessToken, String url, String variant) throws Exception {
        String secureUrl = secureTextureUrl(url);
        if (!"classic".equalsIgnoreCase(variant) && !"slim".equalsIgnoreCase(variant))
            throw new IllegalArgumentException("Modelo de skin inválido.");
        JSONObject body = new JSONObject().put("url", secureUrl).put("variant", variant.toLowerCase(java.util.Locale.ROOT));
        return request("POST", "/minecraft/profile/skins", accessToken, "application/json", body.toString());
    }

    private static String secureTextureUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("http://textures.minecraft.net/"))
            value = "https://" + value.substring("http://".length());
        if (!value.startsWith("https://textures.minecraft.net/"))
            throw new IllegalArgumentException("A URL da skin não pertence ao serviço oficial de texturas.");
        return value;
    }

    static JSONObject activateCape(String accessToken, String capeId) throws Exception {
        if (capeId == null || !capeId.matches("(?i)[0-9a-f-]{16,40}")) {
            throw new IllegalArgumentException("ID de capa inválido.");
        }
        JSONObject body = new JSONObject().put("capeId", capeId);
        return request("PUT", "/minecraft/profile/capes/active", accessToken, "application/json", body.toString());
    }

    static JSONObject hideCape(String accessToken) throws Exception {
        return request("DELETE", "/minecraft/profile/capes/active", accessToken, null, null);
    }

    static JSONObject resetSkin(String accessToken) throws Exception {
        return request("DELETE", "/minecraft/profile/skins/active", accessToken, null, null);
    }

    private static JSONObject request(String method, String path, String token, String contentType, String body) throws Exception {
        HttpURLConnection connection = open(path, token);
        connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        return response(connection);
    }

    private static HttpURLConnection open(String path, String token) throws Exception {
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("Sessão Minecraft vazia.");
        HttpURLConnection connection = (HttpURLConnection) LauncherNetworkSettings.open(new URL(API + path));
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "MobileHorizonNative/1.0");
        return connection;
    }

    private static JSONObject response(HttpURLConnection connection) throws Exception {
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = "";
            if (stream != null) try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (out.size() + read > 2 * 1024 * 1024) throw new IllegalStateException("A resposta do perfil excede o limite.");
                    out.write(buffer, 0, read);
                }
                body = out.toString("UTF-8");
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("O serviço de perfil Minecraft retornou HTTP " + status
                        + (body.isEmpty() ? "." : ": " + body.substring(0, Math.min(240, body.length()))));
            }
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } finally { connection.disconnect(); }
    }

    private static void write(OutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }
}
