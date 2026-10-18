package dev.mobilehorizon.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs Horizon's pinned ARM64 preparer with an app-private OpenJDK 25 runtime. */
final class HorizonPrepareRunner {
    private static final String UPSTREAM_COMMIT = "24bcef42beaf1382e48964b7efa7aef3f5f86792";
    private static final String ASSET_ROOT = "horizon-prepare";
    private static final int MAX_LOG_BYTES = 8 * 1024 * 1024;

    private HorizonPrepareRunner() {}

    static String prepare(Context context, AtomicBoolean cancelled,
                          ProgressListener listener) throws Exception {
        checkCancelled(cancelled);
        report(listener, "Verificando arquivos vanilla", "Validando JARs, bibliotecas e assets oficiais sem duplicar a extração", 0, 6);
        validateVanillaInputs(context.getFilesDir(), cancelled, listener);

        File workspace = new File(context.getFilesDir(), ".local/horizon-prepare-workspace");
        ensureDirectory(workspace);
        ensureDirectory(new File(workspace, "tmp"));
        ensureDirectory(new File(workspace, "home"));
        report(listener, "Instalando fontes do preparador", "Versão upstream " + UPSTREAM_COMMIT, 1, 6);
        installSupportAssets(context.getAssets(), workspace, cancelled, listener);

        File lock = new File(workspace, "research-lock.json");
        File parityDir = new File(workspace,
                "mods/horizon/src/test/java/dev/horizonlab/parity");
        if (!lock.isFile() || !new File(parityDir, "VanillaHeightmapOracle.java").isFile()) {
            throw new IOException("O APK não contém todas as fontes/oráculos necessárias para a conversão. Recompile pela Action mais recente.");
        }

        report(listener, "Instalando Java 25 ARM64",
                "A primeira instalação baixa o runtime para o armazenamento privado do aplicativo", 2, 6);
        JdkRuntimeInstaller.RuntimePaths runtime = JdkRuntimeInstaller.ensureInstalled(
                context, cancelled, (stage, detail, completed, total) ->
                        report(listener, stage, detail, completed, total));

        File tool = new File(context.getFilesDir(), ".local/horizon-prepare-arm64");
        File packagedTool = new File(context.getFilesDir(),
                "unused-horizon-prepare-placeholder");
        try (InputStream input = context.getAssets().open(ASSET_ROOT + "/horizon-prepare-arm64");
             OutputStream output = new FileOutputStream(packagedTool)) {
            copy(input, output, cancelled);
        } catch (IOException error) {
            deleteQuietly(packagedTool);
            throw new IllegalStateException("Este APK não contém o preparador Horizon ARM64. Instale o APK produzido pela Action mais recente.", error);
        }
        ensureDirectory(tool.getParentFile());
        if (tool.exists() && !tool.delete()) {
            deleteQuietly(packagedTool);
            throw new IOException("Não foi possível atualizar o executável do preparador.");
        }
        if (!packagedTool.renameTo(tool)) {
            copyFile(packagedTool, tool);
            deleteQuietly(packagedTool);
        }
        Os.chmod(tool.getAbsolutePath(), 0700);
        checkCancelled(cancelled);

        File stagingResources = new File(workspace, ".local/horizon-client/26.3");
        deleteRecursively(stagingResources);
        ensureDirectory(stagingResources.getParentFile());
        File logFile = new File(workspace, ".local/horizon-prepare.log");
        ensureDirectory(logFile.getParentFile());
        if (logFile.exists() && !logFile.delete()) throw new IOException("Não foi possível zerar o log anterior.");

        report(listener, "Convertendo recursos Horizon",
                "Os oráculos do Minecraft podem consumir bastante RAM e CPU; mantenha espaço livre", 3, 6);
        File systemLinker = new File("/system/bin/linker64");
        if (!systemLinker.isFile() || !systemLinker.canExecute()) {
            throw new IOException("Este aparelho não disponibiliza /system/bin/linker64 para ARM64; "
                    + "não é possível executar o preparador sem violar as restrições W^X do Android.");
        }
        // targetSdk 35 cannot directly exec() ELF files downloaded/extracted into filesDir.
        // The Android system linker loads the verified ARM64 preparer from its absolute path.
        // Route through the system shell to give linker64 an unambiguous
        // argv vector: $0 is linker64 and $1 is the verified preparer ELF.
        ProcessBuilder builder = new ProcessBuilder(
                "/system/bin/sh",
                "-c",
                "exec /system/bin/linker64 \\"$@\\"",
                "linker64",
                tool.getAbsolutePath(),
                "--prepare",
                "--server-jar", new File(context.getFilesDir(), "minecraft/server.jar").getAbsolutePath(),
                "--minecraft-dir", new File(context.getFilesDir(), "minecraft").getAbsolutePath(),
                "--root", workspace.getAbsolutePath());
        builder.directory(workspace);
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.putAll(runtime.processEnvironment(new File(workspace, "tmp")));
        environment.put("HORIZON_MINECRAFT_DIR", new File(context.getFilesDir(), "minecraft").getAbsolutePath());
        environment.put("HORIZON_SERVER_JAR", new File(context.getFilesDir(), "minecraft/server.jar").getAbsolutePath());

        int exitCode = runProcess(builder, logFile, cancelled, listener);
        if (exitCode != 0) {
            throw new IOException("O preparador Horizon terminou com código " + exitCode
                    + ". Últimas linhas do log:\n" + tail(logFile, 8000));
        }
        checkCancelled(cancelled);
        report(listener, "Validando saída completa", "Checando schema, data version e arquivos obrigatórios", 5, 6);
        String summary = HorizonResourceConverter.validatePreparedResources(stagingResources);

        File active = new File(context.getFilesDir(), ".local/horizon-client/26.3");
        File backup = new File(context.getFilesDir(), ".local/horizon-client/26.3.previous");
        ensureDirectory(active.getParentFile());
        deleteRecursively(backup);
        if (active.exists() && !active.renameTo(backup)) {
            throw new IOException("A conversão foi validada, mas não foi possível substituir a saída ativa; a pasta anterior foi preservada.");
        }
        if (!stagingResources.renameTo(active)) {
            if (backup.exists() && !backup.renameTo(active)) {
                throw new IOException("Não foi possível ativar os recursos completos nem restaurar a pasta anterior. Backup: " + backup.getAbsolutePath());
            }
            throw new IOException("Não foi possível ativar a saída Horizon validada.");
        }
        try {
            summary = HorizonResourceConverter.validatePreparedResources(active);
        } catch (Exception error) {
            deleteRecursively(active);
            if (backup.exists()) backup.renameTo(active);
            throw new IOException("A validação após ativação falhou; a saída anterior foi restaurada.", error);
        }
        deleteRecursively(backup);
        report(listener, "Conversão completa concluída",
                "Saída instalada em .local/horizon-client/26.3", 6, 6);
        return summary + "\nConversão completa executada pelo preparador upstream " + UPSTREAM_COMMIT
                + " com Java 25 ARM64 no próprio Android.\nLog: " + logFile.getAbsolutePath();
    }

    private static void validateVanillaInputs(File filesDir, AtomicBoolean cancelled,
                                               ProgressListener listener) throws Exception {
        File minecraft = new File(filesDir, "minecraft");
        File versionJsonFile = new File(minecraft, "versions/26.3/26.3.json");
        File clientJar = new File(minecraft, "versions/26.3/26.3.jar");
        File serverJar = new File(minecraft, "server.jar");
        requireFile(versionJsonFile, "Falta o JSON oficial de Minecraft 26.3. Baixe os arquivos base.");
        requireFile(clientJar, "Falta o JAR oficial do cliente 26.3.");
        requireFile(serverJar, "Falta o JAR oficial do servidor 26.3.");

        String versionJsonHash = sha1(versionJsonFile);
        if (!OfficialMinecraftInstaller.isAcceptedVersionJsonSha1(versionJsonHash)) {
            throw new IOException("O JSON instalado não corresponde a um manifesto conhecido do Minecraft 26.3.");
        }
        org.json.JSONObject version = new org.json.JSONObject(readText(versionJsonFile, 64 * 1024 * 1024));
        if (!"26.3".equals(version.optString("id", ""))
                || version.optJSONObject("javaVersion") == null
                || version.optJSONObject("javaVersion").optInt("majorVersion", -1) != 25) {
            throw new IOException("O manifesto não corresponde ao snapshot Minecraft 26.3 com Java 25.");
        }
        if (!OfficialMinecraftInstaller.CLIENT_SHA1.equalsIgnoreCase(sha1(clientJar))) {
            throw new IOException("O SHA-1 do JAR do cliente não corresponde ao arquivo oficial fixado.");
        }
        if (!OfficialMinecraftInstaller.SERVER_SHA1.equalsIgnoreCase(sha1(serverJar))) {
            throw new IOException("O SHA-1 do JAR do servidor não corresponde ao arquivo oficial fixado.");
        }

        org.json.JSONArray libraries = version.optJSONArray("libraries");
        if (libraries == null) throw new IOException("O manifesto não contém a lista oficial de bibliotecas.");
        java.util.List<String> libraryNames = new java.util.ArrayList<>();
        for (int i = 0; i < libraries.length(); i++) {
            org.json.JSONObject library = libraries.optJSONObject(i);
            if (library != null) libraryNames.add(library.optString("name", ""));
        }
        int verifiedLibraries = 0;
        report(listener, "Verificando bibliotecas", "Validando os JARs descritos pelo manifesto", 1, 6);
        for (int i = 0; i < libraries.length(); i++) {
            checkCancelled(cancelled);
            org.json.JSONObject library = libraries.optJSONObject(i);
            if (library == null || !OfficialMinecraftInstaller.libraryAllowedForAndroid(library)
                    || !OfficialMinecraftInstaller.nativeLibraryMatchesForAndroid(
                    library.optString("name", ""), libraryNames)) continue;
            org.json.JSONObject downloads = library.optJSONObject("downloads");
            org.json.JSONObject artifact = downloads == null ? null : downloads.optJSONObject("artifact");
            if (artifact == null) continue;
            String relative = artifact.optString("path", "");
            if (!OfficialMinecraftInstaller.isSafeRelativePath(relative)) {
                throw new IOException("O manifesto contém o caminho inseguro de uma biblioteca.");
            }
            File file = new File(new File(minecraft, "libraries"), relative);
            requireFile(file, "Falta a biblioteca " + relative + ". Baixe novamente os arquivos base.");
            long expectedSize = artifact.optLong("size", -1);
            if (expectedSize >= 0 && file.length() != expectedSize) {
                throw new IOException("O tamanho da biblioteca " + relative + " não corresponde ao manifesto.");
            }
            String expectedSha1 = artifact.optString("sha1", "");
            if (!OfficialMinecraftInstaller.isSha1(expectedSha1)
                    || !expectedSha1.equalsIgnoreCase(sha1(file))) {
                throw new IOException("A biblioteca " + relative + " não passou na verificação SHA-1.");
            }
            verifiedLibraries++;
        }
        if (verifiedLibraries == 0) throw new IOException("Nenhuma biblioteca de runtime foi verificada.");
        report(listener, "Verificando asset index", verifiedLibraries + " bibliotecas verificadas", 2, 6);

        org.json.JSONObject indexMeta = version.optJSONObject("assetIndex");
        if (indexMeta == null) throw new IOException("O manifesto não define um asset index.");
        String indexId = indexMeta.optString("id", "");
        if (!indexId.matches("[A-Za-z0-9._-]{1,100}") || indexId.equals(".") || indexId.equals("..")) {
            throw new IOException("O ID do asset index é inválido.");
        }
        File indexFile = new File(minecraft, "assets/indexes/" + indexId + ".json");
        requireFile(indexFile, "Falta o índice de assets. Baixe os arquivos base novamente.");
        String expectedIndexHash = indexMeta.optString("sha1", "");
        if (!OfficialMinecraftInstaller.isSha1(expectedIndexHash)
                || !expectedIndexHash.equalsIgnoreCase(sha1(indexFile))) {
            throw new IOException("O asset index não passou na verificação SHA-1.");
        }
        org.json.JSONObject index = new org.json.JSONObject(readText(indexFile, 64 * 1024 * 1024));
        org.json.JSONObject objects = index.optJSONObject("objects");
        if (objects == null || objects.length() == 0 || objects.length() > 100_000) {
            throw new IOException("O asset index está vazio ou excede o limite seguro.");
        }
        java.util.Map<String, Long> objectHashes = new java.util.LinkedHashMap<>();
        java.util.Iterator<String> keys = objects.keys();
        while (keys.hasNext()) {
            checkCancelled(cancelled);
            String name = keys.next();
            org.json.JSONObject object = objects.optJSONObject(name);
            if (object == null) throw new IOException("Entrada de asset inválida no índice: " + name);
            String hash = object.optString("hash", "").toLowerCase(java.util.Locale.ROOT);
            long size = object.optLong("size", -1);
            if (!hash.matches("[0-9a-f]{40}") || size < 0 || size > 512L * 1024L * 1024L) {
                throw new IOException("Metadados inválidos para o asset " + name);
            }
            Long previous = objectHashes.putIfAbsent(hash, size);
            if (previous != null && previous.longValue() != size) {
                throw new IOException("O asset index associa tamanhos diferentes ao mesmo hash.");
            }
        }
        long totalBytes = 0;
        long checkedBytes = 0;
        for (Long size : objectHashes.values()) totalBytes += size;
        int checked = 0;
        for (java.util.Map.Entry<String, Long> entry : objectHashes.entrySet()) {
            checkCancelled(cancelled);
            String hash = entry.getKey();
            long size = entry.getValue();
            File object = new File(minecraft, "assets/objects/" + hash.substring(0, 2) + "/" + hash);
            if (!object.isFile() || object.length() != size) {
                throw new IOException("Falta o asset oficial " + hash
                        + ". Use “Baixar base + todos os assets” antes da conversão completa.");
            }
            if (!hash.equalsIgnoreCase(sha1(object))) {
                throw new IOException("O asset " + hash + " não passou na verificação SHA-1. Baixe os assets novamente.");
            }
            checked++;
            checkedBytes += size;
            if (checked % 64 == 0 || checked == objectHashes.size()) {
                report(listener, "Verificando assets oficiais",
                        checked + " / " + objectHashes.size() + " objetos (" + human(checkedBytes)
                                + " / " + human(totalBytes) + ")", 2, 6);
            }
        }
        report(listener, "Arquivos vanilla íntegros",
                verifiedLibraries + " bibliotecas e " + objectHashes.size() + " assets verificados", 2, 6);
    }

    private static String readText(File file, int limit) throws Exception {
        try (InputStream input = new java.io.BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > limit) throw new IOException("O JSON excede o limite de tamanho.");
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = new java.io.BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(40);
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void requireFile(File file, String message) throws IOException {
        if (!file.isFile()) throw new IOException(message);
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    interface ProgressListener {
        void onProgress(String stage, String detail, int completed, int total);
    }

    private static int runProcess(ProcessBuilder builder, File logFile, AtomicBoolean cancelled,
                                 ProgressListener listener) throws Exception {
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new IOException("Não foi possível executar o preparador ARM64. O Android pode ter bloqueado a execução do arquivo privado: "
                    + error.getMessage(), error);
        }
        final Process running = process;
        Thread logReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
                String line;
                long written = logFile.length();
                while ((line = reader.readLine()) != null) {
                    String record = line + "\n";
                    if (written < MAX_LOG_BYTES) {
                        writer.write(record);
                        writer.flush();
                        written += record.getBytes(StandardCharsets.UTF_8).length;
                    }
                    report(listener, "Exportador Horizon", line, -1, -1);
                }
            } catch (IOException ignored) {
                // The process exit code and last log lines are reported by the caller.
            }
        }, "horizon-prepare-log-reader");
        logReader.setDaemon(true);
        logReader.start();
        long started = System.nanoTime();
        try {
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled);
                if (TimeUnit.NANOSECONDS.toHours(System.nanoTime() - started) >= 2) {
                    process.destroyForcibly();
                    throw new IOException("A conversão completa excedeu o limite de duas horas.");
                }
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw error;
        } catch (Exception error) {
            process.destroyForcibly();
            if (error instanceof InterruptedException) throw error;
            throw error;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try { logReader.join(3000); } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        return process.exitValue();
    }

    private static void installSupportAssets(AssetManager assets, File workspace,
                                             AtomicBoolean cancelled,
                                             ProgressListener listener) throws Exception {
        copyAssetTree(assets, ASSET_ROOT + "/support", workspace, "", cancelled,
                new long[]{0}, listener);
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File targetRoot,
                                      String relative, AtomicBoolean cancelled, long[] files,
                                      ProgressListener listener) throws Exception {
        checkCancelled(cancelled);
        String[] children = assets.list(assetPath);
        if (children != null && children.length > 0) {
            for (String child : children) {
                String childRelative = relative.isEmpty() ? child : relative + "/" + child;
                copyAssetTree(assets, assetPath + "/" + child, targetRoot, childRelative,
                        cancelled, files, listener);
            }
            return;
        }
        if (relative.isEmpty()) return;
        File target = safeChild(targetRoot, relative);
        ensureDirectory(target.getParentFile());
        try (InputStream input = assets.open(assetPath);
             OutputStream output = new FileOutputStream(target)) {
            copy(input, output, cancelled);
        }
        files[0]++;
        if (files[0] % 25 == 0) {
            report(listener, "Instalando fontes do preparador",
                    files[0] + " arquivos instalados", 1, 6);
        }
    }

    private static File safeChild(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.startsWith("/")
                || relative.indexOf('\\') >= 0) throw new IOException("Caminho inseguro em fonte do preparador.");
        File base = root.getCanonicalFile();
        File target = new File(base, relative).getCanonicalFile();
        if (!target.toPath().startsWith(base.toPath())) throw new IOException("Caminho de fonte saiu da pasta de trabalho.");
        return target;
    }

    private static void copy(InputStream input, OutputStream output,
                             AtomicBoolean cancelled) throws IOException, InterruptedException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkCancelled(cancelled);
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static void copyFile(File from, File to) throws IOException {
        ensureDirectory(to.getParentFile());
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
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
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete()) throw new IOException("Não foi possível remover " + file.getAbsolutePath());
    }

    private static String tail(File file, int limit) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long position = Math.max(0, input.length() - limit);
            input.seek(position);
            byte[] bytes = new byte[(int) (input.length() - position)];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "Log não disponível.";
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if (cancelled != null && cancelled.get()) {
            throw new InterruptedException("A conversão completa foi cancelada.");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Conversão interrompida.");
    }

    private static void report(ProgressListener listener, String stage, String detail,
                               int completed, int total) {
        if (listener != null) listener.onProgress(stage, detail, completed, total);
    }
}
