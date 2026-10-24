package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Manages only resource formats supported by the bundled Horizon runtime. */
public final class ResourceManagerActivity extends Activity {
    private static final int REQUEST_IMPORT_FILES = 701;
    private static final int REQUEST_IMPORT_SHADER_FOLDER = 702;
    private static final int REQUEST_IMPORT_FABRIC_JAR = 703;
    private static final int REQUEST_INSTALL_DATA_PACK = 704;
    private static final long MAX_IMPORT_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_CLASS_AUDIT_BYTES = 16 * 1024 * 1024;
    private static final long MAX_TOTAL_CLASS_AUDIT_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_NESTED_JARS = 32;
    private static final long MAX_NESTED_JAR_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_NESTED_JAR_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_NESTED_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_SUPPORTED_CLASS_MAJOR = 69; // Java 25
    private static final int MAX_FOLDER_ENTRIES = 20_000;
    private static final int MAX_FOLDER_DEPTH = 64;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*id\\s*=\\s*\"([A-Za-z0-9_.-]+)\"\\s*(?:#.*)?$");
    private static final Pattern FABRIC_API_REFERENCE = Pattern.compile("net/fabricmc/fabric/api/[A-Za-z0-9_/$]+");

    private enum Kind { TEXTURE, MOD, SHADER, FABRIC_JAR, DATA_PACK }
    private enum ArchiveKind { TEXTURE, JAVA_SHADER, HORIZON_SHADER, UNKNOWN }

    private static final class ResourceItem {
        final Kind kind;
        final String name;
        final File path;
        final String id;
        final boolean active;
        final String detail;
        ResourceItem(Kind kind, String name, File path, String id, boolean active, String detail) {
            this.kind = kind; this.name = name; this.path = path;
            this.id = id; this.active = active; this.detail = detail;
        }
    }

    private static final class FabricJarInfo {
        final String id, name, version, fabricApiConstraint;
        final int classes, resources, assets, data, unmappedApiReferences, maxClassMajor, nestedJarCount;
        final boolean hasMixins, hasAccessWidener, hasEntrypoints;
        final List<String> fabricApiModules, auditWarnings;
        FabricJarInfo(String id, String name, String version, int classes, int resources,
                      int assets, int data, boolean hasMixins, boolean hasAccessWidener,
                      boolean hasEntrypoints, List<String> fabricApiModules,
                      int unmappedApiReferences, int maxClassMajor, int nestedJarCount,
                      String fabricApiConstraint, List<String> auditWarnings) {
            this.id = id; this.name = name; this.version = version;
            this.classes = classes; this.resources = resources; this.assets = assets; this.data = data;
            this.hasMixins = hasMixins; this.hasAccessWidener = hasAccessWidener;
            this.hasEntrypoints = hasEntrypoints;
            this.fabricApiModules = new ArrayList<>(fabricApiModules);
            this.unmappedApiReferences = unmappedApiReferences;
            this.maxClassMajor = maxClassMajor;
            this.nestedJarCount = nestedJarCount;
            this.fabricApiConstraint = fabricApiConstraint == null ? "" : fabricApiConstraint;
            this.auditWarnings = new ArrayList<>(auditWarnings);
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private TextView status;
    private ProgressBar progress;
    private Button importFilesButton, importFolderButton, importFabricJarButton;
    private CheckBox profileToggle;
    private File pendingDataPackInstall;
    private boolean busy;
    private Map<String, String> fabricApiCatalogCache;

    private boolean fabricBridgeEnabled() {
        return getSharedPreferences("horizon_fabric_bridge", MODE_PRIVATE).getBoolean("enabled", false);
    }
    private File home() { return new File(getFilesDir(), "horizon"); }
    private File textureDir() { return new File(home(), "resourcepacks"); }
    private File vanillaModDir() { return new File(home(), "horizon-mods"); }
    private File bridgeModDir() { return new File(home(), "horizon-fabric-bridge-mods"); }
    private File modDir() { return fabricBridgeEnabled() ? bridgeModDir() : vanillaModDir(); }
    private File fabricInboxDir() { return new File(home(), "horizon-fabric-bridge-inbox"); }
    private File convertedDataPackDir() { return new File(home(), "horizon-fabric-bridge-datapacks"); }
    private File shaderDir() { return new File(home(), "shaderpacks"); }
    private File optionsFile() { return new File(home(), "options.txt"); }
    private File modListFile() { return new File(home(), fabricBridgeEnabled()
            ? "horizon-fabric-bridge-mods.txt" : "horizon-mods.txt"); }
    private File graphicsFile() { return new File(home(), "horizon-graphics.txt"); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(244, 246, 251));
        getWindow().setNavigationBarColor(Color.rgb(244, 246, 251));
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        buildUi();
        refreshResources();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xfff4f6fb);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(24));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("MOBILEHORIZON  /  RECURSOS", 12, 0xff5363bd, true), params(-1, -2));
        TextView title = text("Gerenciador de recursos", 25, 0xff17213b, true);
        LinearLayout.LayoutParams titleParams = params(-1, -2);
        titleParams.topMargin = dp(5);
        page.addView(title, titleParams);
        TextView intro = text("Importe, ative, desative e remova texturas, mods Horizon e shader packs compatíveis.",
                14, 0xff59647b, false);
        LinearLayout.LayoutParams introParams = params(-1, -2);
        introParams.topMargin = dp(5);
        page.addView(intro, introParams);

        LinearLayout profileCard = card();
        LinearLayout.LayoutParams profileCardParams = params(-1, -2);
        profileCardParams.topMargin = dp(12);
        page.addView(profileCard, profileCardParams);
        profileToggle = new CheckBox(this);
        profileToggle.setText("Gerenciar perfil Fabric Bridge (experimental)");
        profileToggle.setTextColor(0xff18213a);
        profileToggle.setChecked(fabricBridgeEnabled());
        profileToggle.setOnCheckedChangeListener((button, enabled) -> {
            getSharedPreferences("horizon_fabric_bridge", MODE_PRIVATE).edit()
                    .putBoolean("enabled", enabled).apply();
            showStatus(enabled
                    ? "Perfil Fabric Bridge selecionado. Mods .hmod serão guardados na pasta isolada desse perfil."
                    : "Perfil Vanilla selecionado. Os mods Horizon serão guardados na pasta Vanilla, sem misturar os perfis.");
            refreshResources();
        });
        profileCard.addView(profileToggle, params(-1, -2));
        TextView profileHint = text(
                "A seleção é compartilhada com a tela Jogar. Os mods .hmod ficam separados por perfil; JARs Fabric são apenas armazenados para análise/conversão e nunca são executados diretamente.",
                12, 0xff68728a, false);
        LinearLayout.LayoutParams profileHintParams = params(-1, -2);
        profileHintParams.topMargin = dp(4);
        profileCard.addView(profileHint, profileHintParams);

        LinearLayout actions = card();
        LinearLayout.LayoutParams actionsParams = params(-1, -2);
        actionsParams.topMargin = dp(14);
        page.addView(actions, actionsParams);
        importFilesButton = button("Importar recursos (.zip / .hmod)", true, v -> chooseFiles());
        actions.addView(importFilesButton, params(-1, dp(60)));
        importFabricJarButton = button("Importar e converter mod Fabric (.jar)", false, v -> chooseFabricJars());
        LinearLayout.LayoutParams fabricImportParams = params(-1, dp(60));
        fabricImportParams.topMargin = dp(8);
        actions.addView(importFabricJarButton, fabricImportParams);
        importFolderButton = button("Importar pasta de shader pack", false, v -> chooseShaderFolder());
        LinearLayout.LayoutParams folderParams = params(-1, dp(60));
        folderParams.topMargin = dp(8);
        actions.addView(importFolderButton, folderParams);
        Button refresh = button("Atualizar lista de recursos", false, v -> refreshResources());
        LinearLayout.LayoutParams refreshParams = params(-1, dp(56));
        refreshParams.topMargin = dp(8);
        actions.addView(refresh, refreshParams);

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams statusParams = params(-1, -2);
        statusParams.topMargin = dp(10);
        page.addView(statusCard, statusParams);
        status = text("Pronto.", 13, 0xff59647b, false);
        statusCard.addView(status, params(-1, -2));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        progress.setMax(100);
        LinearLayout.LayoutParams progressParams = params(-1, dp(6));
        progressParams.topMargin = dp(8);
        statusCard.addView(progress, progressParams);

        TextView note = text(
                "As alterações são aplicadas ao abrir o Minecraft novamente. O conversor Horizon cria .hmod de conteúdo a partir de assets/data que entende e declara alguns blocos/itens. A lógica Java, entrypoints, Mixins e access wideners não são convertidos nem executados. Packs de textura usam ZIP com pack.mcmeta e assets/; shader packs aceitam ZIPs shaders/ ou pastas pack.toml/shaders/.",
                12, 0xff68728a, false);
        LinearLayout.LayoutParams noteParams = params(-1, -2);
        noteParams.topMargin = dp(12);
        page.addView(note, noteParams);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentParams = params(-1, -2);
        contentParams.topMargin = dp(8);
        page.addView(content, contentParams);
        Button back = button("Voltar", false, v -> finish());
        LinearLayout.LayoutParams backParams = params(-1, dp(56));
        backParams.topMargin = dp(14);
        page.addView(back, backParams);
    }

    private void chooseFiles() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/java-archive", "application/octet-stream", "*/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_IMPORT_FILES); }
        catch (Exception error) { showStatus("Não foi possível abrir o seletor: " + message(error)); }
    }

    private void chooseFabricJars() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/java-archive");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/java-archive", "application/octet-stream", "*/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_IMPORT_FABRIC_JAR); }
        catch (Exception error) { showStatus("Não foi possível abrir o seletor de JARs: " + message(error)); }
    }

    private void chooseShaderFolder() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_IMPORT_SHADER_FOLDER); }
        catch (Exception error) { showStatus("Não foi possível abrir o seletor de pastas: " + message(error)); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_IMPORT_FILES || requestCode == REQUEST_IMPORT_FABRIC_JAR) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) uris.add(uri);
                }
            } else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) importPickedFiles(uris);
        } else if (requestCode == REQUEST_IMPORT_SHADER_FOLDER && data.getData() != null) {
            importShaderFolder(data.getData());
        } else if (requestCode == REQUEST_INSTALL_DATA_PACK && data.getData() != null) {
            File pack = pendingDataPackInstall;
            pendingDataPackInstall = null;
            if (pack != null) installDataPackIntoWorld(data.getData(), pack);
        }
    }

    private void importPickedFiles(List<Uri> uris) {
        busy = true;
        setBusyUi(true);
        showStatus("Importando " + uris.size() + " arquivo(s)…");
        io.execute(() -> {
            List<String> messages = new ArrayList<>();
            int success = 0;
            for (Uri uri : uris) {
                try { messages.add("✓ " + importOne(uri)); success++; }
                catch (Exception error) { messages.add("✗ " + displayName(uri) + ": " + message(error)); }
            }
            final int imported = success;
            final String summary = String.join("\n", messages);
            mainHandler.post(() -> {
                busy = false; setBusyUi(false); refreshResources();
                showStatus("Importação concluída: " + imported + "/" + uris.size() + " arquivo(s).\\n" + summary);
            });
        });
    }

    private String importOne(Uri uri) throws Exception {
        String name = displayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        File staging = File.createTempFile("mh-resource-", ".part", getCacheDir());
        try {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("O Android não conseguiu abrir o arquivo.");
                copyLimited(input, staging, MAX_IMPORT_BYTES);
            }
            if (lower.endsWith(".jar")) {
                FabricJarInfo info = inspectFabricJar(staging);
                ensureDirs(fabricInboxDir());
                ensureDirs(bridgeModDir());
                if (fabricJarIdExists(info.id)) {
                    throw new IllegalArgumentException("Já existe um JAR Fabric importado com ID '" + info.id + "'. Exclua a versão antiga antes de importar outra.");
                }
                if (modIdExistsIn(bridgeModDir(), info.id)) {
                    throw new IllegalArgumentException("O perfil Fabric Bridge já contém um mod com ID '" + info.id + "'. Remova-o antes de importar outra versão.");
                }
                File target = copyToTarget(staging, fabricInboxDir(), fileName(name, ".jar"));
                String conversion;
                try {
                    conversion = convertFabricJarNative(staging, info);
                } catch (Exception conversionError) {
                    conversion = "O conversor Horizon não conseguiu gerar um mod de conteúdo completo: "
                            + message(conversionError) + ". O JAR original foi mantido na fila para análise.";
                }
                File auditReport = writeFabricAuditReport(info, target);
                return "JAR Fabric " + info.name + " (" + info.id + " " + info.version + ") importado; "
                        + conversion + " Original: " + target.getName()
                        + ". A saída de conteúdo fica apenas no perfil Fabric Bridge; classes Java/Mixins não são executadas. "
                        + summarizeFabricAudit(info)
                        + (auditReport == null ? "" : " Relatório salvo em " + auditReport.getAbsolutePath() + ".");
            }
            if (lower.endsWith(".hmod")) {
                String id = readModId(staging);
                if (id == null) throw new IllegalArgumentException("O .hmod não contém horizon-mod.toml e mod.wasm válidos.");
                ensureDirs(modDir());
                if (modIdExists(id)) throw new IllegalArgumentException("Já existe um mod com ID '" + id + "'. Remova a versão antiga antes de importar outra.");
                File target = copyToTarget(staging, modDir(), fileName(name, ".hmod"));
                return "mod Horizon " + id + " importado desativado (" + target.getName() + ")";
            }
            if (!lower.endsWith(".zip")) {
                throw new IllegalArgumentException("Formato não suportado. Use .hmod, ZIP de textura ou ZIP de shader pack.");
            }
            ArchiveKind kind = inspectZip(staging);
            if (kind == ArchiveKind.TEXTURE) {
                ensureDirs(textureDir());
                File target = copyToTarget(staging, textureDir(), fileName(name, ".zip"));
                return "pacote de textura importado desativado (" + target.getName() + ")";
            }
            if (kind == ArchiveKind.JAVA_SHADER) {
                ensureDirs(shaderDir());
                File target = copyToTarget(staging, shaderDir(), fileName(name, ".zip"));
                return "shader pack Java importado desativado (" + target.getName() + ")";
            }
            if (kind == ArchiveKind.HORIZON_SHADER) {
                throw new IllegalArgumentException("O ZIP contém pack.toml de shader Horizon. Extraia-o e importe a pasta descompactada.");
            }
            throw new IllegalArgumentException("ZIP não reconhecido. Pacotes de textura exigem pack.mcmeta e assets/; shader packs Java exigem shaders/.");
        } finally { staging.delete(); }
    }

    private FabricJarInfo inspectFabricJar(File file) throws Exception {
        List<String> auditWarnings = new ArrayList<>();
        Map<String, String> apiCatalog = loadFabricApiCatalog(auditWarnings);
        Set<String> apiModules = new TreeSet<>();
        Set<String> unknownApiReferences = new TreeSet<>();
        long[] classBytesScanned = {0L};
        int[] maxClassMajor = {0};
        int[] nestedClassCount = {0};
        int[] nestedJarCount = {0};
        long[] nestedArchiveBytes = {0L};
        long[] nestedUncompressedBytes = {0L};
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry metadata = zip.getEntry("fabric.mod.json");
            if (metadata == null || metadata.isDirectory()) {
                throw new IllegalArgumentException("O JAR não contém fabric.mod.json; selecione um mod Fabric válido.");
            }
            JSONObject json;
            try (InputStream input = zip.getInputStream(metadata)) {
                json = new JSONObject(new String(readLimitedBytes(input, 1024 * 1024), StandardCharsets.UTF_8));
            } catch (JSONException error) {
                throw new IllegalArgumentException("fabric.mod.json inválido: " + message(error));
            }
            String id = json.optString("id", "").trim();
            if (!id.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("fabric.mod.json não contém um ID Fabric válido.");
            }
            String name = json.optString("name", id).trim();
            if (name.isEmpty()) name = id;
            String version = json.optString("version", "desconhecida").trim();
            if (version.isEmpty()) version = "desconhecida";
            if (json.optInt("schemaVersion", -1) != 1) {
                auditWarnings.add("fabric.mod.json não declara schemaVersion 1; confirme a versão do Loader.");
            }
            int classes = 0, resources = 0, assets = 0, data = 0;
            boolean mixinFile = false, accessWidenerFile = false;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                if (path.endsWith(".class")) {
                    classes++;
                    if (entry.getSize() > MAX_CLASS_AUDIT_BYTES) {
                        auditWarnings.add("Classe ignorada por exceder o limite de auditoria: " + path);
                    } else if (entry.getSize() >= 0
                            && classBytesScanned[0] + entry.getSize() > MAX_TOTAL_CLASS_AUDIT_BYTES) {
                        if (!auditWarnings.contains("Auditoria de bytecode interrompida ao atingir o limite total de bytes.")) {
                            auditWarnings.add("Auditoria de bytecode interrompida ao atingir o limite total de bytes.");
                        }
                    } else {
                        try (InputStream input = zip.getInputStream(entry)) {
                            byte[] classBytes = readLimitedBytes(input, MAX_CLASS_AUDIT_BYTES);
                            classBytesScanned[0] += classBytes.length;
                            scanFabricClassReferences(classBytes, apiCatalog, apiModules,
                                    unknownApiReferences, maxClassMajor);
                        } catch (IOException | RuntimeException error) {
                            auditWarnings.add("Não foi possível analisar bytecode de " + path + ": " + message(error));
                        }
                    }
                }
                if (path.startsWith("assets/")) { assets++; resources++; }
                if (path.startsWith("data/")) { data++; resources++; }
                String pathLower = path.toLowerCase(Locale.ROOT);
                if (pathLower.endsWith(".mixins.json") || pathLower.endsWith(".mixin.json")
                        || (pathLower.endsWith(".json") && pathLower.contains("mixin"))) mixinFile = true;
                if (pathLower.endsWith(".accesswidener") || pathLower.endsWith(".access_widener")
                        || pathLower.endsWith(".aw")) accessWidenerFile = true;
            }
            inspectDeclaredNestedJars(zip, json, apiCatalog, apiModules, unknownApiReferences,
                    maxClassMajor, classBytesScanned, nestedClassCount, nestedJarCount,
                    nestedArchiveBytes, nestedUncompressedBytes, auditWarnings);
            classes += nestedClassCount[0];
            Object mixins = json.opt("mixins");
            boolean hasMixins = mixinFile || (mixins instanceof JSONArray && ((JSONArray) mixins).length() > 0)
                    || (mixins instanceof String && !((String) mixins).trim().isEmpty())
                    || (mixins instanceof JSONObject && ((JSONObject) mixins).length() > 0);
            boolean hasAccessWidener = accessWidenerFile
                    || !json.optString("accessWidener", "").trim().isEmpty();
            JSONObject entrypoints = json.optJSONObject("entrypoints");
            boolean hasEntrypoints = entrypoints != null && entrypoints.length() > 0;
            JSONObject dependencies = json.optJSONObject("depends");
            String fabricApiConstraint = dependencies == null ? "" : dependencies.optString("fabric-api", "");
            if (maxClassMajor[0] > MAX_SUPPORTED_CLASS_MAJOR) {
                auditWarnings.add("Bytecode Java major " + maxClassMajor[0]
                        + " é mais novo que Java 25 (major " + MAX_SUPPORTED_CLASS_MAJOR + ").");
            }
            if (!unknownApiReferences.isEmpty()) {
                auditWarnings.add(unknownApiReferences.size()
                        + " referência(s) à Fabric API não mapeada(s) no catálogo oficial 26.3.");
            }
            if (hasEntrypoints || hasMixins || hasAccessWidener || classes > 0) {
                auditWarnings.add("A conversão de assets/data não converte entrypoints, lógica Java, Mixins ou access wideners.");
            }
            if (!apiCatalog.isEmpty()) {
                auditWarnings.add("Catálogo de referência: Fabric API 0.165.0 para Minecraft 26.3; símbolos encontrados indicam uso provável, não compatibilidade comprovada.");
            }
            if (apiCatalog.isEmpty()) {
                auditWarnings.add("Catálogo Fabric API não carregado; os módulos usados pelo mod não puderam ser classificados.");
            }
            return new FabricJarInfo(id, name, version, classes, resources, assets, data,
                    hasMixins, hasAccessWidener, hasEntrypoints,
                    new ArrayList<>(apiModules), unknownApiReferences.size(), maxClassMajor[0],
                    nestedJarCount[0], fabricApiConstraint, auditWarnings);
        } catch (IOException error) {
            throw new IllegalArgumentException("O arquivo não é um JAR/ZIP válido.");
        }
    }

    private Map<String, String> loadFabricApiCatalog(List<String> warnings) {
        if (fabricApiCatalogCache != null) return fabricApiCatalogCache;
        Map<String, String> catalog = new LinkedHashMap<>();
        try (InputStream input = getAssets().open("fabric-api-26.3-catalog.json")) {
            JSONObject root = new JSONObject(new String(readLimitedBytes(input, 2 * 1024 * 1024), StandardCharsets.UTF_8));
            if (!"26.3".equals(root.optString("minecraft_version"))
                    || !"0.165.0".equals(root.optString("fabric_api_version"))) {
                warnings.add("O catálogo Fabric API empacotado tem uma versão inesperada.");
            }
            JSONObject mappings = root.optJSONObject("api_class_to_module");
            if (mappings != null) {
                java.util.Iterator<String> keys = mappings.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = mappings.opt(key);
                    if (value instanceof String) catalog.put(key, (String) value);
                }
            }
        } catch (Exception error) {
            warnings.add("Não foi possível carregar o catálogo Fabric API 26.3: " + message(error));
        }
        fabricApiCatalogCache = catalog;
        return catalog;
    }

    private void scanFabricClassReferences(byte[] classBytes, Map<String, String> catalog,
            Set<String> modules, Set<String> unknown, int[] maxClassMajor) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (input.readInt() != 0xCAFEBABE) throw new IOException("magic de class inválido");
            input.readUnsignedShort(); // minor
            int major = input.readUnsignedShort();
            maxClassMajor[0] = Math.max(maxClassMajor[0], major);
            int constantPoolCount = input.readUnsignedShort();
            List<String> utf8 = new ArrayList<>();
            for (int index = 1; index < constantPoolCount; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1: {
                        int length = input.readUnsignedShort();
                        byte[] raw = new byte[length];
                        input.readFully(raw);
                        utf8.add(new String(raw, StandardCharsets.UTF_8));
                        break;
                    }
                    case 3: case 4:
                        skipFully(input, 4);
                        break;
                    case 5: case 6:
                        skipFully(input, 8);
                        index++;
                        break;
                    case 7: case 8: case 16: case 19: case 20:
                        skipFully(input, 2);
                        break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        skipFully(input, 4);
                        break;
                    case 15:
                        skipFully(input, 3);
                        break;
                    default:
                        throw new IOException("tag desconhecida no constant pool: " + tag);
                }
            }
            for (String value : utf8) {
                java.util.regex.Matcher matcher = FABRIC_API_REFERENCE.matcher(value);
                while (matcher.find()) {
                    String reference = matcher.group();
                    if (reference.endsWith("/")) continue;
                    String internal = reference.substring("net/fabricmc/fabric/".length());
                    String module = catalog.get(internal);
                    if (module == null && internal.contains("$")) {
                        module = catalog.get(internal.substring(0, internal.indexOf('$')));
                    }
                    if (module != null) modules.add(module);
                    else unknown.add(reference);
                }
            }
        }
    }

    private static void skipFully(DataInputStream input, int count) throws IOException {
        int remaining = count;
        while (remaining > 0) {
            int skipped = input.skipBytes(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) throw new IOException("class truncada durante a leitura do constant pool");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private void inspectDeclaredNestedJars(ZipFile archive, JSONObject metadata,
            Map<String, String> catalog, Set<String> modules, Set<String> unknown,
            int[] maxClassMajor, long[] classBytesScanned, int[] nestedClassCount,
            int[] nestedJarCount, long[] nestedArchiveBytes, long[] nestedUncompressedBytes,
            List<String> warnings) {
        JSONArray declarations = metadata.optJSONArray("jars");
        if (declarations == null || declarations.length() == 0) return;
        if (declarations.length() > MAX_NESTED_JARS) {
            warnings.add("A declaração de JARs aninhados excede o limite de " + MAX_NESTED_JARS + ".");
        }
        Set<String> seen = new TreeSet<>();
        int limit = Math.min(declarations.length(), MAX_NESTED_JARS);
        for (int index = 0; index < limit; index++) {
            JSONObject definition = declarations.optJSONObject(index);
            String path = definition == null ? "" : definition.optString("file", "");
            String[] parts = path.split("/", -1);
            boolean safe = !path.isEmpty() && !path.startsWith("/") && !path.contains("\\")
                    && path.indexOf(0) < 0;
            for (String part : parts) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) safe = false;
            if (!safe) {
                warnings.add("Caminho inválido de JAR aninhado no fabric.mod.json: " + path);
                continue;
            }
            if (!seen.add(path)) {
                warnings.add("Declaração duplicada de JAR aninhado: " + path);
                continue;
            }
            ZipEntry nested = archive.getEntry(path);
            if (nested == null || nested.isDirectory()) {
                warnings.add("JAR aninhado declarado não encontrado: " + path);
                continue;
            }
            if (nested.getSize() < 0 || nested.getSize() > MAX_NESTED_JAR_BYTES
                    || nestedArchiveBytes[0] + nested.getSize() > MAX_NESTED_JAR_TOTAL_BYTES) {
                warnings.add("JAR aninhado ignorado por limite de tamanho: " + path);
                continue;
            }
            try {
                byte[] raw;
                try (InputStream input = archive.getInputStream(nested)) {
                    raw = readLimitedBytes(input, (int) MAX_NESTED_JAR_BYTES);
                }
                nestedArchiveBytes[0] += raw.length;
                try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(raw))) {
                    ZipEntry child;
                    int entryCount = 0;
                    boolean complete = true;
                    while ((child = input.getNextEntry()) != null) {
                        entryCount++;
                        if (entryCount > 50_000) {
                            warnings.add("JAR aninhado excedeu o limite de entradas: " + path);
                            complete = false;
                            break;
                        }
                        if (nestedUncompressedBytes[0] >= MAX_NESTED_UNCOMPRESSED_BYTES) {
                            warnings.add("Auditoria de JARs aninhados interrompida pelo limite total descompactado.");
                            complete = false;
                            break;
                        }
                        long remaining = MAX_NESTED_UNCOMPRESSED_BYTES - nestedUncompressedBytes[0];
                        if (child.getSize() > remaining) {
                            warnings.add("Entrada do JAR aninhado excede o orçamento de auditoria: " + child.getName());
                            complete = false;
                            break;
                        }
                        if (child.isDirectory()) continue;
                        if (child.getName().endsWith(".class")) {
                            if (child.getSize() > MAX_CLASS_AUDIT_BYTES
                                    || classBytesScanned[0] + Math.max(0L, child.getSize()) > MAX_TOTAL_CLASS_AUDIT_BYTES) {
                                warnings.add("Classe aninhada ignorada por limite de tamanho/bytes: " + child.getName());
                                complete = false;
                                break;
                            }
                            byte[] classBytes = readLimitedBytes(input, MAX_CLASS_AUDIT_BYTES);
                            nestedUncompressedBytes[0] += classBytes.length;
                            classBytesScanned[0] += classBytes.length;
                            nestedClassCount[0]++;
                            scanFabricClassReferences(classBytes, catalog, modules, unknown, maxClassMajor);
                        } else {
                            long consumed = drainNestedEntry(input,
                                    Math.min(remaining, 16L * 1024L * 1024L));
                            nestedUncompressedBytes[0] += consumed;
                        }
                    }
                    if (!complete) continue;
                    nestedJarCount[0]++;
                }
            } catch (IOException | RuntimeException error) {
                warnings.add("Não foi possível auditar o JAR aninhado " + path + ": " + message(error));
            }
        }
    }

    private static long drainNestedEntry(InputStream input, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("entrada descompactada excede limite");
        }
        return total;
    }

    private String summarizeFabricAudit(FabricJarInfo info) {
        StringBuilder summary = new StringBuilder("Auditoria Fabric API 0.165.0/MC 26.3: ");
        if (info.fabricApiModules.isEmpty()) {
            summary.append("nenhuma referência pública Fabric API foi reconhecida");
        } else {
            summary.append("módulos detectados: ").append(String.join(", ", info.fabricApiModules));
        }
        if (!info.fabricApiConstraint.isEmpty()) {
            summary.append("; dependência fabric-api declarada: ").append(info.fabricApiConstraint);
        }
        if (info.maxClassMajor > 0) {
            summary.append("; bytecode máximo Java major ").append(info.maxClassMajor);
            if (info.maxClassMajor > MAX_SUPPORTED_CLASS_MAJOR) summary.append(" (acima do alvo Java 25)");
        }
        if (info.nestedJarCount > 0) summary.append("; ").append(info.nestedJarCount).append(" JAR(s) aninhado(s) inspecionado(s)");
        if (info.unmappedApiReferences > 0) summary.append("; ").append(info.unmappedApiReferences).append(" referência(s) sem mapeamento catalogado");
        summary.append(". O JAR ainda requer porte/teste; referências detectadas não significam que a lógica Java foi convertida.");
        return summary.toString();
    }

    private File writeFabricAuditReport(FabricJarInfo info, File jar) {
        File dir = new File(home(), "fabric-bridge-audits");
        try {
            ensureDirs(dir);
            JSONObject report = new JSONObject();
            report.put("schema_version", 1);
            report.put("target_minecraft_version", "26.3");
            report.put("fabric_api_catalog_version", "0.165.0");
            report.put("mod_id", info.id);
            report.put("mod_name", info.name);
            report.put("mod_version", info.version);
            report.put("source_jar", jar.getName());
            report.put("source_size_bytes", jar.length());
            report.put("top_level_and_nested_class_count", info.classes);
            report.put("resource_count", info.resources);
            report.put("asset_count", info.assets);
            report.put("data_count", info.data);
            report.put("fabric_api_modules_detected", new JSONArray(info.fabricApiModules));
            report.put("fabric_api_constraint", info.fabricApiConstraint);
            report.put("unmapped_api_reference_count", info.unmappedApiReferences);
            report.put("java_class_major", info.maxClassMajor);
            report.put("compatible_with_java_25_bytecode_target", info.maxClassMajor == 0
                    || info.maxClassMajor <= MAX_SUPPORTED_CLASS_MAJOR);
            report.put("nested_jars_inspected", info.nestedJarCount);
            report.put("entrypoints_declared", info.hasEntrypoints);
            report.put("mixins_declared_or_detected", info.hasMixins);
            report.put("access_widener_declared_or_detected", info.hasAccessWidener);
            report.put("java_code_executed", false);
            report.put("java_behavior_converted", false);
            report.put("content_carry_over_is_not_behavior_conversion", true);
            report.put("warnings", new JSONArray(info.auditWarnings));
            File output = new File(dir, info.id + "-audit.json");
            try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(output))) {
                stream.write((report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return output;
        } catch (Exception error) {
            android.util.Log.w("MobileHorizon", "Não foi possível salvar auditoria Fabric", error);
            return null;
        }
    }

    private String convertFabricJarNative(File source, FabricJarInfo info) throws Exception {
        File candidate = new File(getCacheDir(),
                "horizon-fabric-" + info.id + "-" + UUID.randomUUID() + ".hmod");
        Process process = null;
        StringBuffer captured = new StringBuffer();
        Thread logReader = null;
        try {
            File converter = installFabricCarryTool();
            File resourceRoot = new File(getFilesDir(),
                    ".local/horizon-client/" + OfficialMinecraftInstaller.VERSION);
            ProcessBuilder builder = new ProcessBuilder(
                    "/system/bin/sh",
                    "-c",
                    "exec /system/bin/linker64 \"$@\"",
                    "linker64",
                    converter.getAbsolutePath(),
                    source.getAbsolutePath(),
                    candidate.getAbsolutePath(),
                    resourceRoot.getAbsolutePath());
            builder.directory(getCacheDir());
            builder.redirectErrorStream(true);
            process = builder.start();
            final Process running = process;
            logReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (captured) {
                            if (captured.length() < 128 * 1024) {
                                captured.append(line).append('\n');
                            }
                        }
                    }
                } catch (IOException ignored) {}
            }, "horizon-fabric-carry-log");
            logReader.setDaemon(true);
            logReader.start();

            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("A conversão nativa do conteúdo ultrapassou cinco minutos.");
            }
            logReader.join(5000);
            String log;
            synchronized (captured) { log = captured.toString(); }
            if (process.exitValue() != 0) {
                throw new IOException("Conversor Horizon encerrou com código " + process.exitValue()
                        + (log.trim().isEmpty() ? "" : ": " + log.trim()));
            }
            if (!candidate.isFile() || candidate.length() == 0) {
                throw new IOException("O conversor terminou sem gerar um .hmod.");
            }
            String lastLine = "";
            for (String line : log.split("\n")) {
                if (!line.trim().isEmpty()) lastLine = line.trim();
            }
            JSONObject result = new JSONObject(lastLine);
            if (!"ok".equals(result.optString("status"))
                    || !info.id.equals(result.optString("id"))) {
                throw new IOException("O conversor retornou um relatório sem confirmação do ID do mod.");
            }
            String generatedId = readModId(candidate);
            if (!info.id.equals(generatedId)) {
                throw new IOException("O .hmod gerado não passou na validação do manifesto de conteúdo.");
            }

            File installed = copyToTarget(candidate, bridgeModDir(), fileName(info.id, ".hmod"));
            int files = result.optInt("content_files", 0);
            int blocks = result.optInt("blocks_declared", 0);
            int items = result.optInt("items_declared", 0);
            JSONObject report = result.optJSONObject("carry_report");
            int outside = report == null ? 0 : report.optInt("outside", 0);
            int unknownBlocks = report == null ? 0 : report.optInt("blocks_unknown", 0);
            StringBuilder summary = new StringBuilder()
                    .append("conteúdo convertido em mod Horizon ").append(installed.getName())
                    .append(" (").append(files).append(" arquivos, ")
                    .append(blocks).append(" blocos e ").append(items).append(" itens declarados)")
                    .append(" e guardado no perfil Fabric Bridge");
            if (outside > 0) summary.append("; ").append(outside).append(" arquivos foram separados para revisão");
            if (unknownBlocks > 0) summary.append("; ").append(unknownBlocks).append(" blocos não puderam ser declarados automaticamente");
            if (info.classes > 0 || info.hasEntrypoints || info.hasMixins || info.hasAccessWidener) {
                summary.append(". Conversão parcial: o JAR contém código Java/entrypoints/Mixins/AW, que não foram convertidos; os recursos podem funcionar, mas a lógica Java do mod não");
            } else {
                summary.append(". O conteúdo gerado foi validado pelo leitor do Horizon; teste-o no jogo");
            }
            return summary.toString();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (logReader != null && logReader.isAlive()) {
                try { logReader.join(1000); } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            candidate.delete();
        }
    }

    private File installFabricCarryTool() throws Exception {
        File target = new File(getFilesDir(), ".local/horizon-fabric-carry-arm64");
        ensureDirs(target.getParentFile());
        File partial = new File(target.getParentFile(),
                ".horizon-fabric-carry-arm64-" + UUID.randomUUID() + ".part");
        try (InputStream input = getAssets().open("horizon-fabric-carry-arm64");
             OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            copyStream(input, output, MAX_IMPORT_BYTES);
        } catch (IOException error) {
            partial.delete();
            throw new IOException("Este APK não inclui o conversor ARM64 horizon-fabric-carry; instale uma build nova.", error);
        }
        if (!partial.renameTo(target)) {
            try (InputStream input = new BufferedInputStream(new FileInputStream(partial));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                copyStream(input, output, MAX_IMPORT_BYTES);
            } finally {
                partial.delete();
            }
        }
        Os.chmod(target.getAbsolutePath(), 0700);
        File linker = new File("/system/bin/linker64");
        if (!linker.isFile() || !linker.canExecute()) {
            throw new IOException("O dispositivo não disponibiliza o linker64 necessário para o conversor nativo.");
        }
        return target;
    }

    private String convertFabricPayload(File source, FabricJarInfo info) throws Exception {
        List<ZipEntry> assetEntries = new ArrayList<>();
        List<ZipEntry> dataEntries = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        long expectedBytes = 0;
        try (ZipFile zip = new ZipFile(source)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.startsWith("assets/") && !name.startsWith("data/")) continue;
                if (++entryCount > MAX_FOLDER_ENTRIES) {
                    throw new IllegalArgumentException("O JAR contém itens demais para conversão segura.");
                }
                validatePackEntry(name);
                if (!seen.add(name)) {
                    throw new IllegalArgumentException("O JAR contém caminhos de recurso duplicados: " + name);
                }
                long size = entry.getSize();
                if (size < 0 || size > MAX_IMPORT_BYTES) {
                    throw new IllegalArgumentException("Tamanho de recurso inválido ou excessivo: " + name);
                }
                expectedBytes += size;
                if (expectedBytes > MAX_IMPORT_BYTES) {
                    throw new IllegalArgumentException("Os recursos descompactados ultrapassam o limite de 512 MiB.");
                }
                if (name.startsWith("assets/")) assetEntries.add(entry);
                else dataEntries.add(entry);
            }
            if (assetEntries.isEmpty() && dataEntries.isEmpty()) {
                return "nenhum arquivo em assets/ ou data/ foi encontrado; o mod completo ainda exige adaptação nativa.";
            }

            File resourceTarget = null, dataTarget = null, resourcePart = null, dataPart = null;
            boolean resourceCommitted = false;
            try {
                if (!assetEntries.isEmpty()) {
                    ensureDirs(textureDir());
                    resourceTarget = uniqueFile(textureDir(), convertedPackName(info, false));
                    resourcePart = temporaryPackFile(resourceTarget);
                    writeConvertedPack(zip, assetEntries, resourcePart, info, false);
                }
                if (!dataEntries.isEmpty()) {
                    ensureDirs(convertedDataPackDir());
                    dataTarget = uniqueFile(convertedDataPackDir(), convertedPackName(info, true));
                    dataPart = temporaryPackFile(dataTarget);
                    writeConvertedPack(zip, dataEntries, dataPart, info, true);
                }
                if (resourcePart != null) {
                    if (!resourcePart.renameTo(resourceTarget)) {
                        throw new IOException("Não foi possível finalizar o pacote de recursos convertido.");
                    }
                    resourceCommitted = true;
                }
                if (dataPart != null && !dataPart.renameTo(dataTarget)) {
                    throw new IOException("Não foi possível finalizar o pacote de dados convertido.");
                }
            } catch (Exception error) {
                if (resourceCommitted && resourceTarget != null) resourceTarget.delete();
                if (dataTarget != null && dataTarget.exists() && dataPart != null && dataPart.exists()) dataTarget.delete();
                throw error;
            } finally {
                if (resourcePart != null) resourcePart.delete();
                if (dataPart != null) dataPart.delete();
            }
        }

        boolean code = info.classes > 0 || info.hasEntrypoints || info.hasMixins || info.hasAccessWidener;
        StringBuilder result = new StringBuilder();
        if (!assetEntries.isEmpty()) {
            result.append("assets/ convertidos para pack de recursos desativado");
        }
        if (!dataEntries.isEmpty()) {
            if (result.length() > 0) result.append("; ");
            result.append("data/ convertido para pacote de dados, aguardando instalação em um mundo");
        }
        if (code) {
            result.append(". Conversão parcial: ").append(info.classes)
                    .append(" classes Java, entrypoints/Mixins/AW não foram convertidos; a lógica do mod ainda não funcionará");
        } else {
            result.append(". Nenhuma lógica Java foi detectada, mas confira identificadores e formatos dentro do jogo");
        }
        return result.toString();
    }

    private String convertedPackName(FabricJarInfo info, boolean dataPack) {
        return fileName("fabric-" + info.id + "-" + info.version
                + (dataPack ? "-datapack" : "-resources"), ".zip");
    }

    private File temporaryPackFile(File target) {
        return new File(target.getParentFile(),
                "." + target.getName() + ".importing-" + UUID.randomUUID());
    }

    private void validatePackEntry(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Caminho inseguro no JAR: " + name);
        }
        String[] parts = name.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Caminho inseguro no JAR: " + name);
            }
        }
        if (!name.startsWith("assets/") && !name.startsWith("data/")) {
            throw new IllegalArgumentException("Caminho inesperado durante a conversão: " + name);
        }
    }

    private byte[] convertedPackMetadata(FabricJarInfo info, boolean dataPack) throws JSONException {
        JSONArray format = new JSONArray();
        format.put(dataPack ? 121 : 97);
        format.put(dataPack ? 0 : 1);
        JSONObject metadata = new JSONObject();
        metadata.put("description", info.name + " — recursos extraídos de JAR Fabric; código Java não incluído");
        metadata.put("min_format", new JSONArray(format.toString()));
        metadata.put("max_format", new JSONArray(format.toString()));
        JSONObject root = new JSONObject();
        root.put("pack", metadata);
        return (root.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void writeConvertedPack(ZipFile source, List<ZipEntry> entries, File destination,
                                    FabricJarInfo info, boolean dataPack) throws Exception {
        ensureDirs(destination.getParentFile());
        byte[] metadata = convertedPackMetadata(info, dataPack);
        long totalBytes = 0;
        int fileCount = 0;
        try (ZipOutputStream output = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(destination)))) {
            output.putNextEntry(new ZipEntry("pack.mcmeta"));
            output.write(metadata);
            output.closeEntry();
            byte[] buffer = new byte[BUFFER_SIZE];
            for (ZipEntry entry : entries) {
                if (++fileCount > MAX_FOLDER_ENTRIES) {
                    throw new IOException("O pack convertido contém arquivos demais.");
                }
                if (entry.getSize() > MAX_IMPORT_BYTES) {
                    throw new IOException("Arquivo grande demais no pack convertido: " + entry.getName());
                }
                output.putNextEntry(new ZipEntry(entry.getName()));
                long entryBytes = 0;
                try (InputStream input = source.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        entryBytes += count;
                        totalBytes += count;
                        if (entryBytes > MAX_IMPORT_BYTES || totalBytes > MAX_IMPORT_BYTES) {
                            throw new IOException("Os recursos descompactados ultrapassam o limite de 512 MiB.");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                if (entry.getSize() >= 0 && entryBytes != entry.getSize()) {
                    throw new IOException("Tamanho divergente ao ler " + entry.getName());
                }
                output.closeEntry();
            }
            output.finish();
        } catch (Exception error) {
            destination.delete();
            throw error;
        }
    }

    private boolean fabricJarIdExists(String id) {
        File[] files = fabricInboxDir().listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) return false;
        for (File file : files) {
            try { if (id.equals(inspectFabricJar(file).id)) return true; }
            catch (Exception ignored) {}
        }
        return false;
    }

    private ArchiveKind inspectZip(File file) throws Exception {
        boolean packMeta = false, assets = false, shaders = false, horizonToml = false;
        try (ZipFile zip = new ZipFile(file)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().replace('\\', '/');
                if (name.equals("pack.mcmeta")) packMeta = true;
                if (name.startsWith("assets/") && !name.endsWith("/")) assets = true;
                if (name.startsWith("shaders/") && !name.endsWith("/")) shaders = true;
                if (name.equals("pack.toml")) horizonToml = true;
            }
        } catch (IOException error) { throw new IllegalArgumentException("O arquivo não é um ZIP válido."); }
        if (packMeta && assets) return ArchiveKind.TEXTURE;
        if (horizonToml && shaders) return ArchiveKind.HORIZON_SHADER;
        if (shaders) return ArchiveKind.JAVA_SHADER;
        return ArchiveKind.UNKNOWN;
    }

    private String readModId(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry manifest = zip.getEntry("horizon-mod.toml");
            if (manifest == null || manifest.isDirectory()) return null;
            String manifestText;
            try (InputStream input = zip.getInputStream(manifest)) {
                manifestText = new String(readLimitedBytes(input, 64 * 1024), StandardCharsets.UTF_8);
            }
            Matcher matcher = MOD_ID.matcher(manifestText);
            if (!matcher.find()) return null;
            ZipEntry wasm = zip.getEntry("mod.wasm");
            if (wasm != null && !wasm.isDirectory()) return matcher.group(1);

            // Horizon API 0.2 allows content-only .hmod archives with no wasm,
            // but only when the manifest grants registries and data/assets exist.
            if (!Pattern.compile("(?m)^\\s*api\\s*=\\s*\"0\\.2\"\\s*$").matcher(manifestText).find()
                    || !manifestText.contains("\"registries\"")) {
                return null;
            }
            boolean hasContent = false;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && (name.startsWith("assets/") || name.startsWith("data/"))) {
                    hasContent = true;
                    break;
                }
            }
            return hasContent ? matcher.group(1) : null;
        } catch (IOException error) { return null; }
    }

    private boolean modIdExists(String id) {
        return modIdExistsIn(modDir(), id);
    }

    private boolean modIdExistsIn(File directory, String id) {
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".hmod"));
        if (files == null) return false;
        for (File file : files) {
            try { if (id.equals(readModId(file))) return true; }
            catch (Exception ignored) {}
        }
        return false;
    }

    private File copyToTarget(File source, File directory, String wantedName) throws Exception {
        ensureDirs(directory);
        File target = uniqueFile(directory, wantedName);
        File partial = new File(directory, "." + target.getName() + ".importing");
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            copyStream(input, output, MAX_IMPORT_BYTES);
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IOException("Não foi possível instalar o arquivo importado.");
        }
        return target;
    }

    private void importShaderFolder(Uri treeUri) {
        busy = true; setBusyUi(true); showStatus("Copiando pasta de shader pack…");
        io.execute(() -> {
            File staging = null;
            String summary;
            try {
                ensureDirs(shaderDir());
                String rootId = DocumentsContract.getTreeDocumentId(treeUri);
                String name = treeDisplayName(treeUri, rootId);
                File target = uniqueFile(shaderDir(), safeName(name));
                staging = new File(shaderDir(), "." + target.getName() + ".importing-" + UUID.randomUUID());
                ensureDirs(staging);
                CopyCounter counter = new CopyCounter();
                copyDocumentTree(treeUri, rootId, staging, new java.util.HashSet<>(), counter, 0);
                if (!new File(staging, "pack.toml").isFile() && !new File(staging, "shaders").isDirectory()) {
                    throw new IllegalArgumentException("A pasta não contém pack.toml nem shaders/.");
                }
                if (!staging.renameTo(target)) throw new IOException("Não foi possível finalizar a importação da pasta.");
                summary = "Shader pack importado desativado: " + target.getName()
                        + " (" + counter.files + " arquivo(s)).";
            } catch (Exception error) {
                if (staging != null) try { deleteRecursively(staging); } catch (Exception ignored) {}
                summary = "Falha ao importar pasta: " + message(error);
            }
            final String result = summary;
            mainHandler.post(() -> {
                busy = false; setBusyUi(false); refreshResources(); showStatus(result);
            });
        });
    }

    private static final class CopyCounter { long bytes; int files; int entries; }

    private void copyDocumentTree(Uri treeUri, String documentId, File targetDir, Set<String> visited,
                                  CopyCounter counter, int depth) throws Exception {
        if (depth > MAX_FOLDER_DEPTH) throw new IllegalArgumentException("A pasta é profunda demais.");
        if (!visited.add(documentId)) throw new IllegalArgumentException("O provedor retornou uma pasta repetida.");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            if (cursor == null) throw new IOException("Não foi possível listar a pasta selecionada.");
            int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                if (++counter.entries > MAX_FOLDER_ENTRIES) throw new IllegalArgumentException("A pasta contém itens demais.");
                String childId = cursor.getString(idCol);
                String name = safeSegment(cursor.getString(nameCol));
                String mime = cursor.getString(mimeCol);
                File target = new File(targetDir, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    ensureDirs(target);
                    copyDocumentTree(treeUri, childId, target, visited, counter, depth + 1);
                } else {
                    if (++counter.files > MAX_FOLDER_ENTRIES) throw new IllegalArgumentException("A pasta contém arquivos demais.");
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IOException("Não foi possível abrir " + name);
                        ensureDirs(target.getParentFile());
                        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                            counter.bytes += copyStream(input, output, MAX_IMPORT_BYTES - counter.bytes);
                        }
                    }
                }
            }
        }
    }

    private String treeDisplayName(Uri treeUri, String documentId) {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        try (Cursor cursor = getContentResolver().query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return safeName(name);
            }
        } catch (Exception ignored) {}
        int slash = documentId.lastIndexOf('/');
        return safeName(slash >= 0 ? documentId.substring(slash + 1) : documentId);
    }

    private void refreshResources() {
        if (content == null) return;
        content.removeAllViews();
        ensureUiDir(textureDir()); ensureUiDir(modDir()); ensureUiDir(shaderDir());
        ensureUiDir(fabricInboxDir()); ensureUiDir(convertedDataPackDir());
        List<ResourceItem> textures = new ArrayList<>(), mods = new ArrayList<>(),
                shaders = new ArrayList<>(), fabricJars = new ArrayList<>(),
                convertedDataPacks = new ArrayList<>();

        File[] items = textureDir().listFiles();
        if (items != null) for (File file : items) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
            try {
                if (inspectZip(file) == ArchiveKind.TEXTURE) {
                    textures.add(new ResourceItem(Kind.TEXTURE, file.getName(), file, file.getName(),
                            readPackIds().contains("file/" + file.getName()),
                            "Pacote de textura Java; aplica na próxima abertura."));
                }
            } catch (Exception ignored) {}
        }
        items = modDir().listFiles();
        if (items != null) for (File file : items) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".hmod")) continue;
            String id = null;
            try { id = readModId(file); } catch (Exception ignored) {}
            mods.add(new ResourceItem(Kind.MOD, file.getName(), file, id == null ? "" : id,
                    id != null && readEnabledModIds().contains(id),
                    id == null ? "Manifest inválido; não pode ser ativado." : "ID: " + id + " · Mod WebAssembly Horizon."));
        }
        items = shaderDir().listFiles();
        if (items != null) for (File file : items) {
            boolean folder = file.isDirectory() && (new File(file, "pack.toml").isFile()
                    || new File(file, "shaders").isDirectory());
            boolean zip = file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")
                    && isJavaShaderZip(file);
            if (folder || zip) {
                shaders.add(new ResourceItem(Kind.SHADER, file.getName(), file, file.getName(),
                        file.getName().equals(readGraphicsOption("shaderPack")),
                        folder ? "Pasta de shader Horizon/Java." : "Shader pack Java ZIP."));
            }
        }

        items = fabricInboxDir().listFiles();
        if (items != null) for (File file : items) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            try {
                FabricJarInfo info = inspectFabricJar(file);
                boolean resourceConverted = info.assets > 0 && hasConvertedFabricPack(info, false);
                boolean dataConverted = info.data > 0 && hasConvertedFabricPack(info, true);
                boolean contentConverted = modIdExistsIn(bridgeModDir(), info.id);
                boolean code = info.classes > 0 || info.hasEntrypoints || info.hasMixins || info.hasAccessWidener;
                StringBuilder details = new StringBuilder("ID: ").append(info.id).append(" · versão ")
                        .append(info.version).append(" · ").append(info.classes).append(" classes · ")
                        .append(info.resources).append(" arquivos assets/data");
                if (contentConverted) details.append(" · convertido em .hmod de conteúdo no perfil Bridge");
                else {
                    if (resourceConverted) details.append(" · assets convertidos para pack desativado");
                    if (dataConverted) details.append(" · data convertido para pack por mundo");
                }
                if (info.hasMixins) details.append(" · Mixins");
                if (info.hasAccessWidener) details.append(" · Access Widener");
                if (code) details.append(" · código Java/entrypoints não convertido; a lógica do mod ainda não funciona completa");
                else if (contentConverted || resourceConverted || dataConverted) details.append(" · conteúdo portátil convertido; validar no jogo");
                else details.append(" · aguardando conversão nativa; não executado");
                fabricJars.add(new ResourceItem(Kind.FABRIC_JAR, file.getName(), file,
                        info.id, false, details.toString()));
            } catch (Exception error) {
                fabricJars.add(new ResourceItem(Kind.FABRIC_JAR, file.getName(), file,
                        "", false, "JAR importado, mas sua análise falhou: " + message(error)));
            }
        }

        items = convertedDataPackDir().listFiles();
        if (items != null) for (File file : items) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
            if (isConvertedDataPack(file)) {
                convertedDataPacks.add(new ResourceItem(Kind.DATA_PACK, file.getName(), file,
                        file.getName(), false,
                        "Pacote de dados extraído automaticamente de um JAR Fabric. Precisa ser instalado dentro de um mundo."));
            }
        }

        Comparator<ResourceItem> byName = Comparator.comparing(item -> item.name.toLowerCase(Locale.ROOT));
        Collections.sort(textures, byName); Collections.sort(mods, byName);
        Collections.sort(shaders, byName); Collections.sort(fabricJars, byName);
        Collections.sort(convertedDataPacks, byName);
        addSection("Pacotes de textura", "ZIPs Java com pack.mcmeta e assets/.", textures);
        addSection(fabricBridgeEnabled() ? "Mods Horizon — perfil Fabric Bridge" : "Mods Horizon — perfil Vanilla",
                "Arquivos .hmod separados por perfil; a ativação vale no próximo início.", mods);
        addSection("JARs Fabric importados — conversão automática de conteúdo",
                "Assets/data podem ser extraídos; classes Java, entrypoints e Mixins ainda exigem adaptação para Horizon.", fabricJars);
        addSection("Pacotes de dados convertidos — instalação por mundo",
                "Selecione a pasta de um mundo salvo para copiar o pack para datapacks/.", convertedDataPacks);
        addSection("Shader packs", "Pastas pack.toml/shaders/ e shader ZIPs Java reconhecidos.", shaders);
        if (textures.isEmpty() && mods.isEmpty() && shaders.isEmpty()
                && fabricJars.isEmpty() && convertedDataPacks.isEmpty()) {
            TextView empty = text("Nenhum recurso compatível instalado. Importe um arquivo ou uma pasta de shader pack.", 14, 0xff68728a, false);
            LinearLayout.LayoutParams p = params(-1, -2); p.topMargin = dp(8); content.addView(empty, p);
        }
    }

    private void chooseWorldForDataPack(File pack) {
        if (busy) return;
        pendingDataPackInstall = pack;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_INSTALL_DATA_PACK);
        } catch (Exception error) {
            pendingDataPackInstall = null;
            showStatus("Não foi possível abrir o seletor do mundo: " + message(error));
        }
    }

    private void installDataPackIntoWorld(Uri treeUri, File pack) {
        busy = true;
        setBusyUi(true);
        showStatus("Instalando pacote de dados no mundo selecionado…");
        io.execute(() -> {
            Uri createdFile = null;
            String outcome;
            try {
                if (pack == null || !pack.isFile() || !isConvertedDataPack(pack)) {
                    throw new IOException("O pacote convertido não existe ou não é válido.");
                }
                String rootId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri worldUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
                if (findChildDocumentUri(treeUri, rootId, "level.dat") == null) {
                    throw new IllegalArgumentException(
                            "Selecione a pasta raiz de um mundo salvo (aquela que contém level.dat), não a pasta datapacks.");
                }

                Uri datapacksUri = findChildDocumentUri(treeUri, rootId, "datapacks");
                if (datapacksUri == null) {
                    datapacksUri = DocumentsContract.createDocument(getContentResolver(), worldUri,
                            DocumentsContract.Document.MIME_TYPE_DIR, "datapacks");
                }
                if (datapacksUri == null) throw new IOException("O Android não conseguiu criar a pasta datapacks do mundo.");

                String outputName = uniqueDocumentName(treeUri,
                        DocumentsContract.getDocumentId(datapacksUri), safeName(pack.getName()));
                createdFile = DocumentsContract.createDocument(getContentResolver(), datapacksUri,
                        "application/zip", outputName);
                if (createdFile == null) throw new IOException("O Android não conseguiu criar o arquivo do pacote no mundo.");

                try (InputStream input = new BufferedInputStream(new FileInputStream(pack));
                     OutputStream output = getContentResolver().openOutputStream(createdFile, "w")) {
                    if (output == null) throw new IOException("Não foi possível gravar no armazenamento escolhido.");
                    copyStream(input, output, MAX_IMPORT_BYTES);
                    output.flush();
                }
                outcome = "Pacote de dados instalado em datapacks/" + outputName
                        + ". Feche e reabra o mundo para validar o conteúdo.";
            } catch (Exception error) {
                if (createdFile != null) {
                    try { DocumentsContract.deleteDocument(getContentResolver(), createdFile); }
                    catch (Exception ignored) {}
                }
                outcome = "Não foi possível instalar o pacote no mundo: " + message(error);
            }
            final String result = outcome;
            mainHandler.post(() -> {
                busy = false;
                setBusyUi(false);
                showStatus(result);
                refreshResources();
            });
        });
    }

    private Uri findChildDocumentUri(Uri treeUri, String parentDocumentId, String wantedName)
            throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        try (Cursor cursor = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor == null) throw new IOException("Não foi possível listar a pasta escolhida.");
            int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameColumn);
                if (wantedName.equals(name)) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn));
                }
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("O provedor de arquivos não oferece acesso à pasta escolhida.", error);
        }
        return null;
    }

    private String uniqueDocumentName(Uri treeUri, String parentDocumentId, String wantedName)
            throws IOException {
        String safe = safeName(wantedName);
        String extension = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            extension = safe.substring(dot);
            safe = safe.substring(0, dot);
        }
        String candidate = safe + extension;
        int index = 2;
        while (findChildDocumentUri(treeUri, parentDocumentId, candidate) != null) {
            candidate = safe + "_" + index++ + extension;
        }
        return candidate;
    }

    private boolean hasConvertedFabricPack(FabricJarInfo info, boolean dataPack) {
        File directory = dataPack ? convertedDataPackDir() : textureDir();
        String stem = convertedPackName(info, dataPack);
        if (stem.endsWith(".zip")) stem = stem.substring(0, stem.length() - 4);
        final String fileStem = stem;
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith(fileStem) && name.toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (files == null) return false;
        for (File file : files) {
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry metadata = zip.getEntry("pack.mcmeta");
                if (metadata == null) continue;
                try (InputStream input = zip.getInputStream(metadata)) {
                    String value = new String(readLimitedBytes(input, 64 * 1024), StandardCharsets.UTF_8);
                    if (!value.contains("recursos extraídos de JAR Fabric")) continue;
                }
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (dataPack && name.startsWith("data/")) return true;
                    if (!dataPack && name.startsWith("assets/")) return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean isConvertedDataPack(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("pack.mcmeta") == null) return false;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith("data/")) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    private boolean isJavaShaderZip(File file) {
        try { return inspectZip(file) == ArchiveKind.JAVA_SHADER; }
        catch (Exception ignored) { return false; }
    }

    private void addSection(String title, String subtitle, List<ResourceItem> items) {
        LinearLayout section = card();
        LinearLayout.LayoutParams sectionParams = params(-1, -2);
        sectionParams.topMargin = dp(8);
        content.addView(section, sectionParams);
        section.addView(text(title, 17, 0xff18213a, true), params(-1, -2));
        LinearLayout.LayoutParams sub = params(-1, -2); sub.topMargin = dp(3);
        section.addView(text(subtitle, 12, 0xff68728a, false), sub);
        if (items.isEmpty()) {
            TextView none = text("Nenhum item instalado.", 13, 0xff68728a, false);
            LinearLayout.LayoutParams p = params(-1, -2); p.topMargin = dp(8); section.addView(none, p);
            return;
        }
        for (ResourceItem item : items) addResourceRow(section, item);
    }

    private void addResourceRow(LinearLayout section, ResourceItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xfff7f8fc); bg.setCornerRadius(dp(10)); bg.setStroke(dp(1), 0xffdde3ef);
        row.setBackground(bg);
        LinearLayout.LayoutParams rowParams = params(-1, -2);
        rowParams.topMargin = dp(8); section.addView(row, rowParams);
        row.addView(text(item.name, 14, 0xff18213a, true), params(-1, -2));
        LinearLayout.LayoutParams detail = params(-1, -2); detail.topMargin = dp(3);
        row.addView(text(item.detail, 12, 0xff68728a, false), detail);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL); actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = params(-1, -2); actionsParams.topMargin = dp(4);
        row.addView(actions, actionsParams);
        CheckBox active = new CheckBox(this);
        active.setText(item.kind == Kind.FABRIC_JAR ? "Conteúdo convertido / pendente"
                : item.kind == Kind.DATA_PACK ? "Instalação por mundo"
                : item.active ? "Ativo" : "Desativado");
        active.setTextColor(0xff283550);
        active.setChecked(item.kind != Kind.FABRIC_JAR && item.kind != Kind.DATA_PACK && item.active);
        active.setEnabled(item.kind != Kind.FABRIC_JAR && item.kind != Kind.DATA_PACK
                && (item.kind != Kind.MOD || !item.id.isEmpty()));
        active.setOnCheckedChangeListener((button, checked) -> {
            try {
                setActive(item, checked);
                showStatus(item.name + (checked ? " ativado" : " desativado")
                        + ". A mudança será aplicada quando o Minecraft iniciar novamente.");
                refreshResources();
            } catch (Exception error) {
                button.setOnCheckedChangeListener(null);
                button.setChecked(item.active);
                button.setOnCheckedChangeListener((b, value) -> {
                    try { setActive(item, value); refreshResources(); }
                    catch (Exception e) { showStatus(message(e)); }
                });
                showStatus("Não foi possível alterar " + item.name + ": " + message(error));
            }
        });
        actions.addView(active, new LinearLayout.LayoutParams(0, dp(42), 1f));
        if (item.kind == Kind.DATA_PACK) {
            actions.addView(button("Instalar no mundo", true, v -> chooseWorldForDataPack(item.path)),
                    new LinearLayout.LayoutParams(-2, dp(42)));
        }
        actions.addView(button("Excluir", false, v -> confirmDelete(item)),
                new LinearLayout.LayoutParams(-2, dp(42)));
    }

    private void setActive(ResourceItem item, boolean enabled) throws Exception {
        if (item.kind == Kind.FABRIC_JAR || item.kind == Kind.DATA_PACK) {
            throw new IllegalArgumentException("Este recurso não pode ser ativado globalmente.");
        }
        if (item.kind == Kind.MOD) {
            if (item.id.isEmpty()) throw new IllegalArgumentException("O manifesto deste mod é inválido.");
            TreeSet<String> ids = readEnabledModIds();
            if (enabled) ids.add(item.id); else ids.remove(item.id);
            writeEnabledModIds(ids);
        } else if (item.kind == Kind.TEXTURE) {
            LinkedHashSet<String> packs = readPackIds();
            String id = "file/" + item.name;
            packs.remove(id);
            if (enabled) packs.add(id);
            writePackIds(packs);
        } else {
            String selected = readGraphicsOption("shaderPack");
            writeOption(graphicsFile(), "shaderPack", enabled ? item.name
                    : (item.name.equals(selected) ? "" : selected));
        }
    }

    private void confirmDelete(ResourceItem item) {
        new AlertDialog.Builder(this).setTitle("Excluir recurso?")
                .setMessage("O arquivo " + item.name + " será removido do armazenamento do jogo. Essa ação não pode ser desfeita.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (dialog, which) -> {
                    try {
                        if (item.kind != Kind.FABRIC_JAR && item.kind != Kind.DATA_PACK) setActive(item, false);
                        deleteRecursively(item.path);
                        if (item.kind == Kind.SHADER) new File(shaderDir(), item.name + ".txt").delete();
                        refreshResources();
                        showStatus(item.name + " excluído.");
                    } catch (Exception error) {
                        showStatus("Falha ao excluir " + item.name + ": " + message(error));
                    }
                }).show();
    }

    private LinkedHashSet<String> readPackIds() {
        String raw = readOption(optionsFile(), "resourcePacks");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, "");
                if (!id.isEmpty()) ids.add(id);
            }
        } catch (JSONException | RuntimeException error) {
            // Fallback for older/hand-edited options files: read each quoted entry.
            int cursor = 0;
            while (raw != null && cursor < raw.length()) {
                int start = raw.indexOf('"', cursor);
                if (start < 0) break;
                int end = raw.indexOf('"', start + 1);
                if (end < 0) break;
                String id = raw.substring(start + 1, end);
                if (!id.isEmpty()) ids.add(id);
                cursor = end + 1;
            }
        }
        if (!ids.contains("vanilla")) {
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            ordered.add("vanilla"); ordered.addAll(ids); ids = ordered;
        }
        return ids;
    }

    private void writePackIds(LinkedHashSet<String> ids) throws IOException {
        JSONArray array = new JSONArray();
        array.put("vanilla");
        for (String id : ids) if (!"vanilla".equals(id)) array.put(id);
        writeOption(optionsFile(), "resourcePacks", array.toString());
    }

    private TreeSet<String> readEnabledModIds() {
        TreeSet<String> ids = new TreeSet<>();
        if (!modListFile().isFile()) return ids;
        try {
            for (String line : java.nio.file.Files.readAllLines(modListFile().toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int colon = trimmed.indexOf(':');
                if (colon <= 0 || !"true".equals(trimmed.substring(colon + 1).trim())) continue;
                String key = trimmed.substring(0, colon).trim();
                if (key.startsWith("mod.")) {
                    String id = key.substring(4);
                    if (id.matches("[A-Za-z0-9_.-]+")) ids.add(id);
                }
            }
        } catch (IOException ignored) {}
        return ids;
    }

    private void writeEnabledModIds(Set<String> ids) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String id : new TreeSet<>(ids)) {
            if (id.matches("[A-Za-z0-9_.-]+")) text.append("mod.").append(id).append(":true\n");
        }
        writeUtf8(modListFile(), text.toString());
    }

    private String readGraphicsOption(String key) { return readOption(graphicsFile(), key); }

    private String readOption(File file, String key) {
        String fallback = "resourcePacks".equals(key) ? "[\"vanilla\"]" : "";
        if (!file.isFile()) return fallback;
        String prefix = key + ":";
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
            }
        } catch (IOException ignored) {}
        return fallback;
    }

    private void writeOption(File file, String key, String value) throws IOException {
        ensureDirs(file.getParentFile());
        List<String> old = new ArrayList<>();
        if (file.isFile()) old.addAll(java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        String prefix = key + ":";
        List<String> out = new ArrayList<>();
        boolean found = false;
        for (String line : old) {
            if (line.startsWith(prefix)) {
                if (!found) { out.add(prefix + value); found = true; }
            } else out.add(line);
        }
        if (!found) out.add(prefix + value);
        writeUtf8(file, String.join("\n", out) + "\n");
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        String name = uri.getLastPathSegment();
        return name == null ? "resource.zip" : name;
    }

    private void copyLimited(InputStream input, File destination, long limit) throws Exception {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            copyStream(input, output, limit);
        }
    }

    private long copyStream(InputStream input, OutputStream output, long limit) throws Exception {
        if (limit < 0) throw new IllegalArgumentException("O recurso ultrapassa o limite de 512 MiB.");
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IllegalArgumentException("O recurso ultrapassa o limite de 512 MiB.");
            output.write(buffer, 0, count);
        }
        return total;
    }

    private byte[] readLimitedBytes(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IllegalArgumentException("Manifesto muito grande.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String fileName(String source, String ext) {
        String name = safeName(source);
        if (!name.toLowerCase(Locale.ROOT).endsWith(ext)) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            name += ext;
        }
        return name;
    }

    private static String safeName(String source) {
        if (source == null) return "resource";
        String value = source.replace('\\', '_').replace('/', '_').trim()
                .replaceAll("[^A-Za-z0-9._ ()-]", "_");
        while (value.startsWith(".")) value = value.substring(1);
        if (value.isEmpty()) value = "resource";
        return value.length() > 96 ? value.substring(0, 96) : value;
    }

    private static String safeSegment(String source) {
        String value = safeName(source);
        if (".".equals(value) || "..".equals(value) || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Nome de arquivo inseguro.");
        }
        return value;
    }

    private static File uniqueFile(File directory, String name) {
        String safe = safeName(name);
        File target = new File(directory, safe);
        if (!target.exists()) return target;
        String ext = ""; int dot = safe.lastIndexOf('.');
        if (dot > 0) { ext = safe.substring(dot); safe = safe.substring(0, dot); }
        int index = 2;
        do { target = new File(directory, safe + "_" + index++ + ext); } while (target.exists());
        return target;
    }

    private static void ensureDirs(File directory) throws IOException {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())) {
            throw new IOException("Não foi possível criar a pasta de destino.");
        }
    }

    private void ensureUiDir(File directory) {
        try { ensureDirs(directory); } catch (IOException error) { showStatus(message(error)); }
    }

    private static void writeUtf8(File file, String value) throws IOException {
        ensureDirs(file.getParentFile());
        File temp = new File(file.getParentFile(), "." + file.getName() + ".tmp");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(file)) {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
            }
            temp.delete();
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Não foi possível remover " + file.getName() + ".");
    }

    private void setBusyUi(boolean value) {
        importFilesButton.setEnabled(!value);
        importFabricJarButton.setEnabled(!value);
        importFolderButton.setEnabled(!value);
        profileToggle.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(value);
    }

    private void showStatus(String value) {
        if (status != null) status.setText(value == null ? "" : value);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dp(13)); bg.setStroke(dp(1), 0xffe0e5ef);
        view.setBackground(bg);
        return view;
    }

    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setSingleLine(false);
        view.setMaxLines(2);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setMinHeight(dp(56));
        view.setTextColor(primary ? Color.WHITE : 0xff26324d);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? 0xff5363bd : 0xffe9edf6); bg.setCornerRadius(dp(10));
        view.setBackground(bg); view.setOnClickListener(listener);
        return view;
    }

    private LinearLayout.LayoutParams params(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String message(Throwable error) {
        String msg = error.getMessage();
        return msg == null || msg.trim().isEmpty() ? error.getClass().getSimpleName() : msg;
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
