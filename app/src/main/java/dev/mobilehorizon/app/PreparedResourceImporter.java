package dev.mobilehorizon.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * SAF-only importer. Files are staged in app-private storage and promoted only after
 * extraction and basic validation succeed. It deliberately does not claim that imported
 * data is compatible with a particular renderer.
 */
final class PreparedResourceImporter {
    // Prepared Horizon resource trees can contain tens of thousands of files.
    // Keep a finite ceiling for ZIP-bomb and pathological-provider protection, but
    // make it high enough for real Minecraft asset/library trees.
    private static final int MAX_ENTRIES = 250_000;
    private static final int MAX_DEPTH = 128;
    private static final long MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int MANIFEST_LIMIT = 1024 * 1024;

    interface ProgressListener {
        void onProgress(Progress progress);
    }

    static final class Progress {
        final String stage;
        final long completedFiles;
        final long totalFiles;
        final long bytesProcessed;
        final long totalBytes;

        Progress(String stage, long completedFiles, long totalFiles,
                 long bytesProcessed, long totalBytes) {
            this.stage = stage;
            this.completedFiles = completedFiles;
            this.totalFiles = totalFiles;
            this.bytesProcessed = bytesProcessed;
            this.totalBytes = totalBytes;
        }
    }

    static final class ImportSummary {
        final File destination;
        final long files;
        final long bytes;
        final String validationNote;

        ImportSummary(File destination, long files, long bytes, String validationNote) {
            this.destination = destination;
            this.files = files;
            this.bytes = bytes;
            this.validationNote = validationNote;
        }
    }

    private static final class TreeTotals {
        long files;
        long bytes;
        boolean byteSizesKnown = true;
        long entries;
    }

    private static final class Counter {
        long files;
        long bytes;
        long entries;
    }

    private PreparedResourceImporter() {}

    static ImportSummary importTree(ContentResolver resolver, Uri treeUri, File filesDir,
                                    AtomicBoolean cancelled, ProgressListener listener) throws Exception {
        return withStaging(filesDir, cancelled, listener, stage -> {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            TreeTotals totals = new TreeTotals();
            scanTree(resolver, treeUri, rootId, new HashSet<>(), totals, cancelled, listener, 0);
            if (totals.files <= 0) throw new IllegalArgumentException("A pasta selecionada não contém arquivos.");
            Counter copied = new Counter();
            copyTree(resolver, treeUri, rootId, "", stage, new HashSet<>(),
                    totals, copied, cancelled, listener, 0);
            return new long[]{copied.files, copied.bytes};
        });
    }

    static ImportSummary importZip(ContentResolver resolver, Uri zipUri, File filesDir,
                                   AtomicBoolean cancelled, ProgressListener listener) throws Exception {
        return withStaging(filesDir, cancelled, listener, stage -> {
            Counter copied = new Counter();
            report(listener, new Progress("Lendo arquivo ZIP", 0, -1, 0, -1));
            Set<String> seenPaths = new HashSet<>();
            InputStream raw = resolver.openInputStream(zipUri);
            if (raw == null) throw new IllegalArgumentException("O Android não conseguiu abrir o arquivo ZIP.");
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                ZipEntry entry;
                byte[] buffer = new byte[BUFFER_SIZE];
                while ((entry = zip.getNextEntry()) != null) {
                    checkCancelled(cancelled);
                    if (++copied.entries > MAX_ENTRIES) {
                        throw new IllegalArgumentException("O ZIP contém itens demais; limite seguro: " + MAX_ENTRIES + ".");
                    }
                    String relative = safeRelativePath(entry.getName());
                    if (!seenPaths.add(relative)) {
                        throw new IllegalArgumentException("O ZIP contém um caminho repetido: " + relative);
                    }
                    File target = childFile(stage, relative);
                    if (entry.isDirectory()) {
                        if (!target.isDirectory() && !target.mkdirs()) {
                            throw new IllegalStateException("Não foi possível criar uma pasta de destino.");
                        }
                    } else {
                        File parent = target.getParentFile();
                        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                            throw new IllegalStateException("Não foi possível criar a pasta de um arquivo importado.");
                        }
                        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                            int read;
                            while ((read = zip.read(buffer)) != -1) {
                                checkCancelled(cancelled);
                                copied.bytes += read;
                                if (copied.bytes > MAX_TOTAL_BYTES) {
                                    throw new IllegalArgumentException("O conteúdo descompactado ultrapassa o limite de 2 GiB.");
                                }
                                output.write(buffer, 0, read);
                                if ((copied.bytes & 0x3FFFFL) < buffer.length) {
                                    report(listener, new Progress("Extraindo ZIP", copied.files,
                                            -1, copied.bytes, -1));
                                }
                            }
                        }
                        copied.files++;
                        report(listener, new Progress("Extraindo ZIP", copied.files,
                                -1, copied.bytes, -1));
                    }
                    zip.closeEntry();
                }
            }
            if (copied.files == 0) throw new IllegalArgumentException("O ZIP não contém arquivos para importar.");
            return new long[]{copied.files, copied.bytes};
        });
    }

    private interface StageOperation {
        long[] run(File staging) throws Exception;
    }

    private static ImportSummary withStaging(File filesDir, AtomicBoolean cancelled,
                                              ProgressListener listener, StageOperation operation)
            throws Exception {
        File active = new File(filesDir, "prepared-resources");
        File staging = new File(filesDir, "prepared-resources.importing");
        File backup = new File(filesDir, "prepared-resources.previous");
        recoverInterruptedPromotion(active, backup);
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IllegalStateException("Não foi possível criar a área temporária no armazenamento do app.");
        }

        try {
            checkCancelled(cancelled);
            long[] result = operation.run(staging);
            checkCancelled(cancelled);
            if (result[0] <= 0 || !containsFile(staging)) {
                throw new IllegalArgumentException("A importação não produziu nenhum arquivo válido.");
            }
            String note = validateManifestIfPresent(staging);
            promote(staging, active, backup);
            report(listener, new Progress("Concluído", result[0], result[0], result[1], result[1]));
            return new ImportSummary(active, result[0], result[1], note);
        } catch (Exception error) {
            deleteRecursively(staging);
            throw error;
        }
    }

    private static void scanTree(ContentResolver resolver, Uri treeUri, String documentId,
                                 Set<String> visited, TreeTotals totals,
                                 AtomicBoolean cancelled, ProgressListener listener, int depth) throws Exception {
        checkCancelled(cancelled);
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("A pasta é profunda demais para importar com segurança.");
        if (!visited.add(documentId)) throw new IllegalArgumentException("O provedor de arquivos retornou uma referência repetida.");
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE},
                null, null, null)) {
            if (cursor == null) throw new IllegalStateException("O Android não conseguiu listar uma pasta selecionada.");
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            if (idCol < 0 || nameCol < 0 || mimeCol < 0) {
                throw new IllegalStateException("O provedor de arquivos não fornece os metadados necessários.");
            }
            while (cursor.moveToNext()) {
                checkCancelled(cancelled);
                String childId = cursor.getString(idCol);
                String name = safeName(cursor.getString(nameCol));
                String mime = cursor.getString(mimeCol);
                totals.entries++;
                enforceEntryLimit(totals.entries);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanTree(resolver, treeUri, childId, visited, totals, cancelled, listener, depth + 1);
                } else {
                    totals.files++;
                    if (sizeCol < 0 || cursor.isNull(sizeCol) || cursor.getLong(sizeCol) < 0) {
                        totals.byteSizesKnown = false;
                    } else {
                        totals.bytes += cursor.getLong(sizeCol);
                        if (totals.bytes > MAX_TOTAL_BYTES) {
                            throw new IllegalArgumentException("A pasta ultrapassa o limite de 2 GiB.");
                        }
                    }
                }
                report(listener, new Progress("Analisando pasta", totals.files, -1, totals.bytes, -1));
            }
        }
    }

    private static void copyTree(ContentResolver resolver, Uri treeUri, String documentId,
                                 String relative, File destination, Set<String> visited,
                                 TreeTotals totals, Counter copied, AtomicBoolean cancelled,
                                 ProgressListener listener, int depth) throws Exception {
        checkCancelled(cancelled);
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("A pasta é profunda demais.");
        if (!visited.add(documentId)) throw new IllegalArgumentException("A pasta contém uma referência circular.");
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Não foi possível ler uma pasta selecionada.");
            int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                checkCancelled(cancelled);
                String childId = cursor.getString(idCol);
                String name = safeName(cursor.getString(nameCol));
                String childRelative = relative.isEmpty() ? name : relative + "/" + name;
                File target = childFile(destination, childRelative);
                String mime = cursor.getString(mimeCol);
                copied.entries++;
                enforceEntryLimit(copied.entries);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IllegalStateException("Não foi possível criar uma pasta de destino.");
                    }
                    copyTree(resolver, treeUri, childId, childRelative, destination, visited,
                            totals, copied, cancelled, listener, depth + 1);
                } else {
                    File parent = target.getParentFile();
                    if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                        throw new IllegalStateException("Não foi possível criar a pasta de um arquivo importado.");
                    }
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                    InputStream source = resolver.openInputStream(fileUri);
                    if (source == null) throw new IllegalStateException("Não foi possível abrir: " + childRelative);
                    try (InputStream input = new BufferedInputStream(source);
                         OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            checkCancelled(cancelled);
                            copied.bytes += read;
                            if (copied.bytes > MAX_TOTAL_BYTES) {
                                throw new IllegalArgumentException("A pasta ultrapassa o limite de 2 GiB.");
                            }
                            output.write(buffer, 0, read);
                            if ((copied.bytes & 0x3FFFFL) < buffer.length) {
                                report(listener, progressForTree(copied, totals));
                            }
                        }
                    }
                    copied.files++;
                    report(listener, progressForTree(copied, totals));
                }
            }
        }
    }

    private static Progress progressForTree(Counter copied, TreeTotals totals) {
        return new Progress("Copiando pasta", copied.files,
                totals.files, copied.bytes, totals.byteSizesKnown ? totals.bytes : -1);
    }

    private static String validateManifestIfPresent(File root) throws Exception {
        File manifest = new File(root, "horizon-resources.json");
        if (!manifest.isFile()) {
            return "Nenhum manifesto Horizon na raiz foi encontrado. Os arquivos foram importados, mas a compatibilidade com um backend ainda não foi verificada.";
        }
        if (manifest.length() <= 0 || manifest.length() > MANIFEST_LIMIT) {
            throw new IllegalArgumentException("O manifesto Horizon está vazio ou excede 1 MiB.");
        }
        byte[] bytes;
        try (InputStream input = new FileInputStream(manifest);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MANIFEST_LIMIT) {
                    throw new IllegalArgumentException("O manifesto Horizon excede 1 MiB.");
                }
                output.write(buffer, 0, read);
            }
            bytes = output.toByteArray();
        }
        JSONObject json;
        try {
            json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException("O manifesto Horizon não contém JSON válido.");
        }
        Object schema = json.opt("schema");
        Object dataVersion = json.opt("data_version");
        if (!(schema instanceof Number) || !(dataVersion instanceof Number)) {
            throw new IllegalArgumentException("O manifesto Horizon não informa schema e data_version numéricos.");
        }
        return "Manifesto Horizon detectado: schema=" + ((Number) schema).intValue()
                + ", data_version=" + ((Number) dataVersion).intValue()
                + ". A compatibilidade com o motor será verificada quando um backend estiver instalado.";
    }

    private static String safeRelativePath(String raw) {
        if (raw == null) throw new IllegalArgumentException("O ZIP contém um caminho vazio.");
        String path = raw;
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("O ZIP contém um caminho absoluto ou inválido: " + raw);
        }
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_DEPTH) {
            throw new IllegalArgumentException("O ZIP contém um caminho profundo demais.");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("O ZIP contém tentativa de sair da pasta de destino.");
            }
            safeName(segment);
        }
        return path;
    }

    private static String safeName(String value) {
        if (value == null || value.isEmpty() || ".".equals(value) || "..".equals(value)
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            throw new IllegalArgumentException("A origem contém um nome de arquivo inválido.");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("A origem contém um nome de arquivo inválido.");
            }
        }
        return value;
    }

    private static File childFile(File root, String relative) throws Exception {
        File result = new File(root, relative);
        String rootPath = root.getCanonicalPath();
        String filePath = result.getCanonicalPath();
        if (!filePath.startsWith(rootPath + File.separator)) {
            throw new IllegalArgumentException("Caminho fora da pasta de importação.");
        }
        return result;
    }

    private static void promote(File staging, File active, File backup) throws Exception {
        deleteRecursively(backup);
        boolean hadActive = active.exists();
        if (hadActive && !active.renameTo(backup)) {
            throw new IllegalStateException("Não foi possível guardar a importação anterior com segurança.");
        }
        if (!staging.renameTo(active)) {
            if (hadActive && !backup.renameTo(active)) {
                throw new IllegalStateException(
                        "Não foi possível ativar a nova importação nem restaurar o conjunto anterior. "
                                + "A cópia anterior continua guardada em " + backup.getName() + ".");
            }
            throw new IllegalStateException("Não foi possível ativar a nova importação; o conjunto anterior foi preservado.");
        }
        // Activation has succeeded. Failure to remove the old backup must not be reported
        // as an import failure because the new data is already active.
        try {
            deleteRecursively(backup);
        } catch (Exception ignored) {
            // A later import will clean up the stale backup after recovery checks.
        }
    }

    private static void recoverInterruptedPromotion(File active, File backup) throws Exception {
        if (!active.exists() && backup.exists()) {
            if (!backup.renameTo(active)) {
                throw new IllegalStateException("Existe uma importação anterior interrompida e não foi possível recuperá-la.");
            }
        } else if (active.exists() && backup.exists()) {
            deleteRecursively(backup);
        }
    }

    private static boolean containsFile(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isFile() || (child.isDirectory() && containsFile(child))) return true;
        }
        return false;
    }

    private static void deleteRecursively(File file) throws Exception {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IllegalStateException("Não foi possível limpar a área temporária: " + file.getName());
        }
    }

    private static void enforceEntryLimit(long entries) {
        if (entries > MAX_ENTRIES) {
            throw new IllegalArgumentException("A pasta selecionada contém itens demais; limite seguro: " + MAX_ENTRIES + ".");
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Importação cancelada.");
        }
    }

    private static void report(ProgressListener listener, Progress progress) {
        if (listener != null) listener.onProgress(progress);
    }
}
