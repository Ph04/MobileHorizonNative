package dev.mobilehorizon.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Stages the Java compatibility API from the APK and resolves the already-installed
 * Java 25 runtime. It never downloads or installs a JDK during game launch.
 */
final class CompatibilityApiRuntime {
    private static final String ASSET_PATH = "compatibility-runtime/compatibility-api.jar";
    private static final String DIRECTORY_NAME = "compatibility-runtime";
    private static final String JAR_NAME = "compatibility-api.jar";

    static final class Prepared {
        final File apiJar;
        final File javaHome;
        final File javaExecutable;
        final File javaTemp;
        final String libraryPath;

        Prepared(File apiJar, JdkRuntimeInstaller.RuntimePaths java) {
            this.apiJar = apiJar;
            this.javaHome = java.javaHome;
            this.javaExecutable = java.java;
            this.javaTemp = new File(java.root, "tmp");
            this.libraryPath = new File(java.root, "usr/lib").getAbsolutePath()
                    + ":" + new File(java.javaHome, "lib").getAbsolutePath()
                    + ":" + new File(java.javaHome, "lib/server").getAbsolutePath()
                    + ":" + new File(java.javaHome, "lib/jli").getAbsolutePath();
        }
    }

    private CompatibilityApiRuntime() { }

    static Prepared prepare(Context context) throws IOException {
        JdkRuntimeInstaller.RuntimePaths java = JdkRuntimeInstaller.getInstalledIfReady(context);
        if (java == null) {
            throw new IOException("O Java 25 ainda não está instalado. Prepare os recursos oficiais "
                    + "do Minecraft antes de iniciar uma instância com loader.");
        }
        File filesRoot = context.getApplicationContext().getFilesDir().getCanonicalFile();
        File localRoot = new File(filesRoot, ".local").getCanonicalFile();
        if (!filesRoot.equals(localRoot.getParentFile())) {
            throw new IOException("O diretório .local saiu do armazenamento privado do aplicativo.");
        }
        File apiRoot = new File(localRoot, DIRECTORY_NAME);
        if (!apiRoot.isDirectory() && !apiRoot.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta privada da API de compatibilidade.");
        }
        if (Files.isSymbolicLink(apiRoot.toPath())
                || !localRoot.equals(apiRoot.getCanonicalFile().getParentFile())) {
            throw new IOException("A pasta da API de compatibilidade não é segura.");
        }

        File target = new File(apiRoot, JAR_NAME);
        File digestFile = new File(apiRoot, JAR_NAME + ".sha256");
        if (!isValidCachedJar(target, digestFile)) {
            File temporary = new File(apiRoot, JAR_NAME + ".partial");
            if (Files.isSymbolicLink(temporary.toPath())) {
                throw new IOException("O arquivo temporário da API não é seguro.");
            }
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("Não foi possível remover a cópia parcial anterior da API.");
            }
            try (InputStream input = context.getAssets().open(ASSET_PATH);
                 FileOutputStream output = new FileOutputStream(temporary, false)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                long copied = 0L;
                while ((count = input.read(buffer)) != -1) {
                    copied += count;
                    if (copied > 16L * 1024L * 1024L) {
                        throw new IOException("O JAR da API de compatibilidade excede 16 MiB.");
                    }
                    output.write(buffer, 0, count);
                }
                output.flush();
                output.getFD().sync();
            } catch (IOException error) {
                temporary.delete();
                throw new IOException("O APK não contém a API Java de compatibilidade. "
                        + "É necessário instalar uma versão do launcher que inclua esse módulo.", error);
            }
            if (temporary.length() < 1024L) {
                temporary.delete();
                throw new IOException("O JAR da API de compatibilidade está vazio ou incompleto.");
            }
            String digest = sha256(temporary);
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            writeTextAtomically(digestFile, digest + "\n");
        }

        File canonicalJar = target.getCanonicalFile();
        if (!canonicalJar.isFile() || !apiRoot.getCanonicalFile().equals(canonicalJar.getParentFile())) {
            throw new IOException("O JAR da API está fora da pasta privada permitida.");
        }
        return new Prepared(canonicalJar, java);
    }

    private static boolean isValidCachedJar(File jar, File digestFile) {
        if (Files.isSymbolicLink(jar.toPath()) || Files.isSymbolicLink(digestFile.toPath())
                || !jar.isFile() || jar.length() < 1024L
                || !digestFile.isFile() || digestFile.length() > 128L) return false;
        try {
            String expected = readSmallText(digestFile);
            return expected.matches("(?i)[0-9a-f]{64}")
                    && expected.equalsIgnoreCase(sha256(jar));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 não está disponível.", impossible);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private static String readSmallText(File file) throws IOException {
        if (!file.isFile() || file.length() > 128L) throw new IOException("Digest cache inválido.");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("Digest cache incompleto.");
                offset += count;
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        }
    }

    private static void writeTextAtomically(File target, String value) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }
}
