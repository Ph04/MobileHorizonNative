package dev.mobilehorizon.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit, user-confirmed probe that asks the official Fabric Loader to inspect and
 * execute the active original mod JARs in a separate OpenJDK process.
 *
 * This is intentionally not called during game startup. It is not a sandbox, and
 * success only proves the reported stage; the Horizon provider still has no full
 * Minecraft Java client classes or Fabric-to-Rust gameplay adapters.
 */
final class FabricCompatibilityTestRunner {
    interface ProgressListener { void onProgress(String stage, String detail); }

    static final class Result {
        final File report;
        final File log;
        final int exitCode;
        final String summary;
        Result(File report, File log, int exitCode, String summary) {
            this.report = report;
            this.log = log;
            this.exitCode = exitCode;
            this.summary = summary;
        }
    }

    private static final long TIMEOUT_MS = 120_000L;
    private static final int MAX_REPORT_BYTES = 1024 * 1024;
    private static final int MAX_LOG_TAIL_BYTES = 6 * 1024;

    private FabricCompatibilityTestRunner() {}

    static Result run(Context context, File activeMods, AtomicBoolean cancelled,
                      ProgressListener listener) throws Exception {
        if (cancelled != null && cancelled.get()) throw new IOException("O teste foi cancelado.");
        File apiJar = FabricApiRuntimeInstaller.ensureInstalled(context);
        File loaderJar = FabricLoaderRuntimeInstaller.ensureInstalled(context);
        File providerJar = FabricLoaderRuntimeInstaller.providerPath(context);

        File runtimeRoot = new File(new File(context.getFilesDir(), "horizon"), "fabric-bridge-runtime");
        ensureDirectory(runtimeRoot);
        File workspace = new File(runtimeRoot, "compatibility-test");
        ensureDirectory(workspace);
        File gameDir = new File(workspace, "game");
        ensureDirectory(gameDir);
        File loaderReport = new File(workspace, "loader-result.json");
        File finalReport = new File(runtimeRoot, "last-compatibility-test.json");
        File finalLog = new File(runtimeRoot, "last-compatibility-test.log");
        if (loaderReport.exists()) loaderReport.delete();

        if (activeMods == null || !activeMods.isDirectory()) {
            throw new IOException("A pasta de mods ativos do perfil Fabric não existe.");
        }
        File[] jarFiles = activeMods.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        List<File> originals = new ArrayList<>();
        if (jarFiles != null) {
            Arrays.sort(jarFiles, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File candidate : jarFiles) {
                if (!candidate.isFile() || candidate.length() <= 0) continue;
                if (candidate.getCanonicalFile().equals(apiJar.getCanonicalFile())) continue;
                originals.add(candidate.getCanonicalFile());
            }
        }
        if (originals.isEmpty()) {
            throw new IOException("Não há JARs originais de mods ativos para testar. Ative Nature's Compass ou outro mod Fabric no gerenciador.");
        }

        notify(listener, "Preparando teste do Loader",
                originals.size() + " JAR(s) original(is); o código desses mods será executado fora do processo do jogo.");
        JdkRuntimeInstaller.RuntimePaths java = JdkRuntimeInstaller.ensureInstalled(
                context, cancelled,
                (stage, detail, completed, total) -> notify(listener, stage, detail));

        List<String> command = new ArrayList<>();
        command.add("/system/bin/sh");
        command.add("-c");
        command.add("exec /system/bin/linker64 \"$@\"");
        command.add("linker64");
        command.add(java.java.getAbsolutePath());
        command.add("-Xms16m");
        command.add("-Xmx384m");
        command.add("-XX:+UseSerialGC");
        command.add("-XX:-UsePerfData");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Djava.io.tmpdir=" + workspace.getAbsolutePath());
        command.add("-Dfabric.skipMcProvider=true");
        command.add("-Dfabric.noGui=true");
        command.add("-Dfabric.development=false");
        command.add("-Dfabric.gameVersion=26.3");
        command.add("-Dfabric.gameMappingNamespace=official");
        command.add("-Dfabric.runtimeMappingNamespace=official");
        command.add("-Dfabric.modsFolder=" + activeMods.getCanonicalPath());
        command.add("-Dhorizon.fabric.provider.enabled=true");
        command.add("-Dhorizon.fabric.preflight=true");
        command.add("-Dhorizon.fabric.live=true");
        command.add("-Dhorizon.fabric.auditRoot=" + gameDir.getCanonicalPath());
        command.add("-Dhorizon.fabric.report=" + loaderReport.getAbsolutePath());
        command.add("-Dhorizon.fabric.compatibilityTest=true");
        command.add("-cp");
        command.add(FabricLoaderRuntimeInstaller.runtimeClasspath(context, loaderJar, providerJar));
        command.add("net.fabricmc.loader.impl.launch.knot.KnotClient");

        notify(listener, "Executando Fabric Loader 0.19.5",
                "Perfil isolado de teste; o Minecraft Java real e os hooks de gameplay não estão disponíveis neste processo.");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(finalLog);
        builder.environment().putAll(java.processEnvironment(workspace));
        Process process = builder.start();
        int exitCode = -1;
        String failure = "";
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        try {
            while (true) {
                if (cancelled != null && cancelled.get()) {
                    process.destroy();
                    if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                    failure = "cancelled";
                    break;
                }
                if (process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    process.destroy();
                    if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                    failure = "timeout";
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }

        JSONObject loader = readJsonIfSmall(loaderReport);
        String loaderMode = loader == null ? "" : loader.optString("mode", "");
        String loaderError = loader == null ? "" : loader.optString("error", "");
        String logTail = tail(finalLog);
        String outcome;
        if ("cancelled".equals(failure)) {
            outcome = "CANCELLED";
        } else if ("timeout".equals(failure)) {
            outcome = "TIMEOUT";
        } else if (exitCode == 0 && loader != null && loader.optBoolean("ok", false)
                && "entrypoints_smoke".equals(loaderMode)) {
            outcome = "ENTRYPOINT_SMOKE_ONLY";
        } else {
            outcome = "BLOCKED_OR_FAILED";
        }

        JSONObject report = new JSONObject();
        report.put("schema_version", 1);
        report.put("test_kind", "official_fabric_loader_original_jar_probe");
        report.put("target_minecraft", "26.3");
        report.put("loader_version", FabricLoaderRuntimeInstaller.VERSION);
        report.put("outcome", outcome);
        report.put("exit_code", exitCode);
        report.put("timeout", "timeout".equals(failure));
        report.put("cancelled", "cancelled".equals(failure));
        report.put("executed_original_mod_code", true);
        report.put("mods_directory", activeMods.getCanonicalPath());
        JSONArray modList = new JSONArray();
        for (File file : originals) {
            JSONObject item = new JSONObject();
            item.put("file", file.getName());
            item.put("size_bytes", file.length());
            modList.put(item);
        }
        report.put("original_mod_jars", modList);
        report.put("loader_mode", loaderMode);
        report.put("loader_error", loaderError);
        report.put("loader_report_path", loaderReport.getAbsolutePath());
        report.put("log_path", finalLog.getAbsolutePath());
        report.put("log_tail", logTail);
        report.put("note", "This probe has no real Minecraft Java client classes. Reaching an entrypoint is not proof of in-game compatibility.");
        Files.writeString(finalReport.toPath(), report.toString(2) + "\n", StandardCharsets.UTF_8);

        String summary;
        if ("ENTRYPOINT_SMOKE_ONLY".equals(outcome)) {
            summary = "O Loader executou entrypoints no provider sintético, mas isso NÃO comprova compatibilidade dentro do Horizon. Relatório: "
                    + finalReport.getAbsolutePath() + "\nLog: " + finalLog.getAbsolutePath();
        } else if ("TIMEOUT".equals(outcome)) {
            summary = "O Loader excedeu 120 segundos. O processo foi encerrado. Relatório: "
                    + finalReport.getAbsolutePath() + "\nLog: " + finalLog.getAbsolutePath();
        } else if ("CANCELLED".equals(outcome)) {
            summary = "Teste cancelado. Relatório parcial: " + finalReport.getAbsolutePath()
                    + "\nLog: " + finalLog.getAbsolutePath();
        } else {
            summary = "O teste com o Loader real não chegou a executar os entrypoints de todos os mods. "
                    + "Isso identifica um bloqueio no carregamento, mas ainda não é um teste de gameplay.\n"
                    + (loaderError.isEmpty() ? "" : "Erro: " + loaderError + "\n")
                    + "Relatório: " + finalReport.getAbsolutePath() + "\nLog: " + finalLog.getAbsolutePath()
                    + (logTail.isEmpty() ? "" : "\nTrecho final do log:\n" + logTail);
        }
        notify(listener, "Teste finalizado", outcome + " (código " + exitCode + ").");
        return new Result(finalReport, finalLog, exitCode, summary);
    }

    private static JSONObject readJsonIfSmall(File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_REPORT_BYTES) return null;
        try {
            return new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String tail(File file) {
        if (!file.isFile() || file.length() <= 0) return "";
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, input.length() - MAX_LOG_TAIL_BYTES);
            input.seek(start);
            byte[] bytes = new byte[(int) (input.length() - start)];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Não foi possível criar o diretório: " + directory.getAbsolutePath());
        }
    }

    private static void notify(ProgressListener listener, String stage, String detail) {
        if (listener != null) listener.onProgress(stage, detail);
    }
}
