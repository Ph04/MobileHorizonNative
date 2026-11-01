package dev.mobilehorizon.app;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Adaptive memory-pressure guard for the NativeActivity process.
 *
 * It never calls System.gc() or kills game tasks. It exports memory pressure
 * for Java Bridge backpressure and sizes the embedded JVM conservatively.
 * Vanilla render distance and chunk loading are not modified.
 *
 * The monitor runs independently from the game/UI callbacks so pressure sampling
 * never blocks the render or input path. Heap/CPU options are exported before
 * NativeActivity starts the embedded Java Bridge.
 */
final class GameMemoryGuard {
    private static final String TAG = "MobileHorizon-Memory";
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;
    private static final long SAMPLE_SECONDS = 3L;
    private static final int RECOVERY_SAMPLES = 3;

    private final Context appContext;
    private final ActivityManager activityManager;
    private final File pressureFile;
    private final File logFile;
    private final File logsDirectory;
    private final Object logLock = new Object();

    private volatile int currentLevel;
    private long lastCpuSampleElapsedMs;
    private long lastCpuSampleTimeMs;
    private int recoverySamples;
    private long lastDiagnosticAt;
    private ScheduledExecutorService executor;
    private boolean started;

    GameMemoryGuard(Context context, File filesDir) {
        this.appContext = context.getApplicationContext();
        this.activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        this.logsDirectory = new File(filesDir, "logs");
        this.pressureFile = new File(filesDir, "horizon-memory-pressure.level");
        this.logFile = new File(logsDirectory, "memory-guard.log");
    }

    synchronized void start() {
        if (started) return;
        started = true;

        if (!logsDirectory.isDirectory() && !logsDirectory.mkdirs()) {
            Log.e(TAG, "Não foi possível criar a pasta de diagnóstico: " + logsDirectory);
        }
        appendLog("SESSION_BEGIN wallClock=" + new Date()
                + ", pid=" + Process.myPid() + ", uid=" + Process.myUid()
                + ", processName=" + appContext.getPackageName() + ":game"
                + ", android=" + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                + ", device=" + Build.MANUFACTURER + " " + Build.MODEL
                + ", supportedAbis=" + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        logHistoricalProcessExits();

        long totalRam = readTotalRam();
        int heapInitialMiB = recommendedJavaHeapInitialMiB(totalRam);
        int heapMaxMiB = recommendedJavaHeapMaxMiB(totalRam);
        int javaProcessors = recommendedJavaProcessorCount(totalRam);
        int gcThreads = recommendedJavaGcThreadCount(totalRam);
        setEnvironment("HORIZON_MEMORY_PRESSURE_FILE", pressureFile.getAbsolutePath());
        setEnvironment("HORIZON_JAVA_DIAGNOSTICS_DIR", logsDirectory.getAbsolutePath());
        setEnvironment("HORIZON_JAVA_HEAP_INITIAL_MIB", Integer.toString(heapInitialMiB));
        setEnvironment("HORIZON_JAVA_HEAP_MAX_MIB", Integer.toString(heapMaxMiB));
        setEnvironment("HORIZON_JAVA_PROCESSORS", Integer.toString(javaProcessors));
        setEnvironment("HORIZON_JAVA_GC_THREADS", Integer.toString(gcThreads));

        currentLevel = 0;
        writePressureFile(0);
        appendLog("started; totalRamMiB=" + toMiB(totalRam)
                + ", javaHeapInitialMiB=" + heapInitialMiB
                + ", javaHeapMaxMiB=" + heapMaxMiB
                + ", javaProcessors=" + javaProcessors
                + ", javaGcThreads=" + gcThreads
                + ", memoryClassMiB=" + (activityManager == null ? -1 : activityManager.getMemoryClass())
                + ", largeMemoryClassMiB=" + (activityManager == null ? -1 : activityManager.getLargeMemoryClass())
                + ", logsDirectory=" + logsDirectory.getAbsolutePath());
        appendLog("DIAGNOSTICS_ENABLED fields=AndroidMemoryInfo,DebugMemoryInfo(PSS split),ART heap,native heap,"
                + "/proc/self/status,/proc/meminfo,/proc/self/smaps_rollup,cgroup,memory PSI,thermal,"
                + "CPU time,ApplicationExitInfo,HotSpot GC log,HotSpot fatal error file,JNI/Fabric tick timing");

        sampleSafely();
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "MobileHorizon-MemoryGuard");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::sampleSafely, SAMPLE_SECONDS, SAMPLE_SECONDS, TimeUnit.SECONDS);
    }

    synchronized void stop() {
        started = false;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) current.shutdownNow();
        appendLog("SESSION_STOPPED elapsedRealtimeMs=" + SystemClock.elapsedRealtime()
                + ", pid=" + Process.myPid());
    }

    void recordEvent(String event, String details) {
        appendLog("ACTIVITY_EVENT name=" + event + ", elapsedRealtimeMs="
                + SystemClock.elapsedRealtime() + ", details=" + details);
    }

    void recordException(String event, Throwable error) {
        if (error == null) {
            recordEvent(event, "error=null");
            return;
        }
        java.io.StringWriter trace = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(trace));
        String stack = trace.toString();
        final int maxStackChars = 96 * 1024;
        if (stack.length() > maxStackChars) {
            stack = stack.substring(0, maxStackChars)
                    + "\n[stack trace truncated at 96 KiB]";
        }
        String message = error.getMessage();
        appendLog("JAVA_EXCEPTION name=" + event
                + ", type=" + error.getClass().getName()
                + ", message=" + (message == null ? "<null>" : message)
                + "\n" + stack);
    }

    void onTrimMemory(int trimLevel) {
        appendLog("ANDROID_TRIM_MEMORY level=" + trimLevel
                + " (" + trimLevelName(trimLevel) + "), elapsedRealtimeMs="
                + SystemClock.elapsedRealtime());
        // UI_HIDDEN/background callbacks are lifecycle notifications, not
        // evidence that the foreground game is about to exhaust memory.
        if (trimLevel >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                && trimLevel < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            forceAtLeast(3, "Android TRIM_MEMORY_RUNNING_CRITICAL");
        } else if (trimLevel >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                && trimLevel < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            forceAtLeast(2, "Android TRIM_MEMORY_RUNNING_LOW");
        } else if (trimLevel >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
                && trimLevel < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            forceAtLeast(1, "Android TRIM_MEMORY_RUNNING_MODERATE");
        }
    }

    void onLowMemory() {
        appendLog("ANDROID_LOW_MEMORY_CALLBACK elapsedRealtimeMs=" + SystemClock.elapsedRealtime());
        forceAtLeast(3, "Android onLowMemory()");
    }

    private static String trimLevelName(int level) {
        switch (level) {
            case android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE: return "RUNNING_MODERATE";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW: return "RUNNING_LOW";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL: return "RUNNING_CRITICAL";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN: return "UI_HIDDEN";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND: return "BACKGROUND";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE: return "MODERATE";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE: return "COMPLETE";
            default: return "UNKNOWN";
        }
    }

    private void sampleSafely() {
        try {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            if (activityManager != null) activityManager.getMemoryInfo(info);
            long total = info.totalMem > 0 ? info.totalMem : readTotalRam();
            long available = info.availMem;
            long pssKb = 0L;
            Debug.MemoryInfo processMemory = new Debug.MemoryInfo();
            try {
                // PSS includes ART, HotSpot, native and mapped pages visible to the kernel.
                pssKb = Math.max(0L, Debug.getPss());
                Debug.getMemoryInfo(processMemory);
            } catch (RuntimeException error) {
                appendLog("MEMORY_SAMPLE_WARNING Debug.MemoryInfo failed: " + error);
            }
            int memoryClass = activityManager == null
                    ? 256 : Math.max(128, activityManager.getMemoryClass());
            int observed = classifyPressure(
                    total, available, pssKb, memoryClass, info.lowMemory);
            updateObservedLevel(observed, total, available, pssKb);
            appendDetailedSample(info, processMemory, pssKb, memoryClass);
        } catch (RuntimeException error) {
            appendLog("MEMORY_SAMPLE_ERROR type=" + error.getClass().getName()
                    + ", message=" + error.getMessage());
            Log.w(TAG, "Falha ao consultar memória; mantendo o último nível de proteção.", error);
        }
    }

    private void appendDetailedSample(
            ActivityManager.MemoryInfo system,
            Debug.MemoryInfo process,
            long pssKb,
            int memoryClassMiB) {
        long nowElapsed = SystemClock.elapsedRealtime();
        long cpuMs = Process.getElapsedCpuTime();
        String cpu = "first-sample";
        if (lastCpuSampleElapsedMs > 0 && nowElapsed > lastCpuSampleElapsedMs) {
            long elapsedDelta = nowElapsed - lastCpuSampleElapsedMs;
            long cpuDelta = Math.max(0L, cpuMs - lastCpuSampleTimeMs);
            cpu = String.format(java.util.Locale.ROOT, "%.1f%%", 100.0d * cpuDelta / elapsedDelta);
        }
        lastCpuSampleElapsedMs = nowElapsed;
        lastCpuSampleTimeMs = cpuMs;

        Runtime art = Runtime.getRuntime();
        long artUsed = art.totalMemory() - art.freeMemory();
        long nativeAllocated = Debug.getNativeHeapAllocatedSize();
        long nativeSize = Debug.getNativeHeapSize();
        long nativeFree = Debug.getNativeHeapFreeSize();

        String memoryStats = "unavailable";
        try {
            Map<String, String> values = new TreeMap<>(process.getMemoryStats());
            StringBuilder selected = new StringBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey().startsWith("summary.")) {
                    if (selected.length() > 0) selected.append(';');
                    selected.append(entry.getKey()).append('=').append(entry.getValue());
                }
            }
            if (selected.length() > 0) memoryStats = selected.toString();
        } catch (RuntimeException error) {
            memoryStats = "error:" + error.getClass().getSimpleName();
        }

        String thermal = "unavailable";
        try {
            PowerManager power = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) {
                int status = power.getCurrentThermalStatus();
                thermal = status + ":" + thermalStatusName(status);
            }
        } catch (RuntimeException error) {
            thermal = "error:" + error.getClass().getSimpleName();
        }

        long storageAvailable = new StatFs(appContext.getFilesDir().getAbsolutePath()).getAvailableBytes();
        appendLog("MEMORY_SAMPLE elapsedMs=" + nowElapsed
                + ", wallMs=" + System.currentTimeMillis()
                + ", pressure=" + currentLevel
                + ", lowMemory=" + system.lowMemory
                + ", availMiB=" + toMiB(system.availMem)
                + ", thresholdMiB=" + toMiB(system.threshold)
                + ", totalRamMiB=" + toMiB(system.totalMem)
                + ", processPssMiB=" + toMiB(pssKb * 1024L)
                + ", pssJavaHeapMiB=" + memoryStatMiB(process.getMemoryStats(), "summary.java-heap")
                + ", pssNativeHeapMiB=" + memoryStatMiB(process.getMemoryStats(), "summary.native-heap")
                + ", pssGraphicsMiB=" + memoryStatMiB(process.getMemoryStats(), "summary.graphics")
                + ", pssCodeMiB=" + memoryStatMiB(process.getMemoryStats(), "summary.code")
                + ", pssStackMiB=" + memoryStatMiB(process.getMemoryStats(), "summary.stack")
                + ", pssPrivateOtherMiB=" + memoryStatMiB(process.getMemoryStats(), "summary.private-other")
                + ", pssSystemMiB=" + memoryStatMiB(process.getMemoryStats(), "summary.system")
                + ", privateDirtyMiB=" + String.format(java.util.Locale.ROOT, "%.1f", process.getTotalPrivateDirty() / 1024.0d)
                + ", sharedDirtyMiB=" + String.format(java.util.Locale.ROOT, "%.1f", process.getTotalSharedDirty() / 1024.0d)
                + ", swapPssMiB=" + String.format(java.util.Locale.ROOT, "%.1f", process.getTotalSwappablePss() / 1024.0d)
                + ", memoryClassMiB=" + memoryClassMiB
                + ", largeMemoryClassMiB=" + (activityManager == null ? -1 : activityManager.getLargeMemoryClass())
                + ", ART_heapUsedMiB=" + toMiB(artUsed)
                + ", ART_heapCommittedMiB=" + toMiB(art.totalMemory())
                + ", ART_heapMaxMiB=" + toMiB(art.maxMemory())
                + ", nativeHeapAllocatedMiB=" + toMiB(nativeAllocated)
                + ", nativeHeapSizeMiB=" + toMiB(nativeSize)
                + ", nativeHeapFreeMiB=" + toMiB(nativeFree)
                + ", processCpuPercent=" + cpu
                + ", processCpuTimeMs=" + cpuMs
                + ", storageAvailableMiB=" + toMiB(storageAvailable)
                + ", thermalStatus=" + thermal
                + ", debugSummary={" + memoryStats + "}"
                + ", procStatus={" + readProcLines("/proc/self/status",
                    new String[]{"VmPeak:", "VmSize:", "VmHWM:", "VmRSS:", "RssAnon:", "RssFile:", "RssShmem:", "VmSwap:", "Threads:"}) + "}"
                + ", procMeminfo={" + readProcLines("/proc/meminfo",
                    new String[]{"MemTotal:", "MemFree:", "MemAvailable:", "Buffers:", "Cached:", "SwapTotal:", "SwapFree:", "SReclaimable:"}) + "}"
                + ", smapsRollup={" + readProcLines("/proc/self/smaps_rollup",
                    new String[]{"Rss:", "Pss:", "Pss_Anon:", "Pss_File:", "Pss_Shmem:", "Private_Clean:", "Private_Dirty:", "Shared_Clean:", "Shared_Dirty:", "Swap:"}) + "}"
                + ", memoryPsi={" + readSmallFile("/proc/pressure/memory", 1024) + "}"
                + ", cgroupMemory={" + readCgroupMemory() + "}");
    }

    private static long memoryStatMiB(Map<String, String> stats, String key) {
        if (stats == null) return -1L;
        try {
            String value = stats.get(key);
            if (value == null || value.isEmpty()) return -1L;
            // Android's Debug.MemoryInfo summary counters are reported in KiB.
            return Long.parseLong(value) / 1024L;
        } catch (NumberFormatException error) {
            return -1L;
        }
    }

    private static String readProcLines(String path, String[] prefixes) {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String prefix : prefixes) {
                    if (line.startsWith(prefix)) {
                        if (result.length() > 0) result.append(';');
                        result.append(line.trim());
                        break;
                    }
                }
            }
        } catch (Exception error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
        return result.length() == 0 ? "empty-or-denied" : result.toString();
    }

    private static String readSmallFile(String path, int maxChars) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8))) {
            StringBuilder value = new StringBuilder();
            char[] buffer = new char[256];
            int read;
            while ((read = reader.read(buffer)) > 0 && value.length() < maxChars) {
                value.append(buffer, 0, Math.min(read, maxChars - value.length()));
            }
            return value.length() == 0 ? "empty-or-denied" : value.toString().replace('\n', ';');
        } catch (Exception error) {
            return "unavailable:" + error.getClass().getSimpleName();
        }
    }

    private static String readCgroupMemory() {
        String[] paths = {
                "/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory.max",
                "/sys/fs/cgroup/memory/memory.usage_in_bytes",
                "/sys/fs/cgroup/memory/memory.limit_in_bytes"
        };
        StringBuilder result = new StringBuilder();
        for (String path : paths) {
            String value = readSmallFile(path, 128);
            if (!value.startsWith("unavailable:")) {
                if (result.length() > 0) result.append(';');
                result.append(path).append('=').append(value.trim());
            }
        }
        return result.length() == 0 ? "unavailable" : result.toString();
    }

    private static String thermalStatusName(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "NONE";
            case PowerManager.THERMAL_STATUS_LIGHT: return "LIGHT";
            case PowerManager.THERMAL_STATUS_MODERATE: return "MODERATE";
            case PowerManager.THERMAL_STATUS_SEVERE: return "SEVERE";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "CRITICAL";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "EMERGENCY";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "SHUTDOWN";
            default: return "UNKNOWN";
        }
    }

    /**
     * Refreshes Android's exit history when the user exports diagnostics from the
     * launcher. The game process may have been killed before Activity callbacks could
     * persist anything, and starting NativeGameActivity again should not be required
     * just to learn why the previous :game process ended.
     */
    static void captureHistoricalExitsForDiagnostics(Context context, File filesDir) {
        File directory = new File(filesDir, "logs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.e(TAG, "Não foi possível criar a pasta para o histórico de encerramentos: " + directory);
            return;
        }

        StringBuilder snapshot = new StringBuilder();
        snapshot.append("MobileHorizon Android process exit history\n")
                .append("capturedAt=").append(new Date()).append("\n")
                .append("package=").append(context.getPackageName()).append("\n")
                .append("currentPid=").append(Process.myPid()).append("\n");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            snapshot.append("EXIT_HISTORY unavailable: Android API below 30\n");
            writeExitHistorySnapshot(directory, snapshot.toString());
            return;
        }

        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                snapshot.append("EXIT_HISTORY unavailable: ActivityManager is null\n");
                writeExitHistorySnapshot(directory, snapshot.toString());
                return;
            }
            List<ApplicationExitInfo> exits =
                    manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 12);
            if (exits == null || exits.isEmpty()) {
                snapshot.append("EXIT_HISTORY no process exits returned by Android\n");
            } else {
                snapshot.append("EXIT_HISTORY count=").append(exits.size()).append("\n");
                for (ApplicationExitInfo exit : exits) {
                    String description = exit.getDescription();
                    if (description == null) description = "";
                    description = description.replace('\n', ' ').replace('\r', ' ');
                    snapshot.append("EXIT_HISTORY_ENTRY timestamp=").append(new Date(exit.getTimestamp()))
                            .append(", pid=").append(exit.getPid())
                            .append(", process=").append(exit.getProcessName())
                            .append(", reason=").append(exit.getReason()).append(":").append(exitReasonName(exit.getReason()))
                            .append(", status=").append(exit.getStatus())
                            .append(", importance=").append(exit.getImportance())
                            .append(", pssMiB=").append(toMiB(exit.getPss() * 1024L))
                            .append(", rssMiB=").append(toMiB(exit.getRss() * 1024L))
                            .append(", description=").append(description)
                            .append("\n");
                    captureDiagnosticExitTrace(directory, exit, snapshot);
                }
            }
        } catch (RuntimeException error) {
            snapshot.append("EXIT_HISTORY_ERROR ").append(error.getClass().getName())
                    .append(": ").append(error.getMessage()).append("\n");
        }
        writeExitHistorySnapshot(directory, snapshot.toString());
    }

    private static void captureDiagnosticExitTrace(
            File directory, ApplicationExitInfo exit, StringBuilder snapshot) {
        InputStream trace = null;
        try {
            trace = exit.getTraceInputStream();
            if (trace == null) {
                snapshot.append("EXIT_TRACE_UNAVAILABLE pid=").append(exit.getPid())
                        .append(", reason=no trace stream\n");
                return;
            }
            File target = new File(directory,
                    "exit-trace-" + exit.getTimestamp() + "-pid" + exit.getPid() + ".txt");
            try (InputStream input = trace; FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while (total < 512 * 1024
                        && (read = input.read(buffer, 0, Math.min(buffer.length, 512 * 1024 - total))) > 0) {
                    output.write(buffer, 0, read);
                    total += read;
                }
                output.flush();
                output.getFD().sync();
                snapshot.append("EXIT_TRACE_SAVED pid=").append(exit.getPid())
                        .append(", file=").append(target.getName())
                        .append(", bytes=").append(total)
                        .append(", cappedAt512KiB=true\n");
            }
        } catch (Exception error) {
            if (trace != null) {
                try { trace.close(); } catch (Exception ignored) { }
            }
            snapshot.append("EXIT_TRACE_UNAVAILABLE pid=").append(exit.getPid())
                    .append(", reason=").append(error.getClass().getSimpleName())
                    .append(":").append(error.getMessage()).append("\n");
        }
    }

    private static void writeExitHistorySnapshot(File directory, String content) {
        File target = new File(directory, "android-exit-history.log");
        File temporary = new File(directory, ".android-exit-history.log.tmp");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "Não foi possível gravar android-exit-history.log.", error);
            temporary.delete();
            return;
        }
        try {
            android.system.Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
        } catch (Exception renameError) {
            if (!temporary.renameTo(target)) {
                Log.e(TAG, "Não foi possível publicar android-exit-history.log.", renameError);
                temporary.delete();
            }
        }
    }

    private void logHistoricalProcessExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || activityManager == null) {
            appendLog("EXIT_HISTORY unavailable: Android API below 30");
            return;
        }
        try {
            List<ApplicationExitInfo> exits =
                    activityManager.getHistoricalProcessExitReasons(appContext.getPackageName(), 0, 12);
            if (exits == null || exits.isEmpty()) {
                appendLog("EXIT_HISTORY no prior process exits returned by Android");
                return;
            }
            appendLog("EXIT_HISTORY count=" + exits.size());
            for (ApplicationExitInfo exit : exits) {
                String details = "timestamp=" + new Date(exit.getTimestamp())
                        + ", pid=" + exit.getPid()
                        + ", process=" + exit.getProcessName()
                        + ", reason=" + exit.getReason() + ":" + exitReasonName(exit.getReason())
                        + ", status=" + exit.getStatus()
                        + ", importance=" + exit.getImportance()
                        + ", pssMiB=" + toMiB(exit.getPss() * 1024L)
                        + ", rssMiB=" + toMiB(exit.getRss() * 1024L)
                        + ", description=" + exit.getDescription();
                appendLog("EXIT_HISTORY_ENTRY " + details);
                captureExitTrace(exit);
            }
        } catch (RuntimeException error) {
            appendLog("EXIT_HISTORY_ERROR " + error.getClass().getName() + ": " + error.getMessage());
        }
    }

    private void captureExitTrace(ApplicationExitInfo exit) {
        InputStream trace = null;
        try {
            trace = exit.getTraceInputStream();
            if (trace == null) return;
            File target = new File(logsDirectory,
                    "exit-trace-" + exit.getTimestamp() + "-pid" + exit.getPid() + ".txt");
            try (InputStream input = trace; FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while (total < 512 * 1024
                        && (read = input.read(buffer, 0, Math.min(buffer.length, 512 * 1024 - total))) > 0) {
                    output.write(buffer, 0, read);
                    total += read;
                }
                output.flush();
                appendLog("EXIT_TRACE_SAVED file=" + target.getName() + ", bytes=" + total
                        + ", cappedAt512KiB=true");
            }
        } catch (Exception error) {
            if (trace != null) {
                try { trace.close(); } catch (Exception ignored) { }
            }
            appendLog("EXIT_TRACE_UNAVAILABLE pid=" + exit.getPid()
                    + ", reason=" + error.getClass().getSimpleName() + ":" + error.getMessage());
        }
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN";
        }
    }

    private synchronized void updateObservedLevel(
            int observed, long total, long available, long pssKb) {
        int old = currentLevel;
        if (observed > currentLevel) {
            currentLevel = observed;
            recoverySamples = 0;
        } else if (observed < currentLevel) {
            recoverySamples++;
            if (recoverySamples >= RECOVERY_SAMPLES) {
                // Recover one step at a time to avoid oscillating while chunks load.
                currentLevel = Math.max(observed, currentLevel - 1);
                recoverySamples = 0;
            }
        } else {
            recoverySamples = 0;
        }

        if (currentLevel != old) {
            writePressureFile(currentLevel);
            appendLog("pressure " + old + " -> " + currentLevel
                    + "; availableMiB=" + toMiB(available)
                    + ", processPssMiB=" + toMiB(pssKb * 1024L)
                    + ", totalMiB=" + toMiB(total));
        } else {
            long now = SystemClock.elapsedRealtime();
            if (now - lastDiagnosticAt >= 30_000L) {
                lastDiagnosticAt = now;
                appendLog("sample; pressure=" + currentLevel
                        + ", availableMiB=" + toMiB(available)
                        + ", processPssMiB=" + toMiB(pssKb * 1024L)
                        + ", totalMiB=" + toMiB(total));
            }
        }
    }

    private synchronized void forceAtLeast(int requested, String reason) {
        recoverySamples = 0;
        appendLog("PRESSURE_TRIGGER requested=" + requested + ", current=" + currentLevel
                + ", reason=" + reason + ", elapsedRealtimeMs=" + SystemClock.elapsedRealtime());
        if (requested <= currentLevel) return;
        int old = currentLevel;
        currentLevel = Math.min(3, requested);
        writePressureFile(currentLevel);
        appendLog("pressure " + old + " -> " + currentLevel + "; reason=" + reason);
    }

    static int classifyPressure(
            long totalBytes,
            long availableBytes,
            long processPssKb,
            int memoryClassMb,
            boolean lowMemory) {
        if (lowMemory) return 3;

        long total = Math.max(512L * MIB, totalBytes);
        long available = Math.max(0L, availableBytes);
        long pssBytes = Math.max(0L, processPssKb) * 1024L;
        long appClass = Math.max(128, memoryClassMb) * MIB;

        long moderateAvailable = Math.max(480L * MIB, total * 13L / 100L);
        long highAvailable = Math.max(360L * MIB, total * 9L / 100L);
        long criticalAvailable = Math.max(220L * MIB, total * 6L / 100L);

        long moderatePss = Math.max(appClass * 4L, total * 28L / 100L);
        long highPss = Math.max(appClass * 5L, total * 35L / 100L);
        long criticalPss = Math.max(appClass * 6L, total * 42L / 100L);

        int level = 0;
        if (available <= moderateAvailable || pssBytes >= moderatePss) level = 1;
        if (available <= highAvailable || pssBytes >= highPss) level = 2;
        if (available <= criticalAvailable || pssBytes >= criticalPss) level = 3;
        return level;
    }

    static int recommendedJavaHeapInitialMiB(long totalRamBytes) {
        if (totalRamBytes <= 5L * GIB) return 128;
        if (totalRamBytes <= 7L * GIB) return 192;
        if (totalRamBytes <= 10L * GIB) return 256;
        return 384;
    }

    static int recommendedJavaHeapMaxMiB(long totalRamBytes) {
        // The Android renderer and embedded HotSpot share one process. On 4 GB
        // phones, leave more headroom for Vulkan/native allocations and the OS.
        // The diagnostic ZIP from an SM-A155M showed ~2.3 GiB PSS with only
        // ~300 MiB of live HotSpot heap, so an 896 MiB ceiling was too loose.
        if (totalRamBytes <= 5L * GIB) return 640;
        if (totalRamBytes <= 7L * GIB) return 1024;
        if (totalRamBytes <= 10L * GIB) return 1536;
        return 2048;
    }

    static int recommendedJavaProcessorCount(long totalRamBytes) {
        if (totalRamBytes <= 5L * GIB) return 2;
        if (totalRamBytes <= 7L * GIB) return 3;
        return 4;
    }

    static int recommendedJavaGcThreadCount(long totalRamBytes) {
        return totalRamBytes <= 7L * GIB ? 1 : 2;
    }

    private long readTotalRam() {
        if (activityManager != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(info);
            if (info.totalMem > 0) return info.totalMem;
        }
        return Runtime.getRuntime().maxMemory() * 4L;
    }

    private void writePressureFile(int level) {
        File parent = pressureFile.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File temporary = new File(parent, pressureFile.getName() + ".tmp");
        byte[] bytes = (Integer.toString(level) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
        } catch (Exception error) {
            Log.w(TAG, "Não foi possível gravar o nível de pressão de memória.", error);
            return;
        }
        try {
            android.system.Os.rename(temporary.getAbsolutePath(), pressureFile.getAbsolutePath());
        } catch (Exception renameError) {
            if (!temporary.renameTo(pressureFile)) {
                try (FileOutputStream output = new FileOutputStream(pressureFile, false)) {
                    output.write(bytes);
                } catch (Exception writeError) {
                    Log.w(TAG, "Não foi possível atualizar o estado de pressão de memória.", writeError);
                }
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }

    private void setEnvironment(String key, String value) {
        try {
            android.system.Os.setenv(key, value, true);
        } catch (Exception error) {
            appendLog("environment warning: " + key + " could not be set: " + error);
        }
    }

    private void appendLog(String message) {
        String line = "[" + System.currentTimeMillis() + "][elapsedMs="
                + SystemClock.elapsedRealtime() + "][pid=" + Process.myPid() + "] " + message;
        Log.i(TAG, line);
        synchronized (logLock) {
            try {
                if (logFile.isFile() && logFile.length() > 5L * 1024L * 1024L) {
                    File previous = new File(logFile.getParentFile(), logFile.getName() + ".1");
                    //noinspection ResultOfMethodCallIgnored
                    previous.delete();
                    if (!logFile.renameTo(previous)) {
                        Log.w(TAG, "Não foi possível rotacionar memory-guard.log; mantendo arquivo atual.");
                    }
                }
                try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                    output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (Exception error) {
                Log.w(TAG, "Não foi possível gravar memory-guard.log.", error);
            }
        }
    }

    private static long toMiB(long bytes) {
        return Math.max(0L, bytes) / MIB;
    }
}
