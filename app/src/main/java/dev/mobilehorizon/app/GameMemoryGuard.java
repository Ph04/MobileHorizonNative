package dev.mobilehorizon.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Adaptive memory-pressure guard for the NativeActivity process.
 *
 * It never calls System.gc() or kills game tasks. It exports memory pressure
 * for Java Bridge backpressure and sizes the embedded JVM conservatively.
 * Vanilla render distance and chunk loading are not modified.
 */
final class GameMemoryGuard {
    private static final String TAG = "MobileHorizon-Memory";
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;
    private static final long SAMPLE_SECONDS = 3L;
    private static final int RECOVERY_SAMPLES = 3;

    private final ActivityManager activityManager;
    private final File pressureFile;
    private final File logFile;
    private final Object logLock = new Object();

    private volatile int currentLevel;
    private int recoverySamples;
    private long lastDiagnosticAt;
    private ScheduledExecutorService executor;
    private boolean started;

    GameMemoryGuard(Context context, File filesDir) {
        Context appContext = context.getApplicationContext();
        this.activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        this.pressureFile = new File(filesDir, "horizon-memory-pressure.level");
        this.logFile = new File(new File(filesDir, "logs"), "memory-guard.log");
    }

    synchronized void start() {
        if (started) return;
        started = true;

        File logDirectory = logFile.getParentFile();
        if (logDirectory != null && !logDirectory.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            logDirectory.mkdirs();
        }

        long totalRam = readTotalRam();
        int heapInitialMiB = recommendedJavaHeapInitialMiB(totalRam);
        int heapMaxMiB = recommendedJavaHeapMaxMiB(totalRam);
        int javaProcessors = recommendedJavaProcessorCount(totalRam);
        int gcThreads = recommendedJavaGcThreadCount(totalRam);
        setEnvironment("HORIZON_MEMORY_PRESSURE_FILE", pressureFile.getAbsolutePath());
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
                + ", javaGcThreads=" + gcThreads);

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
        appendLog("stopped");
    }

    void onTrimMemory(int trimLevel) {
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
        forceAtLeast(3, "Android onLowMemory()");
    }

    private void sampleSafely() {
        try {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            if (activityManager != null) activityManager.getMemoryInfo(info);
            long total = info.totalMem > 0 ? info.totalMem : readTotalRam();
            long available = info.availMem;
            long pssKb = 0L;
            try {
                // PSS includes Java and native mappings (Horizon, Vulkan and JVM),
                // unlike Runtime.freeMemory(), which only describes the Java heap.
                pssKb = Math.max(0L, Debug.getPss());
            } catch (RuntimeException ignored) {
                // System-wide available memory remains a useful signal.
            }
            int memoryClass = activityManager == null
                    ? 256 : Math.max(128, activityManager.getMemoryClass());
            int observed = classifyPressure(
                    total, available, pssKb, memoryClass, info.lowMemory);
            updateObservedLevel(observed, total, available, pssKb);
        } catch (RuntimeException error) {
            Log.w(TAG, "Falha ao consultar memória; mantendo o último nível de proteção.", error);
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
        if (totalRamBytes <= 5L * GIB) return 896;
        if (totalRamBytes <= 7L * GIB) return 1280;
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
        String line = "[" + System.currentTimeMillis() + "] " + message;
        Log.i(TAG, line);
        synchronized (logLock) {
            File parent = logFile.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                Log.w(TAG, "Não foi possível gravar memory-guard.log.", error);
            }
        }
    }

    private static long toMiB(long bytes) {
        return Math.max(0L, bytes) / MIB;
    }
}
