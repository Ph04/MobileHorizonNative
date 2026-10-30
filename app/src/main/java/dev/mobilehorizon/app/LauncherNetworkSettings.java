package dev.mobilehorizon.app;

import android.content.Context;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;

/** One HTTP proxy preference for launcher downloads and the native Horizon process. */
final class LauncherNetworkSettings {
    private static final String PREFS = "mobilehorizon_interface";
    private static final String KEY_HTTP_PROXY = "http_proxy";
    private LauncherNetworkSettings() {}

    static String configuredProxy(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HTTP_PROXY, "");
    }

    static void setProxy(Context context, String value) throws IOException {
        String normalized = validate(value);
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HTTP_PROXY, normalized).apply();
    }

    static String validate(String raw) throws IOException {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        final URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException error) {
            throw new IOException("Endereço de proxy inválido. Use http://host:porta.", error);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Somente proxy HTTP é aceito nesta versão. Use http://host:porta.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IOException("Proxy com usuário/senha embutidos não é aceito nesta versão.");
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    && !"/".equals(uri.getRawPath()))
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IOException("Proxy inválido. Informe somente http://host:porta.");
        }
        return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    static URLConnection open(URL url) throws IOException {
        Proxy proxy = proxyFor(url);
        return url.openConnection(proxy);
    }

    static Proxy proxyFor(URL url) throws IOException {
        if (isDirectHost(url.getHost())) return Proxy.NO_PROXY;
        String configured = configuredProxyFromUrlContext();
        if (configured.isEmpty()) return Proxy.NO_PROXY;
        URI uri = URI.create(validate(configured));
        return new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort()));
    }

    private static String configuredProxyFromUrlContext() {
        // HTTP clients pass through open(Context, URL) where the preference can be read.
        // Kept as a separate hook to make the no-context overload impossible to accidentally use.
        return "";
    }

    static URLConnection open(Context context, URL url) throws IOException {
        if (isDirectHost(url.getHost())) return url.openConnection(Proxy.NO_PROXY);
        String configured = configuredProxy(context);
        if (configured.isEmpty()) return url.openConnection();
        URI uri = URI.create(validate(configured));
        return url.openConnection(new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort())));
    }

    static void applyToNativeProcess(android.system.Os ignored, Context context)
            throws android.system.ErrnoException {
        // Placeholder retained only for source compatibility; use applyToNativeProcess(context).
        applyToNativeProcess(context);
    }

    static void applyToNativeProcess(Context context) throws android.system.ErrnoException {
        String configured = configuredProxy(context);
        if (configured == null || configured.isEmpty()) {
            android.system.Os.unsetenv("HTTP_PROXY");
            android.system.Os.unsetenv("HTTPS_PROXY");
            android.system.Os.unsetenv("ALL_PROXY");
            android.system.Os.unsetenv("http_proxy");
            android.system.Os.unsetenv("https_proxy");
            android.system.Os.unsetenv("all_proxy");
            return;
        }
        String safe = validate(configured);
        android.system.Os.setenv("HTTP_PROXY", safe, true);
        android.system.Os.setenv("HTTPS_PROXY", safe, true);
        android.system.Os.setenv("ALL_PROXY", safe, true);
        android.system.Os.setenv("http_proxy", safe, true);
        android.system.Os.setenv("https_proxy", safe, true);
        android.system.Os.setenv("all_proxy", safe, true);
        android.system.Os.setenv("NO_PROXY", "localhost,127.0.0.1,::1", true);
        android.system.Os.setenv("no_proxy", "localhost,127.0.0.1,::1", true);
    }

    static boolean isDirectHost(String rawHost) {
        if (rawHost == null) return false;
        String host = rawHost.toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]");
    }
}
