package dev.mobilehorizon.app;

import java.util.Locale;

/**
 * Resolves names returned by Android Storage Access Framework providers.
 *
 * Some providers return a null DISPLAY_NAME or an opaque label even though the
 * document ID or URI path still contains the original filename.
 */
final class HorizonDocumentName {
    private HorizonDocumentName() { }

    static String resolve(String providerName, String documentId,
                          String lastPathSegment, String expectedExtension) {
        String extension = expectedExtension == null
                ? "" : expectedExtension.trim().toLowerCase(Locale.ROOT);
        String fallback = "";
        String[] candidates = {providerName, documentId, lastPathSegment};
        for (String candidate : candidates) {
            String name = basename(candidate);
            if (name.isEmpty()) continue;
            if (fallback.isEmpty()) fallback = name;
            if (extension.isEmpty()
                    || name.toLowerCase(Locale.ROOT).endsWith(extension)) {
                return name;
            }
        }
        return fallback;
    }

    /**
     * Native bundles are validated from their ZIP manifest and source layout, not from
     * a SAF filename that may be hidden by cloud/download providers. Binary runtime
     * assets still require their expected suffix.
     */
    static boolean requiresFilenameExtension(String expectedExtension) {
        return !".hnmod".equalsIgnoreCase(
                expectedExtension == null ? "" : expectedExtension.trim());
    }

    private static String basename(String candidate) {
        if (candidate == null) return "";
        String value = candidate.trim().replace((char) 92, '/');
        if (value.isEmpty()) return "";
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        return value.trim();
    }
}
