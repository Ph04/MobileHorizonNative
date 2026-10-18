package dev.mobilehorizon.app;

import android.content.Context;
import android.os.StatFs;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs the pinned Android ARM64 OpenJDK 25 package set into this app's private directory.
 * The APK contains only a hash-pinned package manifest; the large runtime is downloaded on
 * demand from the Termux package repository. Installing the runtime does not install the Termux app.
 */
final class JdkRuntimeInstaller {
    private static final String ASSET_MANIFEST = "horizon-prepare/jdk-manifest.json";
    private static final String EXPECTED_REPOSITORY = "packages.termux.dev";
    private static final String LEGACY_PREFIX = "data/data/com.termux/files/usr";
    private static final long MAX_INDEX_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final int MAX_TAR_ENTRIES = 400_000;
    private static final long MAX_UNPACKED_BYTES = 2L * 1024L * 1024L * 1024L;

    interface ProgressListener {
        void onProgress(String stage, String detail, int completed, int total);
    }

    static final class RuntimePaths {
        final File root;
        final File javaHome;
        final File java;
        final File javac;

        RuntimePaths(File root) {
            this.root = root;
            this.javaHome = new File(root, "usr/lib/jvm/java-25-openjdk");
            this.java = new File(javaHome, "bin/java");
            this.javac = new File(javaHome, "bin/javac");
        }

        Map<String, String> processEnvironment(File temporaryDirectory) {
            Map<String, String> env = new HashMap<>();
            String oldPath = System.getenv("PATH");
            env.put("JAVA_HOME", javaHome.getAbsolutePath());
            env.put("PATH", javaHome.getAbsolutePath() + "/bin" + (oldPath == null ? "" : ":" + oldPath));
            String libs = new File(root, "usr/lib").getAbsolutePath()
                    + ":" + new File(javaHome, "lib").getAbsolutePath()
                    + ":" + new File(javaHome, "lib/server").getAbsolutePath()
                    + ":" + new File(javaHome, "lib/jli").getAbsolutePath();
            String oldLibs = System.getenv("LD_LIBRARY_PATH");
            if (oldLibs != null && !oldLibs.isEmpty()) libs += ":" + oldLibs;
            env.put("LD_LIBRARY_PATH", libs);
            env.put("HOME", new File(root, "home").getAbsolutePath());
            env.put("TMPDIR", temporaryDirectory.getAbsolutePath());
            env.put("PREFIX", new File(root, "usr").getAbsolutePath());
            env.put("TERMUX_PREFIX", new File(root, "usr").getAbsolutePath());
            // Termux patches OpenJDK's perf-data path to its own absolute prefix.
            // Disable that optional feature; java.io.tmpdir still points to writable app storage.
            env.put("JAVA_TOOL_OPTIONS", "-XX:-UsePerfData -Djava.io.tmpdir="
                    + temporaryDirectory.getAbsolutePath());
            env.put("LANG", "C.UTF-8");
            return env;
        }
    }

    private static final class PackageFile {
        final String name;
        final String filename;
        final String sha256;
        final long size;

        PackageFile(JSONObject json) throws Exception {
            name = json.getString("name");
            filename = json.getString("filename");
            sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
            size = json.getLong("size");
            if (!name.matches("[A-Za-z0-9+.-]{1,120}")
                    || !filename.startsWith("pool/")
                    || filename.contains("..")
                    || !filename.matches("[A-Za-z0-9_+./-]+")
                    || !sha256.matches("[0-9a-f]{64}")
                    || size <= 0 || size > 700L * 1024L * 1024L) {
                throw new IllegalArgumentException("Manifesto Java 25 contém metadados de pacote inválidos.");
            }
        }
    }

    private JdkRuntimeInstaller() {}

    static RuntimePaths ensureInstalled(Context context, AtomicBoolean cancelled,
                                       ProgressListener listener) throws Exception {
        File parent = new File(context.getFilesDir(), ".local");
        ensureDirectory(parent);
        File active = new File(parent, "horizon-java25");
        RuntimePaths existing = new RuntimePaths(active);
        if (isReady(active)) {
            report(listener, "Java 25 já instalado", "Validando o runtime local", 1, 1);
            verifyCommands(existing, cancelled, listener);
            return existing;
        }

        JSONArray packageJson = readManifest(context);
        List<PackageFile> packages = new ArrayList<>();
        long totalDownloadBytes = 0;
        for (int i = 0; i < packageJson.length(); i++) {
            PackageFile item = new PackageFile(packageJson.getJSONObject(i));
            packages.add(item);
            totalDownloadBytes += item.size;
            if (packages.size() > 64 || totalDownloadBytes > 600L * 1024L * 1024L) {
                throw new IllegalStateException("O pacote Java 25 excede o limite de segurança previsto pelo launcher.");
            }
        }
        if (packages.isEmpty()) throw new IllegalStateException("O APK não contém a lista dos pacotes Java 25.");

        try {
            long available = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
            long required = totalDownloadBytes + 700L * 1024L * 1024L;
            if (available < required) {
                throw new IllegalStateException("Pouco espaço livre para o runtime Java 25. Disponível: "
                        + human(available) + "; necessário aproximadamente: " + human(required) + ".");
            }
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception ignored) {
            // Some Android providers do not report quota accurately; package and extraction limits remain enforced.
        }

        File cache = new File(parent, "horizon-java25-package-cache");
        File stage = new File(parent, "horizon-java25.installing");
        File failed = new File(parent, "horizon-java25.failed");
        ensureDirectory(cache);
        deleteRecursively(stage);
        ensureDirectory(stage);
        ensureDirectory(new File(stage, "home"));
        ensureDirectory(new File(stage, "tmp"));

        long downloadedBytes = 0;
        try {
            for (int i = 0; i < packages.size(); i++) {
                checkCancelled(cancelled);
                PackageFile item = packages.get(i);
                File deb = new File(cache, item.name + "-" + item.sha256.substring(0, 12) + ".deb");
                if (!isVerified(deb, item)) {
                    deleteQuietly(deb);
                    File partial = new File(deb.getAbsolutePath() + ".part");
                    deleteQuietly(partial);
                    downloadPackage(item, partial, cancelled, listener,
                            downloadedBytes, totalDownloadBytes, i, packages.size());
                    if (partial.length() != item.size || !item.sha256.equals(sha256(partial))) {
                        deleteQuietly(partial);
                        throw new IOException("A verificação SHA-256 falhou para o pacote " + item.name + ".");
                    }
                    if (!partial.renameTo(deb)) {
                        copyFile(partial, deb);
                        deleteQuietly(partial);
                    }
                }
                downloadedBytes += item.size;
            }

            long[] extractionTotals = new long[]{0, 0};
            for (int i = 0; i < packages.size(); i++) {
                checkCancelled(cancelled);
                PackageFile item = packages.get(i);
                File deb = new File(cache, item.name + "-" + item.sha256.substring(0, 12) + ".deb");
                report(listener, "Instalando runtime Java 25",
                        "Extraindo " + item.name + " (" + (i + 1) + "/" + packages.size() + ")",
                        i, packages.size());
                extractDataArchive(deb, stage, cancelled, extractionTotals);
                if (extractionTotals[0] > MAX_TAR_ENTRIES || extractionTotals[1] > MAX_UNPACKED_BYTES) {
                    throw new IOException("O runtime Java 25 excedeu os limites seguros de extração.");
                }
            }

            RuntimePaths stagingRuntime = new RuntimePaths(stage);
            if (!stagingRuntime.java.isFile() || !stagingRuntime.javac.isFile()) {
                throw new IOException("O pacote Java 25 não contém java/javac nos caminhos esperados.");
            }
            File old = new File(parent, "horizon-java25.previous");
            deleteRecursively(old);
            if (active.exists() && !active.renameTo(old)) {
                throw new IOException("Não foi possível guardar o runtime anterior.");
            }
            if (!stage.renameTo(active)) {
                if (old.exists()) old.renameTo(active);
                throw new IOException("Não foi possível ativar o runtime Java 25.");
            }
            RuntimePaths installed = new RuntimePaths(active);
            try {
                verifyCommands(installed, cancelled, listener);
            } catch (Exception error) {
                deleteRecursively(active);
                if (old.exists()) old.renameTo(active);
                throw error;
            }
            writeText(new File(active, ".ready"), "java-major=25\n");
            deleteRecursively(old);
            deleteRecursively(cache);
            report(listener, "Java 25 pronto", "Runtime ARM64 instalado no armazenamento privado do app", 1, 1);
            return installed;
        } catch (Exception error) {
            // Keep hash-verified package downloads for retry; discard only the partial extraction.
            if (stage.exists()) {
                if (failed.exists()) deleteRecursively(failed);
                stage.renameTo(failed);
            }
            throw error;
        }
    }

    private static JSONArray readManifest(Context context) throws Exception {
        byte[] bytes;
        try (InputStream input = context.getAssets().open(ASSET_MANIFEST);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (output.size() + n > 2 * 1024 * 1024) {
                    throw new IOException("Manifesto do runtime Java 25 grande demais.");
                }
                output.write(buffer, 0, n);
            }
            bytes = output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Este APK não inclui o manifesto dos pacotes Java 25. Instale o APK produzido pela Action mais recente.", error);
        }
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"mobilehorizon-termux-openjdk-runtime".equals(root.optString("format"))
                || root.optInt("format_version", -1) != 1
                || root.optInt("java_major", -1) != 25
                || !"aarch64".equals(root.optString("architecture"))) {
            throw new IllegalStateException("O manifesto incluído não corresponde ao runtime Java 25 ARM64.");
        }
        String repository = root.optString("repository", "");
        URL repo = new URL(repository);
        if (!"https".equalsIgnoreCase(repo.getProtocol()) || !EXPECTED_REPOSITORY.equalsIgnoreCase(repo.getHost())) {
            throw new IllegalStateException("Repositório do runtime Java 25 não reconhecido.");
        }
        JSONArray packages = root.optJSONArray("packages");
        if (packages == null) throw new IllegalStateException("Manifesto Java 25 sem lista de pacotes.");
        return packages;
    }

    private static void downloadPackage(PackageFile item, File target, AtomicBoolean cancelled,
                                        ProgressListener listener, long baseBytes, long totalBytes,
                                        int packageIndex, int packageCount) throws Exception {
        URL url = new URL("https://" + EXPECTED_REPOSITORY + "/apt/termux-main/" + item.filename);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !EXPECTED_REPOSITORY.equalsIgnoreCase(url.getHost())) {
            throw new IOException("URL inválida para o pacote Java 25.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MobileHorizonNative/0.4 (Java25-preparation)");
        try {
            connection.connect();
            URL finalUrl = connection.getURL();
            if (!"https".equalsIgnoreCase(finalUrl.getProtocol())
                    || !(EXPECTED_REPOSITORY.equalsIgnoreCase(finalUrl.getHost())
                    || "packages-cf.termux.dev".equalsIgnoreCase(finalUrl.getHost()))) {
                throw new IOException("O download do runtime redirecionou para um host não permitido.");
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Não foi possível baixar " + item.name + " (HTTP "
                        + connection.getResponseCode() + ").");
            }
            long expectedLength = connection.getContentLengthLong();
            if (expectedLength > 0 && expectedLength != item.size) {
                throw new IOException("O tamanho anunciado de " + item.name + " não corresponde ao manifesto.");
            }
            ensureDirectory(target.getParentFile());
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream raw = new FileOutputStream(target);
                 BufferedOutputStream output = new BufferedOutputStream(raw, BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long current = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    current += n;
                    if (current > item.size || current > MAX_INDEX_BYTES) {
                        throw new IOException("O pacote " + item.name + " excedeu o tamanho fixado.");
                    }
                    output.write(buffer, 0, n);
                    if ((current & 0xFFFFFL) < BUFFER_SIZE || current == item.size) {
                        report(listener, "Baixando Java 25",
                                item.name + ": " + human(current) + " / " + human(item.size),
                                (int) Math.min(Integer.MAX_VALUE, baseBytes + current),
                                (int) Math.min(Integer.MAX_VALUE, totalBytes));
                    }
                }
            }
            if (target.length() != item.size) throw new IOException("Download incompleto: " + item.name + ".");
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isReady(File root) {
        File marker = new File(root, ".ready");
        RuntimePaths paths = new RuntimePaths(root);
        return marker.isFile() && paths.java.isFile() && paths.javac.isFile();
    }

    private static boolean isVerified(File file, PackageFile item) {
        try {
            return file.isFile() && file.length() == item.size && item.sha256.equals(sha256(file));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void verifyCommands(RuntimePaths runtime, AtomicBoolean cancelled,
                                       ProgressListener listener) throws Exception {
        ensureDirectory(new File(runtime.root, "home"));
        ensureDirectory(new File(runtime.root, "tmp"));
        runVersionCommand(runtime, runtime.javac, "javac", cancelled, listener);
        runVersionCommand(runtime, runtime.java, "java", cancelled, listener);
    }

    private static void runVersionCommand(RuntimePaths runtime, File executable, String label,
                                          AtomicBoolean cancelled, ProgressListener listener) throws Exception {
        checkCancelled(cancelled);
        File log = new File(runtime.root, label + "-version.log");
        ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "-version");
        builder.redirectErrorStream(true);
        builder.redirectOutput(log);
        builder.environment().putAll(runtime.processEnvironment(new File(runtime.root, "tmp")));
        Process process = builder.start();
        long start = System.currentTimeMillis();
        while (true) {
            checkCancelled(cancelled);
            try {
                if (process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
            } catch (InterruptedException error) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw error;
            }
            if (System.currentTimeMillis() - start > 30_000) {
                process.destroyForcibly();
                throw new IOException("A verificação de " + label + " excedeu 30 segundos.");
            }
        }
        if (process.exitValue() != 0) {
            throw new IOException("O runtime Android Java 25 falhou ao iniciar " + label + ": " + readSmallText(log));
        }
        report(listener, "Validando Java 25", readSmallText(log).trim(), 1, 1);
    }

    private static void extractDataArchive(File deb, File destinationRoot, AtomicBoolean cancelled,
                                           long[] totals) throws Exception {
        long dataOffset = -1;
        long dataSize = -1;
        try (RandomAccessFile archive = new RandomAccessFile(deb, "r")) {
            byte[] magic = new byte[8];
            archive.readFully(magic);
            if (!"!<arch>\n".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("Pacote .deb inválido: " + deb.getName());
            }
            while (archive.getFilePointer() + 60 <= archive.length()) {
                byte[] header = new byte[60];
                archive.readFully(header);
                String name = ascii(header, 0, 16).trim();
                while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                String sizeText = ascii(header, 48, 10).trim();
                if (header[58] != 0x60 || header[59] != 0x0a) throw new IOException("Cabeçalho AR inválido.");
                long size;
                try { size = Long.parseLong(sizeText); }
                catch (NumberFormatException error) { throw new IOException("Tamanho AR inválido."); }
                if (size < 0 || archive.getFilePointer() + size > archive.length()) {
                    throw new IOException("Entrada AR fora do pacote.");
                }
                long contentOffset = archive.getFilePointer();
                if ("data.tar.xz".equals(name)) {
                    dataOffset = contentOffset;
                    dataSize = size;
                    break;
                }
                archive.seek(contentOffset + size + (size & 1));
            }
        }
        if (dataOffset < 0 || dataSize < 0) {
            throw new IOException("O pacote " + deb.getName() + " não contém data.tar.xz.");
        }
        try (InputStream file = new FileInputStream(deb)) {
            skipFully(file, dataOffset);
            try (InputStream bounded = new BoundedInputStream(file, dataSize);
                 InputStream xz = new XZInputStream(bounded)) {
                extractTar(xz, destinationRoot, cancelled, totals);
            }
        }
    }

    private static void extractTar(InputStream tar, File destinationRoot, AtomicBoolean cancelled,
                                   long[] totals) throws Exception {
        byte[] header = new byte[512];
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
            checkCancelled(cancelled);
            int got = readHeader(tar, header);
            if (got == 0) return;
            if (got != 512) throw new IOException("Cabeçalho TAR truncado no pacote Java 25.");
            if (allZero(header)) return;
            if (++totals[0] > MAX_TAR_ENTRIES) throw new IOException("Muitos arquivos no runtime Java 25.");
            String name = tarString(header, 0, 100);
            String prefix = tarString(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = parseOctal(header, 124, 12);
            int type = header[156] & 0xff;
            String link = tarString(header, 157, 100);
            if (size < 0 || size > MAX_UNPACKED_BYTES || totals[1] + size > MAX_UNPACKED_BYTES) {
                throw new IOException("O runtime Java 25 excede o limite de extração.");
            }

            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                byte[] metadata = readSmallEntry(tar, size);
                if (type == 'L') longName = trimNul(metadata);
                else if (type == 'K') longLink = trimNul(metadata);
                else {
                    Map<String, String> pax = parsePax(metadata);
                    if (pax.containsKey("path")) paxPath = pax.get("path");
                    if (pax.containsKey("linkpath")) paxLink = pax.get("linkpath");
                }
                skipPadding(tar, size);
                continue;
            }
            if (longName != null) { name = longName; longName = null; }
            if (paxPath != null) { name = paxPath; paxPath = null; }
            if (longLink != null) { link = longLink; longLink = null; }
            if (paxLink != null) { link = paxLink; paxLink = null; }

            File target = mapPackagePath(destinationRoot, name);
            if (target == null) {
                skipExactly(tar, size);
                skipPadding(tar, size);
                continue;
            }
            if (type == '5') {
                ensureDirectory(target);
                skipExactly(tar, size);
            } else if (type == '2') {
                skipExactly(tar, size);
                ensureDirectory(target.getParentFile());
                deleteEntry(target);
                String rewritten = rewriteSymlink(destinationRoot, target, link);
                Os.symlink(rewritten, target.getAbsolutePath());
            } else if (type == '1') {
                skipExactly(tar, size);
                File source = mapPackagePath(destinationRoot, link);
                if (source == null || !source.isFile()) throw new IOException("Link de arquivo inválido no runtime Java 25.");
                ensureDirectory(target.getParentFile());
                deleteEntry(target);
                copyFile(source, target);
            } else if (type == 0 || type == '0' || type == '7') {
                ensureDirectory(target.getParentFile());
                deleteEntry(target);
                long written = 0;
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
                    long remain = size;
                    int n;
                    while (remain > 0 && (n = tar.read(buffer, 0, (int) Math.min(buffer.length, remain))) != -1) {
                        checkCancelled(cancelled);
                        output.write(buffer, 0, n);
                        remain -= n;
                        written += n;
                    }
                    if (written != size) throw new IOException("Arquivo TAR truncado: " + name);
                }
                totals[1] += written;
                int mode = (int) parseOctal(header, 100, 8);
                try { Os.chmod(target.getAbsolutePath(), mode & 0777); } catch (Exception ignored) {}
            } else {
                skipExactly(tar, size);
            }
            skipPadding(tar, size);
        }
    }

    private static File mapPackagePath(File root, String rawName) throws IOException {
        if (rawName == null || rawName.isEmpty()) return null;
        String name = rawName.replace('\\', '/');
        while (name.startsWith("./")) name = name.substring(2);
        String prefix = LEGACY_PREFIX + "/";
        if (name.equals(LEGACY_PREFIX)) return new File(root, "usr");
        if (!name.startsWith(prefix)) return null;
        String relative = "usr/" + name.substring(prefix.length());
        Path rootPath = root.getCanonicalFile().toPath();
        Path target = rootPath.resolve(relative).normalize();
        if (!target.startsWith(rootPath) || target.equals(rootPath)) {
            throw new IOException("Caminho inseguro no pacote Java 25.");
        }
        return target.toFile();
    }

    private static String rewriteSymlink(File root, File linkPath, String original) throws IOException {
        if (original == null || original.isEmpty()) throw new IOException("Link simbólico vazio.");
        String prefix = "/" + LEGACY_PREFIX + "/";
        if (original.startsWith(prefix)) {
            File target = mapPackagePath(root, original.substring(1));
            if (target == null) throw new IOException("Destino de link simbólico inválido.");
            return linkPath.getParentFile().toPath().normalize()
                    .relativize(target.toPath().normalize()).toString();
        }
        if (original.startsWith("/")) return original;
        Path resolved = linkPath.getParentFile().toPath().resolve(original).normalize();
        Path rootPath = root.getCanonicalFile().toPath();
        if (!resolved.startsWith(rootPath)) throw new IOException("Link simbólico fora do runtime privado.");
        return original;
    }

    private static Map<String, String> parsePax(byte[] bytes) {
        Map<String, String> result = new HashMap<>();
        int offset = 0;
        while (offset < bytes.length) {
            int space = offset;
            while (space < bytes.length && bytes[space] != ' ') space++;
            if (space == bytes.length) break;
            int length;
            try { length = Integer.parseInt(new String(bytes, offset, space - offset, StandardCharsets.US_ASCII)); }
            catch (NumberFormatException error) { break; }
            if (length <= 0 || offset + length > bytes.length) break;
            String record = new String(bytes, space + 1, offset + length - space - 2, StandardCharsets.UTF_8);
            int equals = record.indexOf('=');
            if (equals > 0) result.put(record.substring(0, equals), record.substring(equals + 1));
            offset += length;
        }
        return result;
    }

    private static byte[] readSmallEntry(InputStream input, long size) throws IOException {
        if (size < 0 || size > 1024 * 1024) throw new IOException("Metadado TAR grande demais.");
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
        byte[] buffer = new byte[8192];
        long remain = size;
        int n;
        while (remain > 0 && (n = input.read(buffer, 0, (int) Math.min(buffer.length, remain))) != -1) {
            out.write(buffer, 0, n);
            remain -= n;
        }
        if (remain != 0) throw new IOException("Metadado TAR truncado.");
        return out.toByteArray();
    }

    private static int readHeader(InputStream input, byte[] header) throws IOException {
        int offset = 0;
        while (offset < header.length) {
            int n = input.read(header, offset, header.length - offset);
            if (n < 0) return offset;
            offset += n;
        }
        return offset;
    }

    private static void readExactly(InputStream input, byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int n = input.read(data, offset, data.length - offset);
            if (n < 0) throw new IOException("Arquivo AR truncado.");
            offset += n;
        }
    }

    private static void skipExactly(InputStream input, long amount) throws IOException {
        long remain = amount;
        byte[] buffer = new byte[16 * 1024];
        while (remain > 0) {
            long skipped = input.skip(remain);
            if (skipped > 0) { remain -= skipped; continue; }
            int n = input.read(buffer, 0, (int) Math.min(buffer.length, remain));
            if (n < 0) throw new IOException("Arquivo TAR truncado.");
            remain -= n;
        }
    }

    private static void skipPadding(InputStream input, long size) throws IOException {
        long padding = (512 - (size % 512)) % 512;
        if (padding > 0) skipExactly(input, padding);
    }

    private static void skipFully(InputStream input, long amount) throws IOException {
        long remain = amount;
        byte[] buffer = new byte[16 * 1024];
        while (remain > 0) {
            long skipped = input.skip(remain);
            if (skipped > 0) { remain -= skipped; continue; }
            int n = input.read(buffer, 0, (int) Math.min(buffer.length, remain));
            if (n < 0) throw new IOException("Pacote .deb truncado.");
            remain -= n;
        }
    }

    private static long parseOctal(byte[] data, int offset, int length) throws IOException {
        int end = offset + length;
        int start = offset;
        while (start < end && (data[start] == 0 || data[start] == ' ')) start++;
        while (end > start && (data[end - 1] == 0 || data[end - 1] == ' ')) end--;
        if (start == end) return 0;
        // Debian packages use octal tar sizes; reject base-256 fields rather than misread them.
        if ((data[start] & 0x80) != 0) throw new IOException("Campo TAR base-256 não suportado.");
        long value = 0;
        for (int i = start; i < end; i++) {
            if (data[i] < '0' || data[i] > '7') throw new IOException("Campo octal TAR inválido.");
            value = (value << 3) + (data[i] - '0');
        }
        return value;
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) if (b != 0) return false;
        return true;
    }

    private static String tarString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String trimNul(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) end--;
        while (end > 0 && (bytes[end - 1] == '\n' || bytes[end - 1] == '\r')) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    private static void copyFile(File source, File target) throws IOException {
        ensureDirectory(target.getParentFile());
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream raw = new FileOutputStream(target);
             BufferedOutputStream output = new BufferedOutputStream(raw, BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        ensureDirectory(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readSmallText(File file) {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1 && output.size() < 64 * 1024) output.write(buffer, 0, n);
            return output.toString("UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory == null) throw new IOException("Pasta de destino inválida.");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Não foi possível criar a pasta " + directory.getAbsolutePath());
        }
    }

    private static void deleteEntry(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || file.isFile()) {
            if (!file.delete()) throw new IOException("Não foi possível substituir " + file.getName());
        } else if (file.isDirectory()) {
            deleteRecursively(file);
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

    private static boolean isVerified(File file, PackageFile item, boolean ignored) {
        return isVerified(file, item);
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if (cancelled != null && cancelled.get()) throw new InterruptedException("Preparação completa cancelada pelo usuário.");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Preparação interrompida.");
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    private static void report(ProgressListener listener, String stage, String detail, int completed, int total) {
        if (listener != null) listener.onProgress(stage, detail, completed, total);
    }

    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            remaining = limit;
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = delegate.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int request = (int) Math.min(length, remaining);
            int count = delegate.read(buffer, offset, request);
            if (count > 0) remaining -= count;
            return count;
        }

        @Override public long skip(long amount) throws IOException {
            long skipped = delegate.skip(Math.min(amount, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override public void close() throws IOException {
            delegate.close();
        }
    }
}
