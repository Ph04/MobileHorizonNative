package dev.mobilehorizon.app;

import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Horizon-specific resource checks and the first step of the conversion pipeline.
 *
 * The input planner does not pretend to replace horizon-prepare: the current upstream exporter
 * invokes JDK 25 javac/java and must be ported before vanilla JARs can produce the runtime files
 * that horizon-client consumes.
 */
final class HorizonResourceConverter {
    static final String VERSION = "26.3";
    static final int EXPECTED_SCHEMA = 7;
    static final int EXPECTED_DATA_VERSION = 5023;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_RESOURCE_FILES = 250_000;
    private static final int MAX_RESOURCE_DEPTH = 64;
    private static final int MAX_SOURCE_JAR_ENTRIES = 500_000;
    private static final int MAX_EXTRACTED_SOURCE_FILES = 150_000;
    private static final long MAX_EXTRACTED_SOURCE_BYTES = 1536L * 1024L * 1024L;
    private static final long MAX_MATERIALIZED_ASSET_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAX_BUNDLED_SERVER_JAR_BYTES = 256L * 1024L * 1024L;

    private static final String[] REQUIRED_ROOT_FILES = {
            "facts.json", "block-tags.json", "block-behaviours.json", "gameplay-facts.json",
            "items.json", "registries.json", "commands.json", "command-registries.json",
            "entity-types.json", "entity-models.json", "mob-data.json", "model-states.json",
            "render-definitions.json", "occlusion.nbt", "attributes.json", "landing-definitions.json",
            "cached-collision.nbt", "movement-properties.nbt", "damage-types.json", "damage-tags.json",
            "damage-catalog.json", "player-float-ids.json", "creative-inventory.json"
    };

    private static final String[] REQUIRED_RELATIVE_FILES = {
            "assets/minecraft/lang/en_us.json",
            "assets/minecraft/textures/colormap/grass.png",
            "assets/minecraft/textures/colormap/foliage.png",
            "assets/minecraft/textures/colormap/dry_foliage.png",
            "data/minecraft/timeline/day.json",
            "data/minecraft/dimension_type/overworld.json",
            "biome-parameters/overworld.json",
            "worldgen/noises.json", "worldgen/densities.json", "worldgen/rules.json",
            "worldgen/conditions.json", "worldgen/settings.json", "worldgen/carvers.json",
            "worldgen/biomes.json", "worldgen/world_presets.json", "worldgen/features.json",
            "worldgen/placed_features.json", "worldgen/state_providers.json", "worldgen/biome_tags.json",
            "worldgen/feature_tags.json", "worldgen/block_tags.json", "worldgen/fluid_tags.json",
            "worldgen/processor_lists.json", "worldgen/template_pools.json", "worldgen/structures.json",
            "worldgen/structure_sets.json", "worldgen/biome_parameters/overworld.json",
            "worldgen/block_flags.json", "worldgen/blocks/block-properties.nbt",
            "worldgen/blocks/cached-collision.nbt", "worldgen/blocks/cached-support.nbt",
            "worldgen/blocks/survival-rules.json", "worldgen/blocks/survival-extra-definitions.json",
            "worldgen/blocks/placement-definitions.json", "worldgen/blocks/structure-blocks.json"
    };

    interface ProgressListener {
        void onProgress(String stage, int completed, int total, String detail);
    }

    static final class InputReport {
        final File planFile;
        final int verifiedLibraries;
        final int uniqueAssets;
        final int presentAssetObjects;
        final int missingAssetObjects;
        final String clientSha1;
        final String serverSha1;
        final String assetIndexId;
        final int extractedClientFiles;
        final int extractedServerFiles;
        final long extractedSourceBytes;
        final int hydratedAssetNames;
        final long hydratedAssetBytes;
        final String note;

        InputReport(File planFile, int libraries, int uniqueAssets, int presentAssetObjects,
                    int missingAssetObjects, String clientSha1, String serverSha1,
                    String assetIndexId, int extractedClientFiles, int extractedServerFiles,
                    long extractedSourceBytes, int hydratedAssetNames, long hydratedAssetBytes,
                    String note) {
            this.planFile = planFile;
            this.verifiedLibraries = libraries;
            this.uniqueAssets = uniqueAssets;
            this.presentAssetObjects = presentAssetObjects;
            this.missingAssetObjects = missingAssetObjects;
            this.clientSha1 = clientSha1;
            this.serverSha1 = serverSha1;
            this.assetIndexId = assetIndexId;
            this.extractedClientFiles = extractedClientFiles;
            this.extractedServerFiles = extractedServerFiles;
            this.extractedSourceBytes = extractedSourceBytes;
            this.hydratedAssetNames = hydratedAssetNames;
            this.hydratedAssetBytes = hydratedAssetBytes;
            this.note = note;
        }
    }

    private HorizonResourceConverter() {}

    /**
     * Validates the downloaded vanilla inputs and writes a compact conversion plan. The plan
     * refers to existing app-private files; it does not duplicate hundreds of MB of JAR/assets.
     */
    static InputReport prepareConversionInputs(File appFilesDir, AtomicBoolean cancelled,
                                               ProgressListener listener) throws Exception {
        File minecraftRoot = new File(appFilesDir, "minecraft");
        File versionJsonFile = new File(minecraftRoot, "versions/" + VERSION + "/" + VERSION + ".json");
        File clientJar = new File(minecraftRoot, "versions/" + VERSION + "/" + VERSION + ".jar");
        File serverJar = new File(minecraftRoot, "server.jar");
        if (!versionJsonFile.isFile()) throw new IllegalStateException("Falta o manifesto oficial de Minecraft " + VERSION + ". Baixe primeiro os arquivos base.");
        if (!clientJar.isFile()) throw new IllegalStateException("Falta o JAR do cliente " + VERSION + ".");
        if (!serverJar.isFile()) throw new IllegalStateException("Falta server.jar. O conversor atual exige o arquivo do servidor.");

        report(listener, "Verificando manifesto oficial", 0, 5, VERSION + ".json");
        String versionJsonSha1 = sha1(versionJsonFile);
        if (!OfficialMinecraftInstaller.isAcceptedVersionJsonSha1(versionJsonSha1)) {
            throw new IllegalStateException("O manifesto local não é um dos manifestos conhecidos pelo motor. SHA-1: " + versionJsonSha1);
        }
        JSONObject version = new JSONObject(readText(versionJsonFile, 64 * 1024 * 1024));
        if (!VERSION.equals(version.optString("id", ""))) throw new IllegalStateException("O manifesto local não declara Minecraft " + VERSION + ".");
        JSONObject javaVersion = version.optJSONObject("javaVersion");
        if (javaVersion == null || javaVersion.optInt("majorVersion", -1) != 25) {
            throw new IllegalStateException("Este snapshot do motor está vinculado ao Java 25.");
        }
        if (!OfficialMinecraftInstaller.CLIENT_SHA1.equalsIgnoreCase(
                valueAt(version, "downloads", "client", "sha1"))) {
            throw new IllegalStateException("O manifesto não corresponde ao pin de SHA-1 do cliente.");
        }
        if (!OfficialMinecraftInstaller.SERVER_SHA1.equalsIgnoreCase(
                valueAt(version, "downloads", "server", "sha1"))) {
            throw new IllegalStateException("O manifesto não corresponde ao pin de SHA-1 do servidor.");
        }

        report(listener, "Verificando JARs do jogo", 1, 5, "cliente e servidor");
        String clientSha1 = sha1(clientJar);
        String serverSha1 = sha1(serverJar);
        if (!OfficialMinecraftInstaller.CLIENT_SHA1.equalsIgnoreCase(clientSha1)) {
            throw new IllegalStateException("O JAR do cliente falhou na verificação SHA-1.");
        }
        if (!OfficialMinecraftInstaller.SERVER_SHA1.equalsIgnoreCase(serverSha1)) {
            throw new IllegalStateException("server.jar falhou na verificação SHA-1.");
        }

        JSONObject assetIndexMetadata = version.optJSONObject("assetIndex");
        if (assetIndexMetadata == null) throw new IllegalStateException("Falta assetIndex no manifesto.");
        String assetIndexId = assetIndexMetadata.optString("id", "");
        if (!assetIndexId.matches("[A-Za-z0-9._-]{1,100}") || ".".equals(assetIndexId) || "..".equals(assetIndexId)) {
            throw new IllegalStateException("O ID do asset index não é seguro.");
        }
        File indexFile = new File(minecraftRoot, "assets/indexes/" + assetIndexId + ".json");
        if (!indexFile.isFile()) throw new IllegalStateException("Falta assets/indexes/" + assetIndexId + ".json. Execute o download oficial.");
        String expectedIndexSha1 = assetIndexMetadata.optString("sha1", "");
        String actualIndexSha1 = sha1(indexFile);
        if (!expectedIndexSha1.equalsIgnoreCase(actualIndexSha1)) {
            throw new IllegalStateException("O índice de assets não corresponde ao SHA-1 do manifesto.");
        }

        report(listener, "Verificando bibliotecas", 2, 5, "bibliotecas Android/ARM64");
        JSONArray libraries = version.optJSONArray("libraries");
        if (libraries == null) throw new IllegalStateException("O manifesto não contém bibliotecas.");
        List<String> allNames = new ArrayList<>();
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject item = libraries.optJSONObject(i);
            if (item != null) allNames.add(item.optString("name", ""));
        }
        int verifiedLibraries = 0;
        List<String> libraryIssues = new ArrayList<>();
        for (int i = 0; i < libraries.length(); i++) {
            checkCancelled(cancelled);
            JSONObject library = libraries.optJSONObject(i);
            if (library == null || !OfficialMinecraftInstaller.libraryAllowedForAndroid(library)) continue;
            if (!OfficialMinecraftInstaller.nativeLibraryMatchesForAndroid(
                    library.optString("name", ""), allNames)) continue;
            JSONObject libraryDownloads = library.optJSONObject("downloads");
            JSONObject artifact = libraryDownloads == null ? null : libraryDownloads.optJSONObject("artifact");
            if (artifact == null) continue;
            String relative = artifact.optString("path", "");
            if (!OfficialMinecraftInstaller.isSafeRelativePath(relative)) {
                throw new IllegalStateException("O manifesto contém um caminho de biblioteca inseguro.");
            }
            File file = new File(minecraftRoot, "libraries/" + relative);
            String expected = artifact.optString("sha1", "");
            long expectedSize = artifact.optLong("size", -1);
            if (!file.isFile() || expectedSize >= 0 && file.length() != expectedSize
                    || OfficialMinecraftInstaller.isSha1(expected) && !expected.equalsIgnoreCase(sha1(file))) {
                libraryIssues.add(relative);
            } else {
                verifiedLibraries++;
            }
            report(listener, "Verificando bibliotecas", i + 1, libraries.length(), relative);
        }
        if (!libraryIssues.isEmpty()) {
            throw new IllegalStateException("Há " + libraryIssues.size()
                    + " bibliotecas ausentes ou inválidas. Execute novamente o download oficial para reparar os arquivos.");
        }

        report(listener, "Verificando índice de assets", 3, 5, assetIndexId + ".json");
        JSONObject index = new JSONObject(readText(indexFile, 64 * 1024 * 1024));
        JSONObject objects = index.optJSONObject("objects");
        if (objects == null) throw new IllegalStateException("O índice oficial não contém a tabela objects.");
        Set<String> unique = new HashSet<>();
        List<IndexedAsset> indexedAssets = new ArrayList<>();
        Map<String, File> verifiedAssetObjects = new HashMap<>();
        int present = 0;
        int missing = 0;
        int inspected = 0;
        Iterator<String> names = objects.keys();
        while (names.hasNext()) {
            checkCancelled(cancelled);
            String name = names.next();
            JSONObject asset = objects.optJSONObject(name);
            if (asset == null) continue;
            String hash = asset.optString("hash", "").toLowerCase(Locale.ROOT);
            long size = asset.optLong("size", -1);
            if (!isSafeAssetPath(name) || !hash.matches("[0-9a-f]{40}") || size < 0) {
                throw new IllegalStateException("Entrada inválida no índice de assets: " + name);
            }
            indexedAssets.add(new IndexedAsset(name, hash, size));
            if (unique.add(hash)) {
                File objectFile = new File(minecraftRoot, "assets/objects/" + hash.substring(0, 2) + "/" + hash);
                if (objectFile.isFile() && objectFile.length() == size
                        && hash.equalsIgnoreCase(sha1(objectFile))) {
                    present++;
                    verifiedAssetObjects.put(hash, objectFile);
                } else {
                    missing++;
                }
            }
            inspected++;
            if (inspected == 1 || inspected == objects.length() || inspected % 25 == 0) {
                report(listener, "Verificando integridade dos assets", inspected,
                        objects.length(), "Objeto " + hash);
            }
        }

        File planDir = new File(appFilesDir, ".local/horizon-input/" + VERSION);
        if (!planDir.isDirectory() && !planDir.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar o diretório privado de entrada do conversor.");
        }

        report(listener, "Extraindo recursos vanilla", 4, 6,
                "assets/minecraft do cliente e data/minecraft do servidor");
        ExtractionSummary extraction = extractVanillaSources(clientJar, serverJar, planDir,
                minecraftRoot, indexedAssets, verifiedAssetObjects, cancelled, listener);
        report(listener, "Exportando dados vanilla disponíveis", 5, 6,
                "tags de blocos, tipos de dano e tags de dano");
        VanillaDirectResourceExporter.Summary directExports = VanillaDirectResourceExporter.export(
                planDir, cancelled,
                (stage, completed, total, detail) -> report(listener, stage, completed, total, detail));
        report(listener, "Gravando plano de conversão", 5, 6, "conversion-plan.json");
        JSONObject plan = new JSONObject();
        plan.put("format", "mobilehorizon-conversion-input");
        plan.put("format_version", 1);
        plan.put("minecraft_version", VERSION);
        plan.put("java_major", 25);
        plan.put("official_version_manifest_sha1", versionJsonSha1);
        plan.put("client_jar", "minecraft/versions/" + VERSION + "/" + VERSION + ".jar");
        plan.put("client_sha1", clientSha1);
        plan.put("server_jar", "minecraft/server.jar");
        plan.put("server_sha1", serverSha1);
        plan.put("version_json", "minecraft/versions/" + VERSION + "/" + VERSION + ".json");
        plan.put("libraries_root", "minecraft/libraries");
        plan.put("verified_libraries", verifiedLibraries);
        plan.put("asset_index_id", assetIndexId);
        plan.put("asset_index_path", "minecraft/assets/indexes/" + assetIndexId + ".json");
        plan.put("asset_index_sha1", actualIndexSha1);
        plan.put("unique_asset_objects", unique.size());
        plan.put("present_asset_objects", present);
        plan.put("missing_asset_objects", missing);
        plan.put("asset_objects_integrity", missing == 0 ? "all-present-and-sha1-verified" : "incomplete-or-hash-mismatch");
        plan.put("asset_objects_root", "minecraft/assets/objects");
        plan.put("vanilla_sources_directory", ".local/horizon-input/" + VERSION + "/vanilla");
        plan.put("extracted_client_resource_files", extraction.clientFiles);
        plan.put("extracted_server_resource_files", extraction.serverFiles);
        plan.put("extracted_vanilla_source_bytes", extraction.bytes);
        plan.put("indexed_asset_names", indexedAssets.size());
        plan.put("asset_names_materialized", extraction.hydratedAssetNames);
        plan.put("asset_bytes_materialized", extraction.hydratedAssetBytes);
        plan.put("direct_export_directory", ".local/horizon-input/" + VERSION + "/direct-export");
        plan.put("direct_export_state", directExports.state);
        plan.put("direct_export_files", directExports.outputFiles);
        plan.put("direct_export_source_json_files", directExports.sourceJsonFiles);
        plan.put("direct_export_source_bytes", directExports.sourceBytes);
        plan.put("conversion_state", "VANILLA_DIRECT_EXPORTS_READY_AWAITING_RUNTIME_ORACLES");
        plan.put("conversion_blocker",
                "The direct JSON exporter now produces block-tags.json, damage-types.json and damage-tags.json in .local/horizon-input. The full Horizon exporter still needs Java 25 oracles/data generator for gameplay, render, collision, registries and worldgen. No runtime resources are implied.");
        plan.put("required_output_directory", ".local/horizon-client/" + VERSION);
        writeAtomically(plan.toString(2).getBytes(StandardCharsets.UTF_8),
                new File(planDir, "conversion-plan.json"));
        report(listener, "Entradas e recursos vanilla preparados", 6, 6,
                "Cliente: " + extraction.clientFiles + " arquivos · servidor: " + extraction.serverFiles
                        + " arquivos · assets baixados: " + present + "/" + unique.size());

        String note = "Entradas oficiais verificadas; " + extraction.clientFiles
                + " arquivos de assets/data do JAR do cliente e " + extraction.serverFiles
                + " arquivos de data/minecraft do servidor foram extraídos. "
                + extraction.hydratedAssetNames + " nomes de assets do índice foram materializados na árvore de entrada ("
                + formatBytes(extraction.hydratedAssetBytes) + "), sem alterar os JARs oficiais. "
                + "Bibliotecas verificadas: " + verifiedLibraries + ". Objetos de asset íntegros disponíveis: "
                + present + "/" + unique.size() + "; ausentes ou corrompidos: " + missing + ". "
                + "O exportador Android direto gerou 3 mapas JSON (" + directExports.sourceJsonFiles
                + " recursos-fonte comparados entre cliente/servidor) em .local/horizon-input/26.3/direct-export. "
                + "O exportador Horizon Java 25 ainda não foi portado por completo; recursos de gameplay/worldgen, render, colisão e registries ainda faltam.";
        return new InputReport(new File(planDir, "conversion-plan.json"), verifiedLibraries,
                unique.size(), present, missing, clientSha1, serverSha1, assetIndexId,
                extraction.clientFiles, extraction.serverFiles, extraction.bytes,
                extraction.hydratedAssetNames, extraction.hydratedAssetBytes, note);
    }

    private static final class IndexedAsset {
        final String name;
        final String hash;
        final long size;
        IndexedAsset(String name, String hash, long size) {
            this.name = name;
            this.hash = hash;
            this.size = size;
        }
    }

    private static final class AssetHydration {
        final int files;
        final long bytes;
        AssetHydration(int files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }

    private static final class ExtractionSummary {
        final int clientFiles;
        final int serverFiles;
        final long bytes;
        final int hydratedAssetNames;
        final long hydratedAssetBytes;

        ExtractionSummary(int clientFiles, int serverFiles, long bytes,
                          int hydratedAssetNames, long hydratedAssetBytes) {
            this.clientFiles = clientFiles;
            this.serverFiles = serverFiles;
            this.bytes = bytes;
            this.hydratedAssetNames = hydratedAssetNames;
            this.hydratedAssetBytes = hydratedAssetBytes;
        }
    }

    /**
     * Stages only the vanilla resource trees consumed by the Horizon exporter. This does not
     * claim that oracle-generated gameplay/worldgen registries have been produced.
     */
    private static ExtractionSummary extractVanillaSources(File clientJar, File serverJar,
                                                            File planDir, File minecraftRoot,
                                                            List<IndexedAsset> indexedAssets,
                                                            Map<String, File> verifiedAssetObjects,
                                                            AtomicBoolean cancelled,
                                                            ProgressListener listener) throws Exception {
        File active = new File(planDir, "vanilla");
        File staging = new File(planDir, "vanilla.importing");
        File backup = new File(planDir, "vanilla.previous");
        recoverFolderPromotion(active, backup);
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IllegalStateException("Não foi possível criar a pasta temporária dos recursos vanilla.");
        }
        try {
            File clientAssetsDestination = new File(staging, "client/assets");
            File clientDataDestination = new File(staging, "client/data");
            File serverDataDestination = new File(staging, "server/data/minecraft");
            ExtractionPart clientAssets = extractZipPrefix(clientJar, "assets/",
                    clientAssetsDestination, "Extraindo assets do JAR cliente", cancelled, listener);
            ExtractionPart clientData = extractZipPrefix(clientJar, "data/",
                    clientDataDestination, "Extraindo dados opcionais do JAR cliente", cancelled, listener);
            ServerDataExtraction serverDataSource = extractServerDataArchive(serverJar, staging,
                    serverDataDestination, cancelled, listener);
            ExtractionPart serverData = serverDataSource.extraction;

            if (serverData.files <= 0 || !containsRequiredServerData(serverDataDestination)) {
                throw new IllegalStateException("Os recursos data/minecraft não foram encontrados no server.jar, nem na raiz nem no JAR interno do servidor. Verifique se o download oficial está íntegro.");
            }

            AssetHydration hydration = materializeAssetObjects(clientAssetsDestination, indexedAssets,
                    verifiedAssetObjects, cancelled, listener);
            File languageFile = new File(clientAssetsDestination, "minecraft/lang/en_us.json");
            if (!languageFile.isFile() || languageFile.length() == 0) {
                JSONObject langEntry = null;
                for (IndexedAsset asset : indexedAssets) {
                    if ("minecraft/lang/en_us.json".equals(asset.name)) {
                        langEntry = new JSONObject()
                                .put("hash", asset.hash)
                                .put("size", asset.size);
                        break;
                    }
                }
                if (langEntry == null) {
                    throw new IllegalStateException("O índice oficial não lista minecraft/lang/en_us.json; não é possível completar a entrada de recursos do cliente.");
                }
                throw new IllegalStateException("Falta o asset obrigatório minecraft/lang/en_us.json. Baixe também “Base + todos os assets” e execute a preparação novamente. O download base não garante todos os objetos de assets.");
            }

            long jarBytes = clientAssets.bytes + clientData.bytes + serverData.bytes;
            long allBytes = jarBytes + hydration.bytes;
            if (allBytes > MAX_EXTRACTED_SOURCE_BYTES + MAX_MATERIALIZED_ASSET_BYTES) {
                throw new IllegalArgumentException("O conjunto vanilla extraído/materializado excede o limite combinado de segurança.");
            }

            JSONObject manifest = new JSONObject();
            manifest.put("format", "mobilehorizon-vanilla-source-tree");
            manifest.put("format_version", 3);
            manifest.put("minecraft_version", VERSION);
            manifest.put("client_jar_sha1", sha1(clientJar));
            manifest.put("server_jar_sha1", sha1(serverJar));
            manifest.put("client_assets_prefix", "assets/");
            manifest.put("client_data_prefix", "data/");
            manifest.put("client_assets_source", clientAssets.files > 0 ? "client.jar" : "asset-index");
            manifest.put("client_data_optional", clientData.files == 0);
            manifest.put("server_data_prefix", "data/minecraft/");
            manifest.put("server_data_source_entry", serverDataSource.sourceEntry);
            manifest.put("client_assets_files", clientAssets.files);
            manifest.put("client_data_files", clientData.files);
            manifest.put("server_data_files", serverData.files);
            manifest.put("client_files", clientAssets.files + clientData.files);
            manifest.put("server_files", serverData.files);
            manifest.put("asset_names_materialized", hydration.files);
            manifest.put("asset_bytes_materialized", hydration.bytes);
            manifest.put("jar_resource_bytes_extracted", jarBytes);
            manifest.put("source_bytes_written", allBytes);
            manifest.put("state", "VANILLA_INPUTS_ONLY_NOT_HORIZON_RUNTIME_RESOURCES");
            writeAtomically(manifest.toString(2).getBytes(StandardCharsets.UTF_8),
                    new File(staging, "source-input-manifest.json"));
            promoteFolder(staging, active, backup);
            return new ExtractionSummary(clientAssets.files + clientData.files, serverData.files,
                    allBytes, hydration.files, hydration.bytes);
        } catch (Exception error) {
            deleteRecursively(staging);
            throw error;
        }
    }

    static final class ServerDataExtraction {
        final ExtractionPart extraction;
        final String sourceEntry;

        ServerDataExtraction(ExtractionPart extraction, String sourceEntry) {
            this.extraction = extraction;
            this.sourceEntry = sourceEntry;
        }
    }

    static final class ExtractionPart {
        final int files;
        final long bytes;

        ExtractionPart(int files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }

    /**
     * Mojang server.jar files may be bundler archives. Support both a flat archive with
     * data/minecraft at its root and the inner server JAR under META-INF/versions/<version>/.
     * Exposed at package scope so the archive layouts can be exercised with synthetic JAR tests.
     */
    static ServerDataExtraction extractServerDataArchive(File serverJar, File staging,
                                                         File destination,
                                                         AtomicBoolean cancelled,
                                                         ProgressListener listener) throws Exception {
        deleteRecursively(destination);
        ensureDirectory(destination);
        ExtractionPart direct = extractZipPrefix(serverJar, "data/minecraft/",
                destination, "Procurando dados na raiz do server.jar", cancelled, listener);
        if (direct.files > 0 && containsRequiredServerData(destination)) {
            return new ServerDataExtraction(direct, "server.jar!/data/minecraft");
        }

        deleteRecursively(destination);
        ensureDirectory(destination);
        BundledServerJar nested = extractBundledServerJar(serverJar, staging, cancelled, listener);
        if (nested == null) {
            throw new IllegalStateException(
                    "O server.jar não contém data/minecraft na raiz e não foi encontrado um JAR interno em META-INF/versions/<versão>/server-<versão>.jar. O download foi verificado, mas o formato do arquivo é diferente do esperado.");
        }
        try {
            ExtractionPart inner = extractZipPrefix(nested.file, "data/minecraft/",
                    destination, "Extraindo data/minecraft do JAR interno " + nested.entryName,
                    cancelled, listener);
            if (inner.files <= 0 || !containsRequiredServerData(destination)) {
                deleteRecursively(destination);
                throw new IllegalStateException(
                        "O JAR interno " + nested.entryName + " foi encontrado, mas não contém data/minecraft/dimension_type/overworld.json. Não será gerada uma conversão parcial.");
            }
            return new ServerDataExtraction(inner, "server.jar!/" + nested.entryName + "!/data/minecraft");
        } finally {
            deleteRecursively(nested.file);
        }
    }

    private static boolean containsRequiredServerData(File destination) {
        File required = new File(destination, "dimension_type/overworld.json");
        return required.isFile() && required.length() > 0;
    }

    private static final class BundledServerJar {
        final File file;
        final String entryName;

        BundledServerJar(File file, String entryName) {
            this.file = file;
            this.entryName = entryName;
        }
    }

    private static BundledServerJar extractBundledServerJar(File outerServerJar, File staging,
                                                            AtomicBoolean cancelled,
                                                            ProgressListener listener) throws Exception {
        File nestedFile = new File(staging, "server-inner.tmp.jar");
        if (nestedFile.exists() && !nestedFile.delete()) {
            throw new IllegalStateException("Não foi possível limpar o JAR interno temporário anterior.");
        }
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(outerServerJar)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(cancelled);
                String name = entry.getName();
                if (!isBundledServerJarEntry(name) || entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (entry.getSize() > MAX_BUNDLED_SERVER_JAR_BYTES) {
                    throw new IllegalArgumentException("O JAR interno do servidor excede o limite de 256 MiB.");
                }
                long copied = 0;
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(nestedFile))) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        copied += read;
                        if (copied > MAX_BUNDLED_SERVER_JAR_BYTES) {
                            throw new IllegalArgumentException("O JAR interno do servidor excede o limite de 256 MiB.");
                        }
                        output.write(buffer, 0, read);
                    }
                    output.flush();
                } catch (Exception error) {
                    nestedFile.delete();
                    throw error;
                }
                zip.closeEntry();
                if (copied == 0) {
                    nestedFile.delete();
                    continue;
                }
                report(listener, "JAR interno do servidor encontrado", 1, 1,
                        name + " · " + formatBytes(copied));
                return new BundledServerJar(nestedFile, name);
            }
        } catch (Exception error) {
            nestedFile.delete();
            throw error;
        }
        nestedFile.delete();
        return null;
    }

    private static boolean isBundledServerJarEntry(String name) {
        if (name == null || !name.startsWith("META-INF/versions/") || !name.endsWith(".jar")) {
            return false;
        }
        String fileName = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return fileName.equals("server.jar") || fileName.startsWith("server-");
    }

    /**
     * Rehydrates files from the launcher's content-addressed asset store into their official
     * resource paths (e.g. minecraft/textures/...). Missing objects are reported by the plan,
     * not fabricated; every copied object is hashed again while being written.
     */
    private static AssetHydration materializeAssetObjects(File clientAssetsRoot,
                                                           List<IndexedAsset> indexedAssets,
                                                           Map<String, File> verifiedAssetObjects,
                                                           AtomicBoolean cancelled,
                                                           ProgressListener listener) throws Exception {
        File canonicalRoot = clientAssetsRoot.getCanonicalFile();
        int copied = 0;
        long copiedBytes = 0;
        int scanned = 0;
        for (IndexedAsset asset : indexedAssets) {
            checkCancelled(cancelled);
            scanned++;
            File source = verifiedAssetObjects.get(asset.hash);
            if (source == null) continue;
            if (copiedBytes + asset.size > MAX_MATERIALIZED_ASSET_BYTES) {
                throw new IllegalArgumentException("Os asset objects materializados excedem o limite de 2 GiB.");
            }
            if (!isSafeAssetPath(asset.name)) {
                throw new IllegalArgumentException("Nome de asset inseguro: " + asset.name);
            }
            File target = new File(canonicalRoot, asset.name).getCanonicalFile();
            if (!target.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                throw new IllegalArgumentException("Nome de asset fora da árvore de destino.");
            }
            copyAssetVerified(source, target, asset.hash, asset.size, cancelled);
            copied++;
            copiedBytes += asset.size;
            if (copied == 1 || copied % 25 == 0 || scanned == indexedAssets.size()) {
                report(listener, "Materializando asset objects", scanned, indexedAssets.size(),
                        asset.name + " · materializados: " + copied + " · " + formatBytes(copiedBytes));
            }
        }
        report(listener, "Asset objects materializados", Math.max(1, scanned), Math.max(1, indexedAssets.size()),
                copied + " nomes · " + formatBytes(copiedBytes));
        return new AssetHydration(copied, copiedBytes);
    }

    private static void copyAssetVerified(File source, File destination, String expectedHash,
                                          long expectedSize, AtomicBoolean cancelled) throws Exception {
        if (source.length() != expectedSize || !expectedHash.equalsIgnoreCase(sha1(source))) {
            throw new IllegalStateException("Um asset object mudou depois da verificação: " + expectedHash);
        }
        File parent = destination.getParentFile();
        ensureDirectory(parent);
        File partial = new File(parent, destination.getName() + ".asset-part");
        if (partial.exists() && !partial.delete()) {
            throw new IllegalStateException("Não foi possível limpar a cópia parcial de " + destination.getName() + ".");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        long bytes = 0;
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                bytes += read;
                if (bytes > expectedSize) {
                    throw new IllegalStateException("O asset object excedeu o tamanho do índice.");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (Exception error) {
            partial.delete();
            throw error;
        }
        StringBuilder hash = new StringBuilder(40);
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        if (bytes != expectedSize || !expectedHash.equalsIgnoreCase(hash.toString())) {
            partial.delete();
            throw new IllegalStateException("Falha de integridade ao materializar o asset object " + expectedHash + ".");
        }
        if (destination.exists() && !destination.delete()) {
            partial.delete();
            throw new IllegalStateException("Não foi possível substituir o asset " + destination.getName() + ".");
        }
        if (!partial.renameTo(destination)) {
            partial.delete();
            throw new IllegalStateException("Não foi possível ativar o asset " + destination.getName() + ".");
        }
    }

    static boolean isSafeAssetPath(String path) {
        return path != null && path.indexOf(':') < 0
                && OfficialMinecraftInstaller.isSafeRelativePath(path);
    }

    private static ExtractionPart extractZipPrefix(File archiveFile, String requiredPrefix,
                                                    File destination, String stage,
                                                    AtomicBoolean cancelled,
                                                    ProgressListener listener) throws Exception {
        ensureDirectory(destination);
        File root = destination.getCanonicalFile();
        Set<String> seenFiles = new HashSet<>();
        int scannedEntries = 0;
        int extractedFiles = 0;
        long extractedBytes = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archiveFile)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(cancelled);
                scannedEntries++;
                if (scannedEntries > MAX_SOURCE_JAR_ENTRIES) {
                    throw new IllegalArgumentException(archiveFile.getName() + " contém mais de "
                            + MAX_SOURCE_JAR_ENTRIES + " entradas ZIP.");
                }
                String name = entry.getName();
                if (name == null || !name.startsWith(requiredPrefix)) {
                    zip.closeEntry();
                    continue;
                }
                String relative = name.substring(requiredPrefix.length());
                if (relative.isEmpty()) {
                    zip.closeEntry();
                    continue;
                }
                if (entry.isDirectory() && relative.endsWith("/")) {
                    relative = relative.substring(0, relative.length() - 1);
                }
                if (!OfficialMinecraftInstaller.isSafeRelativePath(relative)) {
                    throw new IllegalArgumentException("O JAR contém caminho inseguro em " + requiredPrefix + ".");
                }
                File target = new File(root, relative).getCanonicalFile();
                if (!target.getPath().startsWith(root.getPath() + File.separator)) {
                    throw new IllegalArgumentException("Uma entrada do JAR escapou da área temporária.");
                }
                if (entry.isDirectory()) {
                    ensureDirectory(target);
                } else {
                    if (!seenFiles.add(relative)) {
                        throw new IllegalArgumentException("O JAR contém arquivo duplicado: " + relative);
                    }
                    extractedFiles++;
                    if (extractedFiles > MAX_EXTRACTED_SOURCE_FILES) {
                        throw new IllegalArgumentException("A extração excedeu " + MAX_EXTRACTED_SOURCE_FILES + " arquivos.");
                    }
                    File parent = target.getParentFile();
                    ensureDirectory(parent);
                    try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            checkCancelled(cancelled);
                            extractedBytes += read;
                            if (extractedBytes > MAX_EXTRACTED_SOURCE_BYTES) {
                                throw new IllegalArgumentException("Os recursos vanilla descompactados ultrapassam o limite de 1,5 GiB.");
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                    if (extractedFiles == 1 || extractedFiles % 25 == 0) {
                        report(listener, stage, extractedFiles, -1,
                                relative + " · " + formatBytes(extractedBytes));
                    }
                }
                zip.closeEntry();
            }
        }
        report(listener, stage, extractedFiles, extractedFiles,
                extractedFiles + " arquivos · " + formatBytes(extractedBytes));
        return new ExtractionPart(extractedFiles, extractedBytes);
    }

    private static void recoverFolderPromotion(File active, File backup) throws Exception {
        if (!active.exists() && backup.exists()) {
            if (!backup.renameTo(active)) {
                throw new IllegalStateException("Não foi possível restaurar a extração vanilla anterior.");
            }
        } else if (active.exists() && backup.exists()) {
            deleteRecursively(backup);
        }
    }

    private static void promoteFolder(File staging, File active, File backup) throws Exception {
        deleteRecursively(backup);
        boolean hadActive = active.exists();
        if (hadActive && !active.renameTo(backup)) {
            throw new IllegalStateException("Não foi possível preservar a extração vanilla anterior.");
        }
        if (!staging.renameTo(active)) {
            if (hadActive && !backup.renameTo(active)) {
                throw new IllegalStateException("Não foi possível ativar a extração vanilla nem restaurar a anterior.");
            }
            throw new IllegalStateException("Não foi possível ativar a nova extração vanilla; a anterior foi preservada.");
        }
        try {
            deleteRecursively(backup);
        } catch (Exception ignored) {
            // The current extraction is already active; clean stale backup on the next run.
        }
    }

    private static void ensureDirectory(File directory) throws Exception {
        if (directory == null) throw new IllegalArgumentException("Diretório de extração inválido.");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar a pasta " + directory.getName() + ".");
        }
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

    /** Strictly checks output the actual Horizon engine can consume, not a generic copied folder. */
    static String validatePreparedResources(File root) throws Exception {
        if (root == null || !root.isDirectory()) throw new IllegalArgumentException("Pasta de recursos Horizon não encontrada.");
        File manifestFile = new File(root, "horizon-resources.json");
        if (!manifestFile.isFile() || manifestFile.length() <= 0 || manifestFile.length() > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("Falta horizon-resources.json válido na raiz da saída preparada.");
        }
        JSONObject manifest = new JSONObject(readText(manifestFile, MAX_MANIFEST_BYTES));
        int schema = manifest.optInt("schema", -1);
        int dataVersion = manifest.optInt("data_version", -1);
        if (schema != EXPECTED_SCHEMA) throw new IllegalArgumentException("Schema Horizon incompatível: esperado " + EXPECTED_SCHEMA + ", recebido " + schema + ".");
        if (dataVersion != EXPECTED_DATA_VERSION) {
            throw new IllegalArgumentException("Data version incompatível: esperado " + EXPECTED_DATA_VERSION + ", recebido " + dataVersion + ".");
        }
        int expectedFiles = manifest.optInt("resource_files", -1);
        if (expectedFiles <= 0) throw new IllegalArgumentException("O manifesto não informa resource_files válido.");

        List<String> missing = new ArrayList<>();
        for (String path : REQUIRED_ROOT_FILES) requireFile(root, path, missing);
        for (String path : REQUIRED_RELATIVE_FILES) requireFile(root, path, missing);
        if (!new File(root, "assets/minecraft").isDirectory()) missing.add("diretório assets/minecraft");
        if (!new File(root, "data/minecraft").isDirectory()) missing.add("diretório data/minecraft");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("A árvore preparada está incompleta; faltam " + missing.size()
                    + " entradas obrigatórias. Primeiras ausências: " + joinFirst(missing, 8));
        }

        // This receipt is stored beside the app-private tree, never inside imported content.
        // Its root path and manifest digest are rechecked; a promoted tree gets a new receipt.
        long cachedCount = readValidationReceipt(root, manifestFile, schema, dataVersion, expectedFiles);
        if (cachedCount >= expectedFiles) {
            return "Recursos Horizon validados: schema=" + schema + ", data_version=" + dataVersion
                    + ", assets/data=" + cachedCount + "/" + expectedFiles + " (cache de validação).";
        }

        long actual = countFiles(new File(root, "assets"), 0, new long[]{0})
                + countFiles(new File(root, "data"), 0, new long[]{0});
        if (actual < expectedFiles) {
            throw new IllegalArgumentException("Assets/data incompletos: o manifesto exige pelo menos "
                    + expectedFiles + " arquivos, mas foram encontrados " + actual + ".");
        }
        writeValidationReceipt(root, manifestFile, schema, dataVersion, expectedFiles, actual);
        return "Recursos Horizon validados: schema=" + schema + ", data_version=" + dataVersion
                + ", assets/data=" + actual + "/" + expectedFiles + ".";
    }

    private static File validationReceiptFile(File root) {
        File parent = root == null ? null : root.getParentFile();
        return parent == null ? null
                : new File(parent, "." + root.getName() + ".mobilehorizon-validation.json");
    }

    private static long readValidationReceipt(File root, File manifestFile, int schema,
                                              int dataVersion, int expectedFiles) {
        try {
            File receiptFile = validationReceiptFile(root);
            if (receiptFile == null || !receiptFile.isFile()
                    || receiptFile.length() <= 0 || receiptFile.length() > 16 * 1024) return -1;
            JSONObject receipt = new JSONObject(readText(receiptFile, 16 * 1024));
            if (receipt.optInt("receipt_version", -1) != 1
                    || !root.getCanonicalPath().equals(receipt.optString("root_path", ""))
                    || receipt.optInt("schema", -1) != schema
                    || receipt.optInt("data_version", -1) != dataVersion
                    || receipt.optInt("expected_files", -1) != expectedFiles
                    || receipt.optLong("manifest_length", -1) != manifestFile.length()
                    || receipt.optLong("manifest_modified", -1) != manifestFile.lastModified()
                    || !sha256(manifestFile).equalsIgnoreCase(receipt.optString("manifest_sha256", ""))) {
                return -1;
            }
            long actual = receipt.optLong("actual_files", -1);
            return actual >= expectedFiles && actual <= MAX_RESOURCE_FILES ? actual : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static void writeValidationReceipt(File root, File manifestFile, int schema,
                                               int dataVersion, int expectedFiles,
                                               long actualFiles) {
        try {
            JSONObject receipt = new JSONObject();
            receipt.put("receipt_version", 1);
            receipt.put("root_path", root.getCanonicalPath());
            receipt.put("schema", schema);
            receipt.put("data_version", dataVersion);
            receipt.put("expected_files", expectedFiles);
            receipt.put("actual_files", actualFiles);
            receipt.put("manifest_length", manifestFile.length());
            receipt.put("manifest_modified", manifestFile.lastModified());
            receipt.put("manifest_sha256", sha256(manifestFile));
            File destination = validationReceiptFile(root);
            if (destination != null) writeAtomically(receipt.toString().getBytes(StandardCharsets.UTF_8), destination);
        } catch (Exception ignored) {
            // Validation remains valid if this optional performance receipt cannot be stored.
        }
    }

    /** Carries a receipt across an atomic promotion without counting every resource a second time. */
    static void promoteValidationReceipt(File sourceRoot, File destinationRoot) {
        File sourceReceipt = validationReceiptFile(sourceRoot);
        File destinationReceipt = validationReceiptFile(destinationRoot);
        if (destinationReceipt == null) return;
        try {
            if (sourceReceipt == null || !sourceReceipt.isFile()) {
                if (destinationReceipt.exists()) destinationReceipt.delete();
                return;
            }
            JSONObject receipt = new JSONObject(readText(sourceReceipt, 16 * 1024));
            if (!sourceRoot.getCanonicalPath().equals(receipt.optString("root_path", ""))) {
                if (destinationReceipt.exists()) destinationReceipt.delete();
                return;
            }
            receipt.put("root_path", destinationRoot.getCanonicalPath());
            writeAtomically(receipt.toString().getBytes(StandardCharsets.UTF_8), destinationReceipt);
            sourceReceipt.delete();
        } catch (Exception ignored) {
            try { if (destinationReceipt.exists()) destinationReceipt.delete(); } catch (Exception ignoredAgain) {}
        }
    }

    private static void requireFile(File root, String path, List<String> missing) {
        File file = new File(root, path);
        if (!file.isFile() || file.length() <= 0) missing.add(path);
    }

    private static long countFiles(File directory, int depth, long[] count) throws Exception {
        if (!directory.exists()) return 0;
        if (!directory.isDirectory()) throw new IllegalArgumentException("Esperava um diretório em " + directory.getName() + ".");
        if (depth > MAX_RESOURCE_DEPTH) throw new IllegalArgumentException("A árvore de recursos tem mais de " + MAX_RESOURCE_DEPTH + " níveis.");
        File[] children = directory.listFiles();
        if (children == null) throw new IllegalStateException("Não foi possível listar " + directory.getName() + ".");
        long local = 0;
        for (File child : children) {
            if (child.isDirectory()) local += countFiles(child, depth + 1, count);
            else if (child.isFile()) local++;
            count[0] += child.isFile() ? 1 : 0;
            if (count[0] > MAX_RESOURCE_FILES) {
                throw new IllegalArgumentException("A árvore de recursos excede o limite de " + MAX_RESOURCE_FILES + " arquivos.");
            }
        }
        return local;
    }

    private static String valueAt(JSONObject root, String parent, String child, String field) {
        JSONObject object = root.optJSONObject(parent);
        object = object == null ? null : object.optJSONObject(child);
        return object == null ? "" : object.optString(field, "");
    }

    private static String readText(File file, int maxBytes) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maxBytes) throw new IllegalStateException(file.getName() + " excede o limite de tamanho.");
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder text = new StringBuilder(64);
        for (byte b : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return text.toString();
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder text = new StringBuilder(40);
        for (byte b : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return text.toString();
    }

    private static void writeAtomically(byte[] bytes, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Não foi possível criar a pasta do plano de conversão.");
        }
        File partial = new File(parent, destination.getName() + ".part");
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            output.write(bytes);
            output.flush();
        }
        if (destination.exists() && !destination.delete()) throw new IllegalStateException("Não foi possível substituir o plano anterior.");
        if (!partial.renameTo(destination)) throw new IllegalStateException("Não foi possível ativar o plano de conversão.");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if ((cancelled != null && cancelled.get()) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Preparação para conversão cancelada.");
        }
    }

    private static void report(ProgressListener listener, String stage, int complete, int total, String detail) {
        if (listener != null) listener.onProgress(stage, complete, total, detail);
    }

    private static String joinFirst(List<String> values, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(values.size(), count); i++) {
            if (i > 0) result.append(", ");
            result.append(values.get(i));
        }
        if (values.size() > count) result.append(", …");
        return result.toString();
    }
}
