package dev.mobilehorizon.app;

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
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * First Android-side Horizon exporter stage: exports datapack JSON collections whose semantics
 * are direct copies of Mojang's JSON (block tags and damage registries/tags). The source Horizon
 * preparer stores these as {"minecraft:key": "original JSON text"} and compares client/server
 * copies before writing. This stage intentionally does not generate any Java-oracle outputs.
 */
final class VanillaDirectResourceExporter {
    private static final int MAX_JSON_FILES_PER_COLLECTION = 20_000;
    private static final long MAX_COLLECTION_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_SINGLE_JSON_BYTES = 8 * 1024 * 1024;

    interface ProgressListener {
        void onProgress(String stage, int completed, int total, String detail);
    }

    static final class Summary {
        final File outputDirectory;
        final int outputFiles;
        final int sourceJsonFiles;
        final long sourceBytes;
        final String state;

        Summary(File outputDirectory, int outputFiles, int sourceJsonFiles,
                long sourceBytes, String state) {
            this.outputDirectory = outputDirectory;
            this.outputFiles = outputFiles;
            this.sourceJsonFiles = sourceJsonFiles;
            this.sourceBytes = sourceBytes;
            this.state = state;
        }
    }

    private VanillaDirectResourceExporter() {}

    static Summary export(File planDir, AtomicBoolean cancelled,
                          ProgressListener listener) throws Exception {
        if (planDir == null) throw new IllegalArgumentException("Diretório de entrada do exportador inválido.");
        File vanilla = new File(planDir, "vanilla");
        File clientNamespace = new File(vanilla, "client/data/minecraft");
        File serverNamespace = new File(vanilla, "server/data/minecraft");
        if (!clientNamespace.isDirectory()) {
            throw new IllegalStateException("Falta a árvore data/minecraft do cliente; não é possível comparar os recursos diretos do exportador.");
        }
        if (!serverNamespace.isDirectory()) {
            throw new IllegalStateException("Falta a árvore data/minecraft do servidor; execute a preparação vanilla antes do exportador.");
        }

        File blockTagsClient = new File(clientNamespace, "tags/block");
        File blockTagsServer = new File(serverNamespace, "tags/block");
        File damageTypesClient = new File(clientNamespace, "damage_type");
        File damageTypesServer = new File(serverNamespace, "damage_type");
        File damageTagsClient = new File(clientNamespace, "tags/damage_type");
        File damageTagsServer = new File(serverNamespace, "tags/damage_type");

        progress(listener, "Exportando tags de blocos", 0, 3, "Comparando client/server");
        Map<String, String> clientBlocks = collectJsonResources(blockTagsClient, cancelled);
        Map<String, String> serverBlocks = collectJsonResources(blockTagsServer, cancelled);
        requireSameNonEmpty("tags de blocos", clientBlocks, serverBlocks);
        checkCancelled(cancelled);

        progress(listener, "Exportando tipos de dano", 1, 3, "Comparando client/server");
        Map<String, String> clientDamageTypes = collectJsonResources(damageTypesClient, cancelled);
        Map<String, String> serverDamageTypes = collectJsonResources(damageTypesServer, cancelled);
        requireSameNonEmpty("tipos de dano", clientDamageTypes, serverDamageTypes);
        checkCancelled(cancelled);

        progress(listener, "Exportando tags de dano", 2, 3, "Comparando client/server");
        Map<String, String> clientDamageTags = collectJsonResources(damageTagsClient, cancelled);
        Map<String, String> serverDamageTags = collectJsonResources(damageTagsServer, cancelled);
        requireSameNonEmpty("tags de dano", clientDamageTags, serverDamageTags);
        checkCancelled(cancelled);

        File active = new File(planDir, "direct-export");
        File staging = new File(planDir, "direct-export.importing");
        File backup = new File(planDir, "direct-export.previous");
        recover(active, backup);
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IllegalStateException("Não foi possível criar a área temporária do exportador direto.");
        }
        try {
            writeResourceMap(new File(staging, "block-tags.json"), clientBlocks);
            writeResourceMap(new File(staging, "damage-types.json"), clientDamageTypes);
            writeResourceMap(new File(staging, "damage-tags.json"), clientDamageTags);

            JSONObject manifest = new JSONObject();
            manifest.put("format", "mobilehorizon-direct-vanilla-exports");
            manifest.put("format_version", 1);
            manifest.put("minecraft_version", HorizonResourceConverter.VERSION);
            manifest.put("state", "VANILLA_DIRECT_EXPORTS_ONLY_NOT_RUNTIME_RESOURCES");
            manifest.put("source_tree", ".local/horizon-input/" + HorizonResourceConverter.VERSION + "/vanilla");
            manifest.put("resource_format", "object mapping namespaced id to original JSON source text");
            manifest.put("client_server_parity", "verified");
            manifest.put("block_tag_count", clientBlocks.size());
            manifest.put("damage_type_count", clientDamageTypes.size());
            manifest.put("damage_tag_count", clientDamageTags.size());
            manifest.put("output_files", 3);
            manifest.put("source_json_files", clientBlocks.size() + clientDamageTypes.size() + clientDamageTags.size());
            manifest.put("source_bytes", byteCount(clientBlocks) + byteCount(clientDamageTypes) + byteCount(clientDamageTags));
            writeJson(new File(staging, "direct-export-manifest.json"), manifest);
            checkCancelled(cancelled);
            promote(staging, active, backup);
            int sourceFiles = clientBlocks.size() + clientDamageTypes.size() + clientDamageTags.size();
            long bytes = byteCount(clientBlocks) + byteCount(clientDamageTypes) + byteCount(clientDamageTags);
            progress(listener, "Exportação vanilla direta concluída", 3, 3,
                    sourceFiles + " JSONs conferidos · 3 mapas gerados");
            return new Summary(active, 3, sourceFiles, bytes,
                    "VANILLA_DIRECT_EXPORTS_ONLY_NOT_RUNTIME_RESOURCES");
        } catch (Exception error) {
            deleteRecursively(staging);
            throw error;
        }
    }

    private static Map<String, String> collectJsonResources(File directory,
                                                             AtomicBoolean cancelled) throws Exception {
        TreeMap<String, String> result = new TreeMap<>();
        if (!directory.isDirectory()) {
            throw new IllegalStateException("Falta a pasta de recursos diretos: " + directory.getPath());
        }
        File root = directory.getCanonicalFile();
        long[] bytes = {0};
        collectRecursive(root, root, result, bytes, cancelled);
        if (result.isEmpty()) {
            throw new IllegalStateException("Nenhum recurso JSON foi encontrado em " + directory.getPath() + ".");
        }
        return result;
    }

    private static void collectRecursive(File root, File current, Map<String, String> result,
                                         long[] bytes, AtomicBoolean cancelled) throws Exception {
        checkCancelled(cancelled);
        File[] children = current.listFiles();
        if (children == null) throw new IllegalStateException("Não foi possível listar " + current.getPath() + ".");
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
        for (File child : children) {
            checkCancelled(cancelled);
            File canonical = child.getCanonicalFile();
            if (!canonical.getPath().equals(root.getPath())
                    && !canonical.getPath().startsWith(root.getPath() + File.separator)) {
                throw new IllegalStateException("Caminho fora da pasta de recursos recusado.");
            }
            if (child.isDirectory()) {
                collectRecursive(root, child, result, bytes, cancelled);
                continue;
            }
            if (!child.isFile() || !child.getName().endsWith(".json")) continue;
            if (result.size() >= MAX_JSON_FILES_PER_COLLECTION) {
                throw new IllegalArgumentException("Uma coleção exportada excede 20.000 arquivos JSON.");
            }
            long length = child.length();
            if (length <= 0 || length > MAX_SINGLE_JSON_BYTES) {
                throw new IllegalArgumentException("Um recurso JSON está vazio ou excede 8 MiB: " + child.getName());
            }
            if (bytes[0] + length > MAX_COLLECTION_BYTES) {
                throw new IllegalArgumentException("A coleção JSON excede o limite de 64 MiB.");
            }
            String relative = root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/');
            if (!HorizonResourceConverter.isSafeAssetPath(relative)) {
                throw new IllegalArgumentException("Nome de recurso inseguro: " + relative);
            }
            String key = "minecraft:" + relative.substring(0, relative.length() - ".json".length());
            String json = readText(child);
            // Validate the payload as JSON while preserving its original source text.
            new org.json.JSONTokener(json).nextValue();
            if (result.put(key, json) != null) {
                throw new IllegalStateException("ID de recurso duplicado: " + key);
            }
            bytes[0] += length;
        }
    }

    private static void requireSameNonEmpty(String label, Map<String, String> client,
                                            Map<String, String> server) {
        if (client.isEmpty() || server.isEmpty()) {
            throw new IllegalStateException("A exportação direta não encontrou " + label + " suficientes no cliente e no servidor.");
        }
        if (!client.equals(server)) {
            java.util.Set<String> all = new java.util.TreeSet<>();
            all.addAll(client.keySet());
            all.addAll(server.keySet());
            java.util.List<String> different = new java.util.ArrayList<>();
            for (String key : all) {
                if (!java.util.Objects.equals(client.get(key), server.get(key))) {
                    different.add(key);
                    if (different.size() == 5) break;
                }
            }
            throw new IllegalStateException("Os recursos de " + label
                    + " divergem entre client.jar e server.jar. Não será ativado um mapa inconsistente. IDs: "
                    + String.join(", ", different));
        }
    }

    private static long byteCount(Map<String, String> map) {
        long total = 0;
        for (String source : map.values()) total += source.getBytes(StandardCharsets.UTF_8).length;
        return total;
    }

    private static void writeResourceMap(File destination, Map<String, String> resources) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : resources.entrySet()) json.put(entry.getKey(), entry.getValue());
        writeJson(destination, json);
    }

    private static void writeJson(File destination, JSONObject json) throws Exception {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Não foi possível criar a pasta dos recursos exportados.");
        }
        File partial = new File(parent, destination.getName() + ".part");
        byte[] bytes = json.toString(2).getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            output.write(bytes);
            output.flush();
        } catch (Exception error) {
            partial.delete();
            throw error;
        }
        if (!partial.renameTo(destination)) {
            partial.delete();
            throw new IllegalStateException("Não foi possível ativar o arquivo exportado " + destination.getName() + ".");
        }
    }

    private static String readText(File file) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_SINGLE_JSON_BYTES) {
                    throw new IllegalArgumentException(file.getName() + " excede o limite de 8 MiB.");
                }
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    private static void recover(File active, File backup) throws Exception {
        if (!active.exists() && backup.exists()) {
            if (!backup.renameTo(active)) throw new IllegalStateException("Não foi possível restaurar o exportador direto anterior.");
        } else if (active.exists() && backup.exists()) {
            deleteRecursively(backup);
        }
    }

    private static void promote(File staging, File active, File backup) throws Exception {
        deleteRecursively(backup);
        boolean hadActive = active.exists();
        if (hadActive && !active.renameTo(backup)) {
            throw new IllegalStateException("Não foi possível preservar o exportador direto anterior.");
        }
        if (!staging.renameTo(active)) {
            if (hadActive && !backup.renameTo(active)) {
                throw new IllegalStateException("Não foi possível ativar nem restaurar o exportador direto.");
            }
            throw new IllegalStateException("Não foi possível ativar a exportação direta; a anterior foi preservada.");
        }
        try { deleteRecursively(backup); } catch (Exception ignored) {}
    }

    private static void deleteRecursively(File file) throws Exception {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete() && file.exists()) {
            throw new IllegalStateException("Não foi possível limpar " + file.getName() + ".");
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if ((cancelled != null && cancelled.get()) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Exportação direta cancelada.");
        }
    }

    private static void progress(ProgressListener listener, String stage,
                                 int completed, int total, String detail) {
        if (listener != null) listener.onProgress(stage, completed, total, detail);
    }
}
