package dev.mobilehorizon.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Metadata-only dependency preflight. Temporary JAR copies have class files,
 * entrypoints, Mixins, access wideners and service registrations stripped.
 * Original mod bytecode is never loaded or executed by this checker.
 */
final class FabricMetadataPreflight {
    interface ProgressListener { void onProgress(String stage, String detail); }

    static final class Result {
        final List<String> mods;
        final File report;
        final File log;
        Result(List<String> mods, File report, File log) {
            this.mods = new ArrayList<>(mods);
            this.report = report;
            this.log = log;
        }
        String summary() {
            StringBuilder out = new StringBuilder("Fabric Loader resolveu ")
                    .append(mods.size()).append(" IDs no modo de pré-verificação (nenhum código de mod executado).\n");
            int limit = Math.min(mods.size(), 30);
            for (int i = 0; i < limit; i++) out.append("• ").append(mods.get(i)).append("\n");
            if (mods.size() > limit) out.append("… e mais ").append(mods.size() - limit).append(" IDs.\n");
            return out.append("Relatório: ").append(report.getAbsolutePath())
                    .append("\nLog: ").append(log.getAbsolutePath())
                    .append("\nIsso valida metadados/dependências, não o funcionamento em jogo.").toString();
        }
    }

    private static final long MAX_JAR_BYTES = 512L * 1024 * 1024;
    private static final long MAX_NESTED_BYTES = 32L * 1024 * 1024;
    private static final long MAX_NESTED_TOTAL = 128L * 1024 * 1024;
    private static final long MAX_OUTPUT_BYTES = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50_000;
    private static final int MAX_NESTED_JARS = 128;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long TIMEOUT_MS = 120_000L;

    private static final class Limits {
        int nestedCount;
        long nestedBytes;
        long writtenBytes;
        void nested(long length) throws IOException {
            if (++nestedCount > MAX_NESTED_JARS) throw new IOException("Há JARs aninhados demais no perfil.");
            if (length > 0) nestedBytes += length;
            if (nestedBytes > MAX_NESTED_TOTAL) throw new IOException("Os JARs aninhados excedem 128 MiB.");
        }
        void written(long length) throws IOException {
            writtenBytes += length;
            if (writtenBytes > MAX_OUTPUT_BYTES) throw new IOException("As cópias temporárias excedem 512 MiB.");
        }
    }

    private FabricMetadataPreflight() {}

    static Result run(Context context, File activeMods, AtomicBoolean cancelled,
                      ProgressListener listener) throws Exception {
        File api = FabricApiRuntimeInstaller.ensureInstalled(context);
        File loader = FabricLoaderRuntimeInstaller.ensureInstalled(context);
        File provider = FabricLoaderRuntimeInstaller.providerPath(context);

        File runtimeRoot = new File(new File(context.getFilesDir(), "horizon"), "fabric-bridge-runtime");
        ensureDirectory(runtimeRoot);
        File workspace = new File(runtimeRoot, "preflight-work");
        deleteTree(workspace);
        File safeMods = new File(workspace, "mods");
        File temp = new File(workspace, "tmp");
        File gameDir = new File(workspace, "game");
        ensureDirectory(safeMods);
        ensureDirectory(temp);
        ensureDirectory(gameDir);
        File outputReport = new File(workspace, "loader-result.json");
        File finalReport = new File(runtimeRoot, "last-preflight.json");
        File finalLog = new File(runtimeRoot, "last-preflight.log");

        try {
            checkCancelled(cancelled);
            List<File> sources = new ArrayList<>();
            if (activeMods.isDirectory()) {
                File[] files = activeMods.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (files != null) sources.addAll(Arrays.asList(files));
            }
            boolean apiIncluded = false;
            for (File file : sources) {
                if (file.getCanonicalFile().equals(api.getCanonicalFile())) apiIncluded = true;
            }
            if (!apiIncluded) sources.add(api);
            sources.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

            Limits limits = new Limits();
            for (int i = 0; i < sources.size(); i++) {
                checkCancelled(cancelled);
                File source = sources.get(i).getCanonicalFile();
                if (!source.isFile() || source.length() <= 0 || source.length() > MAX_JAR_BYTES) {
                    throw new IOException("JAR ausente, vazio ou grande demais: " + source.getName());
                }
                notify(listener, "Preparando cópia sem bytecode", source.getName() + " (" + (i + 1) + "/" + sources.size() + ")");
                sanitizeJar(source, new File(safeMods, safeJarName(source.getName())), temp, 0, limits, cancelled);
            }

            notify(listener, "Preparando Java 25", "Validando o runtime; a primeira execução pode baixar arquivos.");
            JdkRuntimeInstaller.RuntimePaths java = JdkRuntimeInstaller.ensureInstalled(
                    context, cancelled, (stage, detail, completed, total) -> notify(listener, stage, detail));

            List<String> command = new ArrayList<>();
            command.add("/system/bin/sh");
            command.add("-c");
            command.add("exec /system/bin/linker64 \"$@\"");
            command.add("linker64");
            command.add(java.java.getAbsolutePath());
            command.add(java.java.getAbsolutePath());
            command.add("-Xms32m");
            command.add("-Xmx512m");
            command.add("-XX:+UseSerialGC");
            command.add("-Dfabric.skipMcProvider=true");
            command.add("-Dfabric.noGui=true");
            command.add("-Dfabric.development=false");
            command.add("-Dfabric.gameVersion=26.3");
            command.add("-Dfabric.gameMappingNamespace=official");
            command.add("-Dfabric.runtimeMappingNamespace=official");
            command.add("-Dfabric.modsFolder=" + safeMods.getAbsolutePath());
            command.add("-Dhorizon.fabric.provider.enabled=true");
            command.add("-Dhorizon.fabric.preflight=true");
            command.add("-Dhorizon.fabric.auditRoot=" + gameDir.getAbsolutePath());
            command.add("-Dhorizon.fabric.report=" + outputReport.getAbsolutePath());
            command.add("-cp");
            command.add(loader.getAbsolutePath() + File.pathSeparator + provider.getAbsolutePath());
            command.add("net.fabricmc.loader.impl.launch.knot.KnotClient");

            notify(listener, "Resolvendo dependências", "Fabric Loader 0.19.5; entrypoints e bytecode removidos.");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(finalLog);
            builder.environment().putAll(java.processEnvironment(temp));
            Process process = builder.start();
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (cancelled != null && cancelled.get()) {
                    process.destroyForcibly();
                    throw new IOException("A pré-verificação foi cancelada.");
                }
                if (System.currentTimeMillis() >= deadline) {
                    process.destroyForcibly();
                    throw new IOException("Fabric Loader excedeu 120 segundos. Log: " + finalLog.getAbsolutePath());
                }
            }
            if (process.exitValue() != 0) {
                throw new IOException("Fabric Loader não resolveu as dependências (código " + process.exitValue()
                        + ").\n" + tail(finalLog, 5000));
            }
            if (!outputReport.isFile() || outputReport.length() <= 0 || outputReport.length() > MAX_METADATA_BYTES) {
                throw new IOException("O Loader não produziu relatório válido.\n" + tail(finalLog, 3000));
            }
            JSONObject json = new JSONObject(new String(Files.readAllBytes(outputReport.toPath()), StandardCharsets.UTF_8));
            if (!json.optBoolean("ok", false)
                    || !"metadata_preflight_only".equals(json.optString("mode", ""))
                    || !"26.3".equals(json.optString("game_version", ""))) {
                throw new IOException("O relatório do Loader não corresponde à pré-verificação esperada.");
            }
            JSONArray array = json.optJSONArray("mods");
            if (array == null || array.length() == 0) throw new IOException("O Loader não encontrou mods.");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject mod = array.optJSONObject(i);
                if (mod == null) continue;
                String id = mod.optString("id", "");
                if (!id.isEmpty()) ids.add(id + " " + mod.optString("version", ""));
            }
            Files.move(outputReport.toPath(), finalReport.toPath(), StandardCopyOption.REPLACE_EXISTING);
            notify(listener, "Verificação concluída", ids.size() + " IDs de mod resolvidos.");
            return new Result(ids, finalReport, finalLog);
        } finally {
            deleteTree(workspace);
        }
    }

    private static void sanitizeJar(File source, File target, File tempDir, int depth,
                                    Limits limits, AtomicBoolean cancelled) throws Exception {
        checkCancelled(cancelled);
        if (depth > MAX_DEPTH) throw new IOException("Profundidade de JAR aninhado excedida.");
        ensureDirectory(target.getParentFile());
        File partial = new File(target.getParentFile(), "." + target.getName() + ".part");
        if (partial.exists() && !partial.delete()) throw new IOException("Não foi possível limpar a cópia parcial.");

        try (ZipFile zip = new ZipFile(source)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            if (entries.size() > MAX_ENTRIES) throw new IOException("JAR possui entradas demais: " + source.getName());
            ZipEntry metaEntry = zip.getEntry("fabric.mod.json");
            if (metaEntry == null || metaEntry.isDirectory()
                    || metaEntry.getSize() <= 0 || metaEntry.getSize() > MAX_METADATA_BYTES) {
                throw new IOException("fabric.mod.json ausente ou inválido: " + source.getName());
            }
            JSONObject metadata;
            try (InputStream in = zip.getInputStream(metaEntry)) {
                metadata = new JSONObject(new String(readLimited(in, MAX_METADATA_BYTES), StandardCharsets.UTF_8));
            }
            Set<String> nestedPaths = new HashSet<>();
            JSONArray declarations = metadata.optJSONArray("jars");
            if (declarations != null) {
                for (int i = 0; i < declarations.length(); i++) {
                    JSONObject declared = declarations.optJSONObject(i);
                    if (declared == null) throw new IOException("Declaração de JAR aninhado inválida.");
                    String nestedPath = declared.optString("file", "");
                    if (!safeArchivePath(nestedPath) || !nestedPath.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        throw new IOException("Caminho de JAR aninhado inseguro: " + nestedPath);
                    }
                    if (!nestedPaths.add(nestedPath)) throw new IOException("JAR aninhado declarado duas vezes: " + nestedPath);
                    ZipEntry nested = zip.getEntry(nestedPath);
                    if (nested == null || nested.isDirectory()) throw new IOException("JAR aninhado declarado e ausente: " + nestedPath);
                    if (nested.getSize() > MAX_NESTED_BYTES) throw new IOException("JAR aninhado excede 32 MiB: " + nestedPath);
                    limits.nested(nested.getSize());
                }
            }

            metadata.remove("entrypoints");
            metadata.remove("initializers");
            metadata.remove("mixins");
            metadata.remove("accessWidener");
            metadata.remove("languageAdapters");
            metadata.remove("custom");
            byte[] metadataBytes = (metadata.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] buffer = new byte[BUFFER_SIZE];
            Set<String> seen = new HashSet<>();
            try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(partial));
                 ZipOutputStream out = new ZipOutputStream(stream)) {
                for (ZipEntry entry : entries) {
                    checkCancelled(cancelled);
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!safeArchivePath(name)) throw new IOException("Entrada ZIP insegura: " + name);
                    if (name.endsWith(".class") || isSignature(name) || name.startsWith("META-INF/services/")) continue;
                    if (!seen.add(name)) throw new IOException("Entrada ZIP duplicada: " + name);
                    ZipEntry outEntry = new ZipEntry(name);
                    if (entry.getTime() > 0) outEntry.setTime(entry.getTime());
                    out.putNextEntry(outEntry);
                    if ("fabric.mod.json".equals(name)) {
                        out.write(metadataBytes);
                        limits.written(metadataBytes.length);
                    } else if (nestedPaths.contains(name)) {
                        File nestedSource = File.createTempFile("fabric-nested-src-", ".jar", tempDir);
                        File nestedSafe = File.createTempFile("fabric-nested-safe-", ".jar", tempDir);
                        try {
                            try (InputStream in = zip.getInputStream(entry);
                                 OutputStream nestedOut = new BufferedOutputStream(new FileOutputStream(nestedSource))) {
                                copyLimited(in, nestedOut, MAX_NESTED_BYTES);
                            }
                            sanitizeJar(nestedSource, nestedSafe, tempDir, depth + 1, limits, cancelled);
                            try (InputStream in = new BufferedInputStream(new FileInputStream(nestedSafe))) {
                                int count;
                                long copied = 0;
                                while ((count = in.read(buffer)) != -1) {
                                    copied += count;
                                    limits.written(count);
                                    out.write(buffer, 0, count);
                                }
                                if (copied <= 0 || copied > MAX_NESTED_BYTES) throw new IOException("JAR aninhado sanitizado inválido.");
                            }
                        } finally {
                            nestedSource.delete();
                            nestedSafe.delete();
                        }
                    } else {
                        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
                            int count;
                            while ((count = in.read(buffer)) != -1) {
                                limits.written(count);
                                out.write(buffer, 0, count);
                            }
                        }
                    }
                    out.closeEntry();
                }
                out.finish();
            }
        } catch (Exception error) {
            partial.delete();
            throw error;
        }
        try {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            partial.delete();
            throw new IOException("Não foi possível concluir a cópia segura de " + source.getName(), error);
        }
    }

    private static boolean safeArchivePath(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.startsWith("\\")
                || value.contains("\\") || value.indexOf('\0') >= 0) return false;
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        }
        return true;
    }

    private static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        String leaf = upper.substring("META-INF/".length());
        if (leaf.contains("/")) return false;
        return leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA")
                || leaf.endsWith(".EC") || leaf.startsWith("SIG-");
    }

    private static void copyLimited(InputStream in, OutputStream out, long maximum) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long count = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            count += read;
            if (count > maximum) throw new IOException("JAR aninhado excede o limite.");
            out.write(buffer, 0, read);
        }
    }

    private static byte[] readLimited(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > maximum) throw new IOException("fabric.mod.json excede o limite.");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String safeJarName(String source) throws IOException {
        String name = source == null ? "" : source.replace('/', '_').replace('\\', '_');
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar") || name.length() > 160 || name.startsWith(".")) {
            throw new IOException("Nome de JAR inseguro: " + source);
        }
        return name;
    }

    private static void notify(ProgressListener listener, String stage, String detail) {
        if (listener != null) listener.onProgress(stage, detail);
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled != null && cancelled.get()) throw new IOException("Pré-verificação Fabric cancelada.");
        if (Thread.currentThread().isInterrupted()) throw new IOException("Pré-verificação Fabric interrompida.");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Não foi possível criar a pasta " + directory.getAbsolutePath());
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("Não foi possível remover " + file.getAbsolutePath());
    }

    private static String tail(File file, int maxChars) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            int start = Math.max(0, bytes.length - maxChars);
            return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "Log indisponível: " + file.getAbsolutePath();
        }
    }
}
