package dev.mobilehorizon.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** App-side settings, pinned ViaProxy download and Android Java-runtime selection. */
final class CrossVersionManager {
    static final String VIA_PROXY_VERSION = "3.4.14";
    static final String VIA_PROXY_SHA256 =
            "2894bbfb2f4342f8fde887af6c689313efd5b0e46a8006775eea0e21e2948ab5";
    static final String VIA_PROXY_URL =
            "https://github.com/ViaVersion/ViaProxy/releases/download/v3.4.14/ViaProxy-3.4.14.jar";
    static final long MAX_DOWNLOAD_BYTES = 128L * 1024L * 1024L;
    static final String PREFS = "crossversion_state";
    static final String KEY_SIGN_IN_STATE = "sign_in_state";
    static final String KEY_SIGN_IN_CODE = "sign_in_code";
    static final String KEY_SIGN_IN_MESSAGE = "sign_in_message";

    private static final String[] TARGET_VERSIONS = {
            "auto", "1.8", "1.9", "1.12.2", "1.16.5",
            "1.20.1", "1.21.1", "1.21.4", "26.3"
    };

    interface ProgressListener {
        void onProgress(String stage, long completed, long total);
    }

    static final class JavaRuntime {
        final File root;
        final File javaHome;
        final File java;
        final File temporaryDirectory;
        final Map<String, String> environment;
        final String provider;

        JavaRuntime(File root, File javaHome, File java, File temporaryDirectory,
                    Map<String, String> environment, String provider) {
            this.root = root;
            this.javaHome = javaHome;
            this.java = java;
            this.temporaryDirectory = temporaryDirectory;
            this.environment = Collections.unmodifiableMap(new HashMap<>(environment));
            this.provider = provider;
        }
    }

    private CrossVersionManager() { }

    static File dataDirectory(Context context) throws IOException {
        File files = context.getApplicationContext().getFilesDir().getCanonicalFile();
        File root = new File(files, "crossversion").getCanonicalFile();
        if (!files.equals(root.getParentFile())) {
            throw new IOException("A pasta CrossVersion saiu do armazenamento privado do app.");
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta CrossVersion.");
        }
        return root;
    }

    static File viaProxyDirectory(Context context) throws IOException {
        File root = dataDirectory(context);
        File proxy = new File(root, "viaproxy").getCanonicalFile();
        if (!root.equals(proxy.getParentFile())) {
            throw new IOException("A pasta ViaProxy saiu do diretório CrossVersion.");
        }
        if (!proxy.isDirectory() && !proxy.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta ViaProxy.");
        }
        return proxy;
    }

    static File viaProxyJar(Context context) throws IOException {
        return new File(viaProxyDirectory(context), "ViaProxy-" + VIA_PROXY_VERSION + ".jar");
    }

    static boolean isViaProxyReady(Context context) {
        try {
            File jar = viaProxyJar(context);
            return jar.isFile() && jar.length() > 0
                    && VIA_PROXY_SHA256.equals(sha256(jar));
        } catch (Exception ignored) {
            return false;
        }
    }

    static void ensureViaProxyDownloaded(Context context, ProgressListener listener)
            throws Exception {
        File jar = viaProxyJar(context);
        if (jar.isFile() && VIA_PROXY_SHA256.equals(sha256(jar))) {
            publish(listener, "ViaProxy já está instalado e verificado", jar.length(), jar.length());
            return;
        }

        File partial = new File(jar.getParentFile(), jar.getName() + ".part");
        if (partial.exists() && !partial.delete()) {
            throw new IOException("Não foi possível limpar o download parcial do ViaProxy.");
        }

        HttpURLConnection connection = null;
        try {
            publish(listener, "Baixando ViaProxy " + VIA_PROXY_VERSION, 0, -1);
            URL url = new URL(VIA_PROXY_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "MobileHorizonNative-CrossVersion/1");
            connection.connect();

            URL finalUrl = connection.getURL();
            String host = finalUrl.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(finalUrl.getProtocol())
                    || !("github.com".equals(host)
                    || "objects.githubusercontent.com".equals(host)
                    || "release-assets.githubusercontent.com".equals(host))) {
                throw new IOException("O download do ViaProxy foi redirecionado para um host não autorizado.");
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("O GitHub respondeu HTTP " + status + " ao baixar o ViaProxy.");
            }
            long declared = connection.getContentLengthLong();
            if (declared > MAX_DOWNLOAD_BYTES) {
                throw new IOException("O arquivo ViaProxy excede o limite de tamanho permitido.");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[128 * 1024];
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("O download ViaProxy excedeu 128 MiB.");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    publish(listener, "Baixando ViaProxy " + VIA_PROXY_VERSION, total, declared);
                }
                output.flush();
                output.getFD().sync();
            }

            String found = hex(digest.digest());
            if (!VIA_PROXY_SHA256.equals(found)) {
                throw new IOException("SHA-256 do ViaProxy não corresponde à versão oficial fixada. Esperado "
                        + VIA_PROXY_SHA256 + ", recebido " + found + ".");
            }
            if (jar.exists() && !jar.delete()) {
                throw new IOException("Não foi possível substituir o ViaProxy antigo.");
            }
            if (!partial.renameTo(jar)) {
                copyFile(partial, jar);
                if (!partial.delete()) partial.deleteOnExit();
            }
            publish(listener, "ViaProxy " + VIA_PROXY_VERSION + " verificado", total, total);
        } finally {
            if (connection != null) connection.disconnect();
            if (partial.exists()) partial.delete();
        }
    }

    static JavaRuntime findJavaRuntime(Context context) throws IOException {
        boolean preferPojav = context.getSharedPreferences(
                PojavRuntimeInstaller.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PojavRuntimeInstaller.PREF_USE_POJAV, false);

        JavaRuntime preferred = preferPojav
                ? getPojavRuntime(context) : getTermuxRuntime(context);
        if (preferred != null) return preferred;

        JavaRuntime fallback = preferPojav
                ? getTermuxRuntime(context) : getPojavRuntime(context);
        if (fallback != null) return fallback;

        throw new IOException("Java 25 ARM64 não está preparado. Execute a preparação completa dos recursos do Horizon ou instale o JRE Pojav/Mojo em Configurações.");
    }

    private static JavaRuntime getTermuxRuntime(Context context) {
        JdkRuntimeInstaller.RuntimePaths runtime =
                JdkRuntimeInstaller.getInstalledIfReady(context);
        if (runtime == null) return null;
        File temporary = new File(runtime.root, "tmp");
        File home = new File(runtime.root, "home");
        if (!ensureDirectory(temporary) || !ensureDirectory(home)) return null;
        try {
            Map<String, String> environment = runtime.processEnvironment(temporary);
            return new JavaRuntime(runtime.root, runtime.javaHome, runtime.java,
                    temporary, environment, "Termux OpenJDK " + runtime.javaHome.getName());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JavaRuntime getPojavRuntime(Context context) {
        PojavRuntimeInstaller.RuntimePaths runtime =
                PojavRuntimeInstaller.getInstalledIfReady(context);
        if (runtime == null) return null;
        File temporary = new File(runtime.root, "tmp");
        File home = new File(runtime.root, "home");
        if (!ensureDirectory(temporary) || !ensureDirectory(home)) return null;
        try {
            Map<String, String> environment = runtime.processEnvironment(temporary);
            return new JavaRuntime(runtime.root, runtime.javaHome, runtime.java,
                    temporary, environment, "Pojav/Mojo OpenJDK " + runtime.version);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean ensureDirectory(File directory) {
        return directory.isDirectory() || (directory.mkdirs() && directory.isDirectory());
    }

    static JSONObject readConfig(Context context) throws IOException {
        File file = new File(dataDirectory(context), "config.json");
        if (!file.isFile()) {
            JSONObject defaults = new JSONObject();
            try {
                defaults.put("enabled", true);
                defaults.put("always", false);
                defaults.put("servers", new JSONObject());
            } catch (JSONException impossible) {
                throw new IOException("Não foi possível criar as configurações padrão.", impossible);
            }
            writeConfig(context, defaults);
            return defaults;
        }
        if (file.length() <= 0 || file.length() > 1024 * 1024) {
            throw new IOException("O arquivo de configurações CrossVersion está vazio ou excede 1 MiB.");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new IOException("As configurações CrossVersion contêm JSON inválido.", error);
        }
    }

    static synchronized void writeConfig(Context context, JSONObject config) throws IOException {
        File root = dataDirectory(context);
        File target = new File(root, "config.json");
        File partial = new File(root, ".config.json.part");
        byte[] bytes;
        try {
            bytes = config.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IOException("Não foi possível serializar as configurações CrossVersion.", error);
        }
        try (FileOutputStream output = new FileOutputStream(partial)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            partial.delete();
            throw new IOException("Não foi possível substituir as configurações CrossVersion.");
        }
        if (!partial.renameTo(target)) {
            copyFile(partial, target);
            if (!partial.delete()) partial.deleteOnExit();
        }
    }

    static synchronized void setEnabled(Context context, boolean enabled) throws IOException {
        JSONObject config = readConfig(context);
        try {
            config.put("enabled", enabled);
        } catch (JSONException error) {
            throw new IOException("Não foi possível atualizar o estado CrossVersion.", error);
        }
        writeConfig(context, config);
    }

    static synchronized void setAlways(Context context, boolean always) throws IOException {
        JSONObject config = readConfig(context);
        try {
            config.put("always", always);
        } catch (JSONException error) {
            throw new IOException("Não foi possível atualizar o modo sempre ViaProxy.", error);
        }
        writeConfig(context, config);
    }

    static synchronized void setServerVersion(Context context, String address, String version)
            throws IOException {
        String normalized = normalizeAddress(address);
        boolean knownVersion = false;
        for (String allowed : TARGET_VERSIONS) if (allowed.equals(version)) knownVersion = true;
        if (!knownVersion) throw new IOException("Versão de destino não reconhecida.");

        JSONObject config = readConfig(context);
        JSONObject servers = config.optJSONObject("servers");
        if (servers == null) servers = new JSONObject();
        try {
            if ("auto".equals(version)) servers.remove(normalized);
            else servers.put(normalized, version);
            config.put("servers", servers);
        } catch (JSONException error) {
            throw new IOException("Não foi possível salvar a versão do servidor.", error);
        }
        writeConfig(context, config);
    }

    static List<String> targetVersions() {
        ArrayList<String> versions = new ArrayList<>();
        Collections.addAll(versions, TARGET_VERSIONS);
        return Collections.unmodifiableList(versions);
    }

    static List<String> serverOverrides(Context context) throws IOException {
        JSONObject servers = readConfig(context).optJSONObject("servers");
        if (servers == null) return Collections.emptyList();
        ArrayList<String> entries = new ArrayList<>();
        Iterator<String> keys = servers.keys();
        while (keys.hasNext()) {
            String address = keys.next();
            String version = servers.optString(address, "auto");
            entries.add(address + " → " + version);
        }
        Collections.sort(entries, String.CASE_INSENSITIVE_ORDER);
        return Collections.unmodifiableList(entries);
    }

    static boolean hasMicrosoftAccount(Context context) {
        try {
            File saves = new File(viaProxyDirectory(context), "saves.json");
            if (!saves.isFile() || saves.length() <= 0 || saves.length() > 2L * 1024L * 1024L) {
                return false;
            }
            JSONObject json;
            try (FileInputStream input = new FileInputStream(saves)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                json = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
            JSONArray accounts = json.optJSONArray("accountsV4");
            if (accounts == null) return false;
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject account = accounts.optJSONObject(i);
                if (account != null && account.optString("accountType", "")
                        .endsWith("MicrosoftAccount")) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static void deleteDownloadedJars(Context context) throws IOException {
        File proxy = viaProxyDirectory(context);
        deleteJarFiles(proxy);
        deleteJarFiles(new File(proxy, "plugins"));
    }

    private static void deleteJarFiles(File directory) throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                if (!entry.delete()) throw new IOException("Não foi possível excluir " + entry.getName() + ".");
            }
        }
    }

    static String normalizeAddress(String value) throws IOException {
        String address = value == null ? "" : value.trim();
        if (address.isEmpty() || address.length() > 255
                || address.contains("://") || address.indexOf('/') >= 0
                || address.chars().anyMatch(Character::isWhitespace)
                || !address.matches("[A-Za-z0-9._:\\[\\]-]+")) {
            throw new IOException("Informe um endereço de servidor válido, sem o prefixo https://.");
        }
        return address.toLowerCase(Locale.ROOT);
    }

    private static void publish(ProgressListener listener, String stage, long completed, long total) {
        if (listener != null) listener.onProgress(stage, completed, total);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            output.getFD().sync();
        }
    }
}
