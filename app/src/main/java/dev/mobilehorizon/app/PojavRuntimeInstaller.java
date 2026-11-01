package dev.mobilehorizon.app;

import android.content.Context;
import android.os.StatFs;
import android.system.Os;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class PojavRuntimeInstaller {
    static final String PREFS_NAME = "java-bridge-preferences";
    static final String PREF_USE_POJAV = "use_pojav_mojo_runtime";

    private static final String VERSION_URL =
            "https://mojolauncher.github.io/jre-download/components/jre-25/version";
    private static final String COMPONENT_BASE =
            "https://mojolauncher.github.io/jre-download/components/jre-25/";
    private static final String CERT_ASSET = "horizon-prepare/pojav-jre-cert.pem";
    private static final String UNIVERSAL = "universal.tar.xz";
    private static final String ARM64 = "bin-arm64.tar.xz";
    private static final long MAX_COMPONENT_BYTES = 64L * 1024L * 1024L;
    private static final long MIB = 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;

    interface ProgressListener {
        void onProgress(String stage, String detail, int completed, int total);
    }

    static final class RuntimePaths {
        final File root;
        final File javaHome;
        final File java;
        final File libjvm;
        final String version;

        RuntimePaths(File root) throws IOException {
            this.root = root;
            javaHome = root;
            java = new File(root, "bin/java");
            libjvm = new File(root, "lib/server/libjvm.so");
            version = readReleaseVersion(new File(root, "release"));
        }

        Map<String, String> processEnvironment(File temporaryDirectory) throws IOException {
            ensureDirectory(new File(root, "home"));
            ensureDirectory(temporaryDirectory);
            Map<String, String> env = new HashMap<>();
            String oldPath = System.getenv("PATH");
            env.put("JAVA_HOME", javaHome.getAbsolutePath());
            env.put("PATH", new File(javaHome, "bin").getAbsolutePath()
                    + (oldPath == null || oldPath.isEmpty() ? "" : ":" + oldPath));
            String libs = new File(javaHome, "lib").getAbsolutePath()
                    + ":" + new File(javaHome, "lib/server").getAbsolutePath()
                    + ":" + new File(javaHome, "lib/jli").getAbsolutePath();
            String oldLibs = System.getenv("LD_LIBRARY_PATH");
            if (oldLibs != null && !oldLibs.isEmpty()) libs += ":" + oldLibs;
            env.put("LD_LIBRARY_PATH", libs);
            env.put("HOME", new File(root, "home").getAbsolutePath());
            env.put("TMPDIR", temporaryDirectory.getAbsolutePath());
            env.put("JAVA_TOOL_OPTIONS", "-XX:-UsePerfData -Djava.io.tmpdir="
                    + temporaryDirectory.getAbsolutePath());
            env.put("LANG", "C.UTF-8");
            env.put("HORIZON_JAVA_PROFILE", "mojo-pojav-openjdk-25");
            return env;
        }
    }

    private PojavRuntimeInstaller() {}

    static RuntimePaths getInstalledIfReady(Context context) {
        File root = new File(context.getApplicationContext().getFilesDir(),
                ".local/horizon-java25-pojav");
        try {
            return isReady(root) ? new RuntimePaths(root) : null;
        } catch (IOException error) {
            return null;
        }
    }

    static synchronized RuntimePaths ensureInstalled(Context context, AtomicBoolean cancelled,
                                                      ProgressListener listener) throws Exception {
        Context app = context.getApplicationContext();
        File parent = new File(app.getFilesDir(), ".local");
        ensureDirectory(parent);
        File active = new File(parent, "horizon-java25-pojav");
        if (isReady(active)) {
            RuntimePaths existing = new RuntimePaths(active);
            report(listener, "JRE Pojav/Mojo já instalado",
                    "Java " + existing.version + " · ARM64 · runtime separado", 2, 2);
            return existing;
        }

        long available = new StatFs(app.getFilesDir().getAbsolutePath()).getAvailableBytes();
        long required = 600L * MIB;
        if (available > 0 && available < required) {
            throw new IOException("Pouco espaço livre para o JRE Pojav/Mojo. Disponível "
                    + human(available) + "; necessário aproximadamente " + human(required) + ".");
        }

        File cache = new File(parent, "horizon-java25-pojav-cache");
        File stage = new File(parent, "horizon-java25-pojav.installing");
        File old = new File(parent, "horizon-java25-pojav.previous");
        ensureDirectory(cache);
        deleteRecursively(stage);
        ensureDirectory(stage);
        ensureDirectory(new File(stage, "home"));
        ensureDirectory(new File(stage, "tmp"));

        try {
            checkCancelled(cancelled);
            report(listener, "Verificando runtime Pojav/Mojo",
                    "Baixando o manifesto de assinaturas oficial", 0, 2);
            PublicKey publicKey = readPublicKey(app);
            Map<String, byte[]> signatures = readSignatureBundle(readTextUrl(VERSION_URL));
            byte[] universalSignature = signatures.get(UNIVERSAL);
            byte[] arm64Signature = signatures.get(ARM64);
            if (universalSignature == null || arm64Signature == null) {
                throw new IOException("O manifesto oficial não inclui os dois componentes necessários para ARM64.");
            }

            File universal = new File(cache, UNIVERSAL);
            File arm64 = new File(cache, ARM64);
            if (!isSigned(universal, universalSignature, publicKey)) {
                deleteQuietly(universal);
                downloadAndVerify(UNIVERSAL, universal, universalSignature, publicKey,
                        cancelled, listener, 0);
            } else {
                report(listener, "JRE Pojav/Mojo", "Componente universal já verificado", 1, 2);
            }
            if (!isSigned(arm64, arm64Signature, publicKey)) {
                deleteQuietly(arm64);
                downloadAndVerify(ARM64, arm64, arm64Signature, publicKey,
                        cancelled, listener, 1);
            } else {
                report(listener, "JRE Pojav/Mojo", "Componente ARM64 já verificado", 2, 2);
            }

            long[] totals = new long[]{0L, 0L};
            checkCancelled(cancelled);
            report(listener, "Instalando JRE Pojav/Mojo", "Extraindo o runtime universal", 0, 2);
            JdkRuntimeInstaller.extractMojoArchive(universal, stage, cancelled, totals);
            checkCancelled(cancelled);
            report(listener, "Instalando JRE Pojav/Mojo", "Extraindo as bibliotecas ARM64", 1, 2);
            JdkRuntimeInstaller.extractMojoArchive(arm64, stage, cancelled, totals);
            if (totals[0] > 250_000L || totals[1] > 1024L * MIB) {
                throw new IOException("O JRE Pojav/Mojo excedeu os limites seguros de extração.");
            }

            RuntimePaths candidate = new RuntimePaths(stage);
            validateRuntime(candidate);
            ensureExecutableInsideRuntime(candidate.root, candidate.java, "java");
            File versionLog = new File(stage, "java-version.log");
            if (!runVersionCommand(candidate, versionLog, cancelled)) {
                throw new IOException("O runtime Pojav/Mojo foi extraído, mas java -version não iniciou corretamente: "
                        + readSmallText(versionLog));
            }
            writeText(new File(stage, ".ready"),
                    "java-major=25\narchitecture=aarch64\nprovider=mojo-pojav\n");

            deleteRecursively(old);
            if (active.exists() && !active.renameTo(old)) {
                throw new IOException("Não foi possível guardar a instalação anterior do runtime Pojav/Mojo.");
            }
            if (!stage.renameTo(active)) {
                if (old.exists()) old.renameTo(active);
                throw new IOException("Não foi possível ativar o runtime Pojav/Mojo.");
            }
            RuntimePaths installed = new RuntimePaths(active);
            deleteRecursively(old);
            deleteRecursively(cache);
            report(listener, "JRE Pojav/Mojo pronto",
                    "Java " + installed.version + " ARM64 instalado separadamente; OpenJDK Termux preservado",
                    2, 2);
            return installed;
        } catch (Exception error) {
            deleteRecursively(stage);
            throw error;
        }
    }

    private static void validateRuntime(RuntimePaths runtime) throws IOException {
        if (!runtime.java.isFile() || runtime.java.length() < 16 * 1024
                || !runtime.libjvm.isFile() || runtime.libjvm.length() < 1024 * 1024) {
            throw new IOException("O JRE Pojav/Mojo não contém bin/java e lib/server/libjvm.so válidos.");
        }
        if (!runtime.version.startsWith("25")) {
            throw new IOException("A runtime obtida não é Java 25: " + runtime.version);
        }
        String release = readSmallText(new File(runtime.root, "release"));
        if (!release.contains("OS_ARCH=\"aarch64\"")) {
            throw new IOException("A runtime Pojav/Mojo não declara arquitetura aarch64.");
        }
    }

    private static boolean isReady(File root) {
        if (!new File(root, ".ready").isFile()) return false;
        try {
            RuntimePaths paths = new RuntimePaths(root);
            validateRuntime(paths);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static PublicKey readPublicKey(Context context) throws Exception {
        try (InputStream input = context.getAssets().open(CERT_ASSET)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Certificate certificate = factory.generateCertificate(input);
            PublicKey key = certificate.getPublicKey();
            if (!(key instanceof RSAPublicKey)
                    || ((RSAPublicKey) key).getModulus().bitLength() != 4096) {
                throw new IOException("O certificado do runtime Pojav/Mojo não corresponde à chave RSA-4096 esperada.");
            }
            return key;
        }
    }

    private static Map<String, byte[]> readSignatureBundle(String bundle) throws IOException {
        Map<String, byte[]> signatures = new HashMap<>();
        for (String line : bundle.split("\\r?\\n")) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            int colon = value.indexOf(':');
            if (colon <= 0 || colon == value.length() - 1) {
                throw new IOException("O manifesto de assinaturas do runtime Pojav/Mojo está malformado.");
            }
            String name = value.substring(0, colon);
            if (!UNIVERSAL.equals(name) && !ARM64.equals(name)
                    && !name.matches("bin-(arm|arm64|x86_64)\\.tar\\.xz")) {
                throw new IOException("O manifesto contém um nome de componente inesperado: " + name);
            }
            try {
                byte[] signature = Base64.decode(value.substring(colon + 1), Base64.DEFAULT);
                if (signature.length != 512 || signatures.put(name, signature) != null) {
                    throw new IOException("Assinatura ausente, duplicada ou de tamanho inválido: " + name);
                }
            } catch (IllegalArgumentException error) {
                throw new IOException("Assinatura Base64 inválida para " + name, error);
            }
        }
        return signatures;
    }

    private static String readTextUrl(String address) throws Exception {
        HttpURLConnection connection = open(address);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode()
                        + " ao consultar o manifesto do runtime Pojav/Mojo.");
            }
            requireOfficialHost(connection.getURL());
            long length = connection.getContentLengthLong();
            if (length > 32 * 1024) throw new IOException("Manifesto de assinaturas grande demais.");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 32 * 1024) throw new IOException("Manifesto de assinaturas grande demais.");
                    output.write(buffer, 0, count);
                }
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadAndVerify(String name, File destination, byte[] expectedSignature,
                                          PublicKey publicKey, AtomicBoolean cancelled,
                                          ProgressListener listener, int index) throws Exception {
        File part = new File(destination.getAbsolutePath() + ".part");
        deleteQuietly(part);
        HttpURLConnection connection = open(COMPONENT_BASE + name);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " ao baixar " + name + ".");
            }
            requireOfficialHost(connection.getURL());
            long length = connection.getContentLengthLong();
            if (length > MAX_COMPONENT_BYTES) throw new IOException(name + " excede o limite de 64 MiB.");
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            long total = 0L;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream raw = new FileOutputStream(part);
                 java.io.BufferedOutputStream output = new java.io.BufferedOutputStream(raw, BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    total += count;
                    if (total > MAX_COMPONENT_BYTES) throw new IOException(name + " excede o limite de 64 MiB.");
                    verifier.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    if (total % MIB < BUFFER_SIZE) {
                        report(listener, "Baixando runtime Pojav/Mojo", name + ": " + human(total), index, 2);
                    }
                }
                output.flush();
                raw.getFD().sync();
            }
            if (total <= 0 || !verifier.verify(expectedSignature)) {
                throw new IOException("A assinatura oficial SHA256-RSA de " + name + " não corresponde ao arquivo baixado.");
            }
            if (!part.renameTo(destination)) Files.move(part.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            deleteQuietly(part);
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isSigned(File file, byte[] expectedSignature, PublicKey publicKey) {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_COMPONENT_BYTES) return false;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) verifier.update(buffer, 0, count);
            return verifier.verify(expectedSignature);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static HttpURLConnection open(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"mojolauncher.github.io".equalsIgnoreCase(url.getHost())) {
            throw new IOException("Host de download do runtime Pojav/Mojo não reconhecido.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(90_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative-PojavRuntime/1.0");
        return connection;
    }

    private static void requireOfficialHost(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"mojolauncher.github.io".equalsIgnoreCase(url.getHost())) {
            throw new IOException("O download do runtime foi redirecionado para um host não autorizado.");
        }
    }

    private static boolean runVersionCommand(RuntimePaths runtime, File log,
                                             AtomicBoolean cancelled) throws Exception {
        checkCancelled(cancelled);
        File linker = new File("/system/bin/linker64");
        if (!linker.isFile() || !linker.canExecute()) {
            throw new IOException("O Android não disponibiliza /system/bin/linker64 para validar o JRE Pojav/Mojo.");
        }
        List<String> command = new ArrayList<>();
        command.add("/system/bin/sh");
        command.add("-c");
        command.add("exec /system/bin/linker64 \"$@\"");
        command.add("linker64");
        command.add(runtime.java.getAbsolutePath());
        command.add(runtime.java.getAbsolutePath());
        command.add("-version");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log);
        builder.environment().putAll(runtime.processEnvironment(new File(runtime.root, "tmp")));
        Process process = builder.start();
        long started = System.currentTimeMillis();
        while (true) {
            checkCancelled(cancelled);
            if (process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
            if (System.currentTimeMillis() - started > 30_000) {
                process.destroyForcibly();
                throw new IOException("A validação java -version do runtime Pojav/Mojo excedeu 30 segundos.");
            }
        }
        return process.exitValue() == 0 && readSmallText(log).contains("25");
    }

    private static String readReleaseVersion(File release) throws IOException {
        String text = readSmallText(release);
        for (String line : text.split("\\r?\\n")) {
            if (!line.startsWith("JAVA_VERSION=")) continue;
            String value = line.substring("JAVA_VERSION=".length()).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) return value;
        }
        throw new IOException("Arquivo release ausente ou sem JAVA_VERSION.");
    }

    private static String readSmallText(File file) {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1 && output.size() < 64 * 1024) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void ensureExecutableInsideRuntime(File root, File file, String name) throws IOException {
        if (!file.isFile()) throw new IOException("Executável não encontrado no runtime Pojav/Mojo: " + name);
        File canonicalRoot = root.getCanonicalFile();
        File canonicalFile = file.getCanonicalFile();
        if (!canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
            throw new IOException("O executável " + name + " aponta para fora do runtime Pojav/Mojo.");
        }
        Os.chmod(canonicalFile.getAbsolutePath(), 0755);
        if (!canonicalFile.canExecute()) throw new IOException("O Android não permitiu executar " + name + ".");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory == null) throw new IOException("Pasta de destino inválida.");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Não foi possível criar " + directory.getAbsolutePath());
        }
    }

    private static void deleteQuietly(File file) {
        try { deleteRecursively(file); } catch (Exception ignored) {}
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || (!file.exists() && !Files.isSymbolicLink(file.toPath()))) return;
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Não foi possível remover " + file.getAbsolutePath());
    }

    private static void writeText(File file, String value) throws IOException {
        ensureDirectory(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void report(ProgressListener listener, String stage, String detail,
                               int completed, int total) {
        if (listener != null) listener.onProgress(stage, detail, completed, total);
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if (cancelled != null && cancelled.get()) throw new InterruptedException("Instalação do runtime Pojav/Mojo cancelada.");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Instalação do runtime Pojav/Mojo interrompida.");
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < MIB) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / MIB);
    }
}
