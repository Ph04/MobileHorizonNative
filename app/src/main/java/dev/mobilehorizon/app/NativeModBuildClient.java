package dev.mobilehorizon.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipFile;

/** Drives the authenticated GitHub Actions .hnmod build from the Android app. */
final class NativeModBuildClient {
    static final String REPOSITORY = "Ph04/MobileHorizonNative";
    static final String WORKFLOW = "horizon-native-mod-build.yml";
    static final String BUILD_REF = "feature/fabric-java-bridge-test";
    static final String ARTIFACT_NAME = "mobilehorizon-native-runtime";
    private static final String API = "https://api.github.com";
    private static final long MAX_UPLOAD_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_ARTIFACT_BYTES = 440L * 1024L * 1024L;
    private static final long BUILD_TIMEOUT_MS = 90L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    interface Progress {
        void update(String message);
    }

    private NativeModBuildClient() { }

    static String buildAndInstall(Context context, String instanceId, String token,
                                  Progress progress) throws Exception {
        if (token == null || token.trim().length() < 30) {
            throw new IOException("Configure um token Fine-grained do GitHub antes de compilar.");
        }
        String baseVersion = HorizonRuntimeManager.currentVersion(context);
        if (baseVersion == null || baseVersion.isEmpty()) {
            throw new IOException("Instale primeiro uma runtime Horizon limpa.");
        }
        String baseTag = HorizonNativeModManager.baseRuntimeReleaseTag(baseVersion);
        if (!baseTag.startsWith("horizon-runtime-")) {
            throw new IOException("Não foi possível identificar a Release da runtime Horizon instalada.");
        }

        List<File> archives = HorizonNativeModManager.selectedArchiveFiles(context, instanceId);
        long uploadBytes = 0L;
        for (File archive : archives) {
            uploadBytes += archive.length();
            if (uploadBytes > MAX_UPLOAD_BYTES) {
                throw new IOException("Os módulos selecionados excedem 256 MiB.");
            }
        }

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String releaseTag = "hnmod-input-" + System.currentTimeMillis() + "-" + suffix;
        long releaseId = -1L;
        boolean dispatchSent = false;
        File artifactFile = new File(context.getCacheDir(),
                "native-mod-runtime-" + instanceId + "-" + System.currentTimeMillis() + ".zip");
        try {
            progress.update("Criando uma Release temporária para os " + archives.size()
                    + " módulos selecionados…");
            JSONObject releaseBody = new JSONObject();
            releaseBody.put("tag_name", releaseTag);
            releaseBody.put("target_commitish", "main");
            releaseBody.put("name", "MobileHorizon temporary native build " + suffix);
            releaseBody.put("body", "Temporary public upload used by MobileHorizonNative. "
                    + "The app removes this release after the build completes.");
            releaseBody.put("draft", false);
            releaseBody.put("prerelease", true);
            releaseBody.put("make_latest", "false");
            JSONObject release = requestJson("POST", "/repos/" + REPOSITORY + "/releases",
                    token, releaseBody);
            releaseId = release.getLong("id");
            String uploadUrl = release.getString("upload_url");
            int uploaded = 0;
            for (File archive : archives) {
                uploaded++;
                progress.update("Enviando módulo " + uploaded + "/" + archives.size()
                        + ": " + archive.getName());
                uploadReleaseAsset(uploadUrl, archive, token);
            }

            progress.update("Enviando pedido de compilação para Horizon Android ARM64…");
            JSONObject dispatch = new JSONObject();
            dispatch.put("ref", BUILD_REF);
            JSONObject inputs = new JSONObject();
            inputs.put("mod_release_tag", releaseTag);
            inputs.put("base_runtime_tag", baseTag);
            dispatch.put("inputs", inputs);
            requestNoContent("POST", "/repos/" + REPOSITORY + "/actions/workflows/"
                    + WORKFLOW + "/dispatches", token, dispatch);
            dispatchSent = true;

            long dispatchTime = System.currentTimeMillis();
            long deadline = dispatchTime + BUILD_TIMEOUT_MS;
            long runId = -1L;
            String runUrl = null;
            JSONObject completedRun = null;
            while (System.currentTimeMillis() < deadline) {
                if (runId < 0) {
                    JSONObject runs = requestJson("GET",
                            "/repos/" + REPOSITORY + "/actions/workflows/" + WORKFLOW
                                    + "/runs?event=workflow_dispatch&branch="
                                    + urlEncode(BUILD_REF) + "&per_page=20",
                            token, null);
                    JSONArray values = runs.optJSONArray("workflow_runs");
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            JSONObject candidate = values.optJSONObject(i);
                            if (candidate == null
                                    || !"workflow_dispatch".equals(candidate.optString("event", ""))
                                    || !BUILD_REF.equals(candidate.optString("head_branch", ""))) {
                                continue;
                            }
                            String runTitle = candidate.optString("display_title", "") + " "
                                    + candidate.optString("name", "");
                            if (!runTitle.contains(releaseTag)) continue;
                            String created = candidate.optString("created_at", "");
                            try {
                                if (Instant.parse(created).toEpochMilli() >= dispatchTime - 60_000L) {
                                    runId = candidate.getLong("id");
                                    runUrl = candidate.optString("html_url", null);
                                    progress.update("Compilação iniciada no GitHub Actions. Execução #"
                                            + runId + "…");
                                    break;
                                }
                            } catch (Exception ignored) { }
                        }
                    }
                    if (runId < 0) {
                        progress.update("Pedido enviado. Aguardando a fila do GitHub Actions…");
                        Thread.sleep(10_000L);
                        continue;
                    }
                }

                JSONObject run = requestJson("GET",
                        "/repos/" + REPOSITORY + "/actions/runs/" + runId, token, null);
                String status = run.optString("status", "");
                runUrl = run.optString("html_url", runUrl);
                if ("completed".equals(status)) {
                    completedRun = run;
                    break;
                }
                String currentJob = "in_progress".equals(status)
                        ? "A compilação ARM64 está em andamento…" : "Compilação na fila…";
                progress.update(currentJob + " Execução #" + runId + ".");
                Thread.sleep(15_000L);
            }
            if (completedRun == null) {
                throw new IOException("A compilação excedeu o limite de 90 minutos. "
                        + (runUrl == null ? "" : "Consulte: " + runUrl));
            }
            String conclusion = completedRun.optString("conclusion", "");
            if (!"success".equals(conclusion)) {
                throw new IOException("O GitHub Actions terminou com resultado '" + conclusion
                        + "'. Consulte os logs: " + (runUrl == null ? "" : runUrl));
            }

            progress.update("Build concluído. Localizando o artefato ARM64…");
            JSONObject artifact = findArtifact(token, runId);
            if (artifact == null) {
                throw new IOException("A Action terminou com sucesso, mas não publicou "
                        + ARTIFACT_NAME + ".");
            }
            long artifactSize = artifact.optLong("size_in_bytes", 0L);
            if (artifact.optBoolean("expired", false)
                    || artifactSize <= 0 || artifactSize > MAX_ARTIFACT_BYTES) {
                throw new IOException("O artefato expirou ou excede o limite de 440 MiB.");
            }
            String downloadUrl = artifact.optString("archive_download_url", "");
            if (downloadUrl.isEmpty()) {
                throw new IOException("O GitHub não forneceu o endereço de download do artefato.");
            }
            progress.update("Baixando a runtime compilada (" + formatBytes(artifactSize) + ")…");
            downloadArtifact(downloadUrl, token, artifactFile, artifactSize, progress);

            // Validate archive structure before handing it to the strict per-instance installer.
            try (ZipFile ignored = new ZipFile(artifactFile)) {
                // The installer verifies manifest, engine ELF/hash, selected module hashes
                // and every declared post-build data file before activation.
            }
            progress.update("Validando e instalando a runtime nesta instância…");
            String version = HorizonNativeModManager.installBuildArtifact(
                    context, instanceId, artifactFile, baseVersion);
            progress.update("Runtime " + version + " instalada nesta instância.");
            return version;
        } finally {
            if (artifactFile.exists() && !artifactFile.delete()) {
                android.util.Log.w("HorizonNativeBuild",
                        "Não foi possível remover o ZIP temporário do build.");
            }
            if (releaseId > 0L) {
                progress.update("Removendo a Release temporária com os .hnmod enviados…");
                boolean releaseDeleted = false;
                try {
                    requestNoContent("DELETE", "/repos/" + REPOSITORY
                            + "/releases/" + releaseId, token, null);
                    releaseDeleted = true;
                } catch (Exception cleanupError) {
                    android.util.Log.w("HorizonNativeBuild",
                            "Não foi possível remover a Release temporária " + releaseTag + ".");
                }
                try {
                    requestNoContent("DELETE", "/repos/" + REPOSITORY
                            + "/git/refs/tags/" + urlEncode(releaseTag), token, null);
                } catch (Exception cleanupError) {
                    android.util.Log.w("HorizonNativeBuild",
                            "Não foi possível remover a tag temporária " + releaseTag + ".");
                }
                if (dispatchSent && !releaseDeleted) {
                    progress.update("Build encerrado. Atenção: a Release pública temporária "
                            + releaseTag + " não pôde ser removida automaticamente.");
                }
            }
        }
    }

    private static JSONObject findArtifact(String token, long runId) throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            JSONObject response = requestJson("GET",
                    "/repos/" + REPOSITORY + "/actions/runs/" + runId + "/artifacts",
                    token, null);
            JSONArray artifacts = response.optJSONArray("artifacts");
            if (artifacts != null) {
                for (int i = 0; i < artifacts.length(); i++) {
                    JSONObject item = artifacts.optJSONObject(i);
                    if (item != null && ARTIFACT_NAME.equals(item.optString("name", ""))) {
                        return item;
                    }
                }
            }
            Thread.sleep(2_000L);
        }
        return null;
    }

    private static JSONObject requestJson(String method, String path, String token,
                                          JSONObject body) throws Exception {
        HttpURLConnection connection = openApi(method, path, token);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw httpError(connection, code, token);
            }
            byte[] response = readResponse(connection.getInputStream(), 1024 * 1024);
            if (response.length == 0) return new JSONObject();
            try {
                return new JSONObject(new String(response, StandardCharsets.UTF_8));
            } catch (JSONException error) {
                throw new IOException("Resposta inesperada do GitHub (" + code + ").", error);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void requestNoContent(String method, String path, String token,
                                         JSONObject body) throws Exception {
        HttpURLConnection connection = openApi(method, path, token);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND && "DELETE".equals(method)) return;
            if (code < 200 || code >= 300) throw httpError(connection, code, token);
            InputStream input = connection.getInputStream();
            if (input != null) input.close();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openApi(String method, String path, String token)
            throws IOException {
        URL url = new URL(API + path);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !"api.github.com".equals(url.getHost())) {
            throw new IOException("Destino GitHub não permitido.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "MobileHorizonNative-AndroidBuilder/1.0");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private static void uploadReleaseAsset(String templateUrl, File archive, String token)
            throws Exception {
        String endpoint = templateUrl.split("\\{", 2)[0];
        URL url = new URL(endpoint + "?name=" + urlEncode(archive.getName()));
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"uploads.github.com".equals(url.getHost())) {
            throw new IOException("O GitHub forneceu um destino de upload inválido.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(10 * 60 * 1000);
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "MobileHorizonNative-AndroidBuilder/1.0");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setFixedLengthStreamingMode(archive.length());
        try {
            try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(archive));
                 OutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > archive.length()) throw new IOException("O .hnmod mudou durante o envio.");
                    output.write(buffer, 0, read);
                }
                if (total != archive.length()) throw new IOException("Upload .hnmod incompleto.");
            }
            int code = connection.getResponseCode();
            if (code != 201) throw httpError(connection, code, token);
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadArtifact(String apiUrl, String token, File outputFile,
                                         long expectedBytes, Progress progress) throws Exception {
        URL url = new URL(apiUrl);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"api.github.com".equals(url.getHost())) {
            throw new IOException("Endereço do artefato não é do GitHub API.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative-AndroidBuilder/1.0");
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                if (location == null || !location.startsWith("https://")) {
                    throw new IOException("Redirecionamento de download inválido.");
                }
                downloadSignedUrl(location, outputFile, expectedBytes, progress);
                return;
            }
            if (code != HttpURLConnection.HTTP_OK) throw httpError(connection, code, token);
            try (InputStream input = connection.getInputStream()) {
                writeDownload(input, outputFile, expectedBytes, progress);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadSignedUrl(String urlValue, File outputFile,
                                          long expectedBytes, Progress progress) throws Exception {
        URL url = new URL(urlValue);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("URL assinada do artefato precisa usar HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative-AndroidBuilder/1.0");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Falha ao baixar o artefato do GitHub (HTTP " + code + ").");
            }
            try (InputStream input = connection.getInputStream()) {
                writeDownload(input, outputFile, expectedBytes, progress);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void writeDownload(InputStream input, File outputFile,
                                      long expectedBytes, Progress progress) throws Exception {
        File parent = outputFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Não foi possível preparar o cache do artefato.");
        }
        long total = 0L;
        long lastUpdate = 0L;
        try (InputStream in = new BufferedInputStream(input);
             FileOutputStream file = new FileOutputStream(outputFile, false);
             OutputStream out = new BufferedOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ARTIFACT_BYTES || total > expectedBytes) {
                    throw new IOException("O artefato excedeu o tamanho informado pelo GitHub.");
                }
                out.write(buffer, 0, read);
                if (total - lastUpdate >= 8L * 1024L * 1024L) {
                    lastUpdate = total;
                    progress.update("Baixando runtime: " + formatBytes(total) + " de "
                            + formatBytes(expectedBytes) + "…");
                }
            }
            out.flush();
            file.getFD().sync();
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            outputFile.delete();
            throw error;
        }
        if (total != expectedBytes || total <= 0L) {
            //noinspection ResultOfMethodCallIgnored
            outputFile.delete();
            throw new IOException("Download do artefato incompleto ou divergente.");
        }
    }

    private static IOException httpError(HttpURLConnection connection, int code, String token) {
        String detail = "";
        try {
            InputStream error = connection.getErrorStream();
            if (error != null) {
                byte[] bytes = readResponse(error, 64 * 1024);
                detail = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) { }
        if (token != null && !token.isEmpty()) detail = detail.replace(token, "[token oculto]");
        String message;
        try {
            JSONObject json = new JSONObject(detail);
            message = json.optString("message", detail);
        } catch (Exception ignored) {
            message = detail;
        }
        if (message.length() > 600) message = message.substring(0, 600);
        if (code == 401 || code == 403) {
            return new IOException("GitHub recusou o token ou suas permissões (HTTP " + code
                    + "). Verifique Contents: read/write e Actions: read/write. " + message);
        }
        return new IOException("Erro na API do GitHub (HTTP " + code + "): " + message);
    }

    private static byte[] readResponse(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > maxBytes) {
                    throw new IOException("Resposta do GitHub excedeu o limite.");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String urlEncode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String formatBytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
