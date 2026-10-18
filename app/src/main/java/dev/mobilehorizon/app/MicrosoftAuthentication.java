package dev.mobilehorizon.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Microsoft device-code sign-in and Minecraft entitlement verification.
 * This is a public OAuth client: it must use this project's own app registration. Tokens are
 * never logged or included in exception text.
 */
final class MicrosoftAuthentication {
    private static final String MS_BASE =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private static final String XBOX_USER =
            "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS =
            "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_BASE = "https://api.minecraftservices.com";
    private static final String SCOPES = "XboxLive.signin offline_access";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    interface ProgressListener {
        void onProgress(String stage, String detail);
    }

    static final class DeviceCode {
        final String deviceCode;
        final String userCode;
        final String verificationUri;
        final String message;
        final long expiresInSeconds;
        final int intervalSeconds;

        DeviceCode(String deviceCode, String userCode, String verificationUri, String message,
                   long expiresInSeconds, int intervalSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.message = message;
            this.expiresInSeconds = expiresInSeconds;
            this.intervalSeconds = Math.max(1, intervalSeconds);
        }
    }

    static final class MinecraftAccount {
        final String name;
        final String uuid;
        final String xuid;
        final String minecraftAccessToken;
        final String refreshToken;

        MinecraftAccount(String name, String uuid, String xuid, String minecraftAccessToken,
                         String refreshToken) {
            this.name = name;
            this.uuid = uuid;
            this.xuid = xuid;
            this.minecraftAccessToken = minecraftAccessToken;
            this.refreshToken = refreshToken;
        }
    }

    private static final class HttpResult {
        final int status;
        final JSONObject json;

        HttpResult(int status, JSONObject json) {
            this.status = status;
            this.json = json;
        }
    }

    private MicrosoftAuthentication() {}

    static DeviceCode requestDeviceCode(String clientId) throws Exception {
        requireClientId(clientId);
        HttpResult response = requestForm(MS_BASE + "/devicecode", null,
                "client_id", clientId,
                "scope", SCOPES);
        JSONObject json = response.json;
        if (response.status != 200) throw oauthError(json, response.status);
        String deviceCode = nonEmpty(json, "device_code");
        String userCode = nonEmpty(json, "user_code");
        String verificationUri = json.optString("verification_uri",
                json.optString("verification_url", ""));
        if (!"https://www.microsoft.com/link".equals(verificationUri)
                && !"https://microsoft.com/devicelogin".equals(verificationUri)
                && !"https://www.microsoft.com/devicelogin".equals(verificationUri)
                && !verificationUri.matches("https://login\\.microsoftonline\\.com/.*")) {
            throw new IllegalStateException("A Microsoft retornou um endereço de verificação inesperado.");
        }
        return new DeviceCode(deviceCode, userCode, verificationUri,
                json.optString("message", ""),
                Math.max(1, json.optLong("expires_in", 900)),
                Math.max(1, json.optInt("interval", 5)));
    }

    static MinecraftAccount waitForSignIn(String clientId, DeviceCode deviceCode,
                                           AtomicBoolean cancelled, ProgressListener listener)
            throws Exception {
        long deadline = System.currentTimeMillis() + deviceCode.expiresInSeconds * 1000L;
        int interval = deviceCode.intervalSeconds;
        while (System.currentTimeMillis() < deadline) {
            sleepCancellable(interval, cancelled);
            checkCancelled(cancelled);
            HttpResult response = requestForm(MS_BASE + "/token", null,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id", clientId,
                    "device_code", deviceCode.deviceCode);
            JSONObject json = response.json;
            String oauthError = json.optString("error", "");
            if ("authorization_pending".equals(oauthError)) {
                progress(listener, "Aguardando confirmação", "Conclua o login na página da Microsoft.");
                continue;
            }
            if ("slow_down".equals(oauthError)) {
                interval += 5;
                continue;
            }
            if (response.status != 200) throw oauthError(json, response.status);
            String accessToken = nonEmpty(json, "access_token");
            String refreshToken = nonEmpty(json, "refresh_token");
            progress(listener, "Verificando conta Minecraft", "Autenticação Microsoft concluída.");
            return exchangeAndVerify(accessToken, refreshToken);
        }
        throw new IllegalStateException("O código de login expirou. Inicie o login novamente.");
    }

    static MinecraftAccount refreshAndVerify(String clientId, String refreshToken,
                                              ProgressListener listener) throws Exception {
        requireClientId(clientId);
        progress(listener, "Renovando sessão Microsoft", "Atualizando a sessão antes de iniciar o cliente.");
        HttpResult response = requestForm(MS_BASE + "/token", null,
                "grant_type", "refresh_token",
                "client_id", clientId,
                "refresh_token", refreshToken,
                "scope", SCOPES);
        if (response.status != 200) {
            String error = response.json.optString("error", "");
            if ("invalid_grant".equals(error)) {
                throw new IllegalStateException("A sessão Microsoft expirou. Entre novamente.");
            }
            throw oauthError(response.json, response.status);
        }
        return exchangeAndVerify(nonEmpty(response.json, "access_token"),
                response.json.optString("refresh_token", refreshToken));
    }

    private static MinecraftAccount exchangeAndVerify(String microsoftAccessToken,
                                                       String refreshedToken) throws Exception {
        progress(null, "Autenticando no Xbox Live", "Validando a sessão da conta.");
        JSONObject xblProperties = new JSONObject()
                .put("AuthMethod", "RPS")
                .put("SiteName", "user.auth.xboxlive.com")
                .put("RpsTicket", "d=" + microsoftAccessToken);
        HttpResult xblResponse = requestJson(XBOX_USER, null, new JSONObject()
                .put("Properties", xblProperties)
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT"));
        String xblToken = responseString(xblResponse, "Token", "O Xbox Live recusou a autenticação.");

        progress(null, "Autorizando sessão Xbox", "Verificando a identidade de Minecraft.");
        JSONObject xstsProperties = new JSONObject()
                .put("SandboxId", "RETAIL")
                .put("UserTokens", new JSONArray().put(xblToken));
        HttpResult xstsResponse = requestJson(XSTS, null, new JSONObject()
                .put("Properties", xstsProperties)
                .put("RelyingParty", "rp://api.minecraftservices.com/")
                .put("TokenType", "JWT"));
        long xerr = xstsResponse.json.optLong("XErr", 0);
        if (xerr != 0) throw new IllegalStateException(xstsError(xerr));
        String xstsToken = responseString(xstsResponse, "Token", "O Xbox Live recusou a sessão XSTS.");
        JSONObject claims = xstsResponse.json.optJSONObject("DisplayClaims");
        JSONArray xui = claims == null ? null : claims.optJSONArray("xui");
        JSONObject firstClaim = xui == null || xui.length() == 0 ? null : xui.optJSONObject(0);
        if (firstClaim == null) throw new IllegalStateException("O Xbox Live não retornou a identidade da conta.");
        String userHash = firstClaim.optString("uhs", "");
        if (userHash.isEmpty()) throw new IllegalStateException("O Xbox Live não retornou o identificador da conta.");

        progress(null, "Verificando conta Minecraft", "Consultando os serviços oficiais do Minecraft.");
        JSONObject identity = new JSONObject().put("identityToken",
                "XBL3.0 x=" + userHash + ";" + xstsToken);
        HttpResult minecraftResponse = requestJson(MC_BASE + "/authentication/login_with_xbox",
                null, identity);
        if (minecraftResponse.status == 403) {
            throw new IllegalStateException("Os serviços do Minecraft recusaram este App ID (403). O cadastro da aplicação precisa ser aprovado para acesso às APIs do Minecraft; não é possível ignorar essa verificação.");
        }
        String minecraftToken = responseString(minecraftResponse, "access_token",
                "Os serviços do Minecraft recusaram a sessão.");

        progress(null, "Verificando licença Java Edition", "Consultando os direitos da conta.");
        HttpResult entitlements = requestGet(MC_BASE + "/entitlements/mcstore", minecraftToken);
        if (entitlements.status != 200) {
            throw new IllegalStateException("Não foi possível verificar os direitos de Minecraft Java Edition nos serviços oficiais.");
        }
        JSONArray items = entitlements.json.optJSONArray("items");
        if (!hasJavaEntitlement(items)) {
            throw new IllegalStateException("Esta conta não possui uma licença reconhecida de Minecraft Java Edition (ou acesso incluído ativo). O cliente não será iniciado.");
        }

        progress(null, "Carregando perfil Minecraft", "Confirmando nome e UUID do jogador.");
        HttpResult profile = requestGet(MC_BASE + "/minecraft/profile", minecraftToken);
        if (profile.status == 404) {
            throw new IllegalStateException("A conta tem acesso ao jogo, mas ainda não tem perfil Minecraft. Crie o perfil no site oficial e entre novamente.");
        }
        if (profile.status != 200) {
            throw new IllegalStateException("Não foi possível consultar o perfil Minecraft.");
        }
        String name = nonEmpty(profile.json, "name");
        String uuid = profile.json.optString("id", "").replace("-", "");
        if (!name.matches("[A-Za-z0-9_]{1,16}") || !uuid.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalStateException("Os serviços retornaram um perfil Minecraft inválido.");
        }
        return new MinecraftAccount(name, uuid.toLowerCase(java.util.Locale.ROOT),
                firstClaim.optString("xid", ""), minecraftToken, refreshedToken);
    }

    private static HttpResult requestForm(String url, String bearer, String... fields) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            if (body.length() > 0) body.append('&');
            body.append(formEncode(fields[i])).append('=').append(formEncode(fields[i + 1]));
        }
        return request(url, bearer, "application/x-www-form-urlencoded",
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResult requestJson(String url, String bearer, JSONObject body) throws Exception {
        return request(url, bearer, "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResult requestGet(String url, String bearer) throws Exception {
        HttpURLConnection connection = open(url, bearer, null);
        try {
            return readResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpResult request(String url, String bearer, String contentType, byte[] body)
            throws Exception {
        HttpURLConnection connection = open(url, bearer, contentType);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            return readResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String bearer, String contentType)
            throws Exception {
        if (url == null || !url.startsWith("https://")) {
            throw new IllegalArgumentException("Um endpoint de autenticação inseguro foi recusado.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "MobileHorizonNative/0.3.0");
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType);
        if (bearer != null && !bearer.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        return connection;
    }

    private static HttpResult readResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return new HttpResult(status, new JSONObject());
        byte[] bytes;
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("A resposta de autenticação excedeu o limite permitido.");
                }
                output.write(buffer, 0, read);
            }
            bytes = output.toByteArray();
        }
        JSONObject json;
        try {
            json = bytes.length == 0 ? new JSONObject()
                    : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception error) {
            json = new JSONObject();
        }
        return new HttpResult(status, json);
    }

    private static String responseString(HttpResult response, String key, String fallback)
            throws Exception {
        if (response.status < 200 || response.status >= 300) {
            String error = response.json.optString("error", "");
            if ("XErr".equals(error)) throw new IllegalStateException(xstsError(response.json.optLong("XErr", 0)));
            throw new IllegalStateException(fallback + " (HTTP " + response.status + ").");
        }
        return nonEmpty(response.json, key);
    }

    private static String nonEmpty(JSONObject json, String key) throws Exception {
        String value = json.optString(key, "");
        if (value.isEmpty() || "null".equals(value)) {
            throw new IllegalStateException("A resposta oficial não continha o campo " + key + ".");
        }
        return value;
    }

    private static String oauthError(JSONObject json, int status) {
        String error = json.optString("error", "");
        switch (error) {
            case "authorization_declined":
                return "O login Microsoft foi recusado no navegador.";
            case "expired_token":
                return "O código de login expirou. Tente novamente.";
            case "bad_verification_code":
                return "O código informado não foi reconhecido. Inicie o login novamente.";
            case "invalid_client":
            case "unauthorized_client":
                return "O Microsoft recusou o App ID deste projeto. Configure uma aplicação Microsoft própria e habilite o fluxo de cliente público.";
            case "invalid_grant":
                return "A sessão Microsoft expirou. Entre novamente.";
            default:
                return "Falha na autenticação Microsoft (HTTP " + status
                        + (error.isEmpty() ? "" : ", " + error) + ").";
        }
    }

    private static String xstsError(long code) {
        if (code == 2148916233L) {
            return "Esta conta Microsoft ainda não tem um perfil Xbox. Configure o perfil nos serviços oficiais e tente novamente.";
        }
        if (code == 2148916235L) {
            return "O Xbox Live não está disponível para esta região.";
        }
        if (code == 2148916236L || code == 2148916237L) {
            return "A conta precisa concluir a verificação de idade exigida pelo Xbox.";
        }
        if (code == 2148916238L) {
            return "Esta conta infantil precisa das permissões da família Microsoft para usar o Xbox Live.";
        }
        return "O Xbox Live recusou a autenticação (código " + code + ").";
    }

    static boolean isValidClientId(String clientId) {
        return clientId != null && clientId.trim().matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    static boolean hasJavaEntitlement(JSONArray items) {
        if (items == null) return false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "");
            if ("game_minecraft".equals(name) || "product_minecraft".equals(name)) return true;
        }
        return false;
    }

    static void requireClientId(String clientId) {
        if (!isValidClientId(clientId)) {
            throw new IllegalStateException("O login Microsoft não está configurado neste build. Defina HORIZON_MSA_CLIENT_ID com o App ID próprio do MobileHorizonNative.");
        }
    }

    private static String formEncode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static void progress(ProgressListener listener, String stage, String detail) {
        if (listener != null) listener.onProgress(stage, detail);
    }

    private static void sleepCancellable(int seconds, AtomicBoolean cancelled) throws InterruptedException {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            checkCancelled(cancelled);
            Thread.sleep(Math.min(200L, Math.max(1L, until - System.currentTimeMillis())));
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if ((cancelled != null && cancelled.get()) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Login Microsoft cancelado.");
        }
    }
}
