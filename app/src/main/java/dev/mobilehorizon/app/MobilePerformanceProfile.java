package dev.mobilehorizon.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-time conservative defaults for weaker Android devices; user overrides are preserved. */
final class MobilePerformanceProfile {
    private static final String MARKER = ".mobilehorizon-performance-v3";

    private static final class Setting {
        final String defaultValue;
        final String mobileValue;
        final String[] previousMobileValues;
        Setting(String defaultValue, String mobileValue) {
            this(defaultValue, mobileValue, new String[0]);
        }
        Setting(String defaultValue, String mobileValue, String... previousMobileValues) {
            this.defaultValue = defaultValue;
            this.mobileValue = mobileValue;
            this.previousMobileValues = previousMobileValues == null
                    ? new String[0] : previousMobileValues.clone();
        }
    }

    private static final Map<String, Setting> MOBILE_SETTINGS = new LinkedHashMap<>();
    static {
        MOBILE_SETTINGS.put("renderDistance", new Setting("12", "3", "4", "6"));
        MOBILE_SETTINGS.put("simulationDistance", new Setting("12", "3", "4", "5"));
        MOBILE_SETTINGS.put("maxFps", new Setting("120", "30"));
        MOBILE_SETTINGS.put("ao", new Setting("true", "false"));
        MOBILE_SETTINGS.put("renderClouds", new Setting("true", "\"false\""));
        MOBILE_SETTINGS.put("particles", new Setting("0", "2"));
        MOBILE_SETTINGS.put("entityShadows", new Setting("true", "false"));
        MOBILE_SETTINGS.put("entityDistanceScaling", new Setting("1.0", "0.60", "0.75"));
        MOBILE_SETTINGS.put("biomeBlendRadius", new Setting("2", "0"));
        MOBILE_SETTINGS.put("graphicsPreset", new Setting("fancy", "\"fast\""));
        MOBILE_SETTINGS.put("mipmapLevels", new Setting("4", "0"));
        MOBILE_SETTINGS.put("weatherRadius", new Setting("10", "3", "5"));
        MOBILE_SETTINGS.put("vignette", new Setting("true", "false"));
        MOBILE_SETTINGS.put("cutoutLeaves", new Setting("true", "false"));
        MOBILE_SETTINGS.put("menuBackgroundBlurriness", new Setting("5", "0"));
    }

    private MobilePerformanceProfile() {}

    static void applyInitialOptions(Context context) throws Exception {
        File root = context.getFilesDir();
        File marker = new File(root, MARKER);
        if (marker.isFile()) return;

        File options = new File(root, "options.txt");
        String oldText = options.isFile() ? read(options) : "version:4000\n";
        String tuned = tuneOptionsText(oldText);
        File temporary = new File(root, "options.txt.mobilehorizon.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(tuned.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), options.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary.toPath(), options.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        try (FileOutputStream output = new FileOutputStream(marker, false)) {
            output.write(("applied=" + System.currentTimeMillis() + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    static String tuneOptionsText(String input) {
        List<String> lines = new ArrayList<>();
        String[] split = (input == null ? "" : input).split("\\r?\\n", -1);
        for (String line : split) {
            if (!line.isEmpty() || lines.isEmpty()) lines.add(line);
        }
        if (lines.isEmpty()) lines.add("version:4000");

        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            int colon = lines.get(i).indexOf(':');
            if (colon > 0) indexes.put(lines.get(i).substring(0, colon).trim(), i);
        }
        if (!indexes.containsKey("version")) {
            lines.add(0, "version:4000");
            indexes.clear();
            for (int i = 0; i < lines.size(); i++) {
                int colon = lines.get(i).indexOf(':');
                if (colon > 0) indexes.put(lines.get(i).substring(0, colon).trim(), i);
            }
        }

        for (Map.Entry<String, Setting> entry : MOBILE_SETTINGS.entrySet()) {
            String key = entry.getKey();
            Setting setting = entry.getValue();
            Integer index = indexes.get(key);
            if (index == null) {
                lines.add(key + ":" + setting.mobileValue);
                indexes.put(key, lines.size() - 1);
            } else {
                String line = lines.get(index);
                String current = line.substring(line.indexOf(':') + 1).trim();
                String normalized = normalize(current);
                boolean stockDefault = normalized.equals(normalize(setting.defaultValue));
                boolean knownPreviousMobileValue = false;
                for (String previousValue : setting.previousMobileValues) {
                    if (normalized.equals(normalize(previousValue))) {
                        knownPreviousMobileValue = true;
                        break;
                    }
                }
                if (stockDefault || knownPreviousMobileValue) {
                    lines.set(index, key + ":" + setting.mobileValue);
                }
            }
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return String.join("\n", lines) + "\n";
    }

    private static String normalize(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static String read(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > 1024 * 1024) throw new java.io.IOException("options.txt excede 1 MiB.");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
