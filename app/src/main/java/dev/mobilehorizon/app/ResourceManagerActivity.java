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
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Manages only resource formats supported by the bundled Horizon runtime. */
public final class ResourceManagerActivity extends Activity {
    private static final int REQUEST_IMPORT_FILES = 701;
    private static final int REQUEST_IMPORT_SHADER_FOLDER = 702;
    private static final int REQUEST_IMPORT_FABRIC_JAR = 703;
    private static final long MAX_IMPORT_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_FOLDER_ENTRIES = 20_000;
    private static final int MAX_FOLDER_DEPTH = 64;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*id\\s*=\\s*\"([A-Za-z0-9_.-]+)\"\\s*(?:#.*)?$");

    private enum Kind { TEXTURE, MOD, SHADER, FABRIC_JAR }
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
        final String id, name, version;
        final int classes, resources;
        final boolean hasMixins, hasAccessWidener;
        FabricJarInfo(String id, String name, String version, int classes, int resources,
                      boolean hasMixins, boolean hasAccessWidener) {
            this.id = id; this.name = name; this.version = version;
            this.classes = classes; this.resources = resources;
            this.hasMixins = hasMixins; this.hasAccessWidener = hasAccessWidener;
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
    private boolean busy;

    private boolean fabricBridgeEnabled() {
        return getSharedPreferences("horizon_fabric_bridge", MODE_PRIVATE).getBoolean("enabled", false);
    }
    private File home() { return new File(getFilesDir(), "horizon"); }
    private File textureDir() { return new File(home(), "resourcepacks"); }
    private File modDir() { return new File(home(), fabricBridgeEnabled()
            ? "horizon-fabric-bridge-mods" : "horizon-mods"); }
    private File fabricInboxDir() { return new File(home(), "horizon-fabric-bridge-inbox"); }
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
        importFilesButton = button("Importar recurso que ainda não tenho (.zip / .hmod)", true, v -> chooseFiles());
        actions.addView(importFilesButton, params(-1, 48));
        importFabricJarButton = button("Importar mod Fabric (.jar) para análise", false, v -> chooseFabricJars());
        LinearLayout.LayoutParams fabricImportParams = params(-1, 46);
        fabricImportParams.topMargin = dp(6);
        actions.addView(importFabricJarButton, fabricImportParams);
        importFolderButton = button("Importar pasta de shader pack", false, v -> chooseShaderFolder());
        LinearLayout.LayoutParams folderParams = params(-1, 46);
        folderParams.topMargin = dp(6);
        actions.addView(importFolderButton, folderParams);
        Button refresh = button("Atualizar lista", false, v -> refreshResources());
        LinearLayout.LayoutParams refreshParams = params(-1, 44);
        refreshParams.topMargin = dp(6);
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
                "As alterações são aplicadas ao abrir o Minecraft novamente. Mods Fabric/Forge/NeoForge em .jar não são compatíveis: use .hmod do Horizon. Pacotes de textura precisam de ZIP Java com pack.mcmeta e assets/; shader packs compatíveis podem ser ZIPs com shaders/ ou pastas com pack.toml/shaders/.",
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
        LinearLayout.LayoutParams backParams = params(-1, 46);
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
                if (fabricJarIdExists(info.id)) {
                    throw new IllegalArgumentException("Já existe um JAR Fabric importado com ID '" + info.id + "'. Exclua a versão antiga antes de importar outra.");
                }
                File target = copyToTarget(staging, fabricInboxDir(), fileName(name, ".jar"));
                return "mod Fabric " + info.name + " (" + info.id + " " + info.version
                        + ") guardado para análise/conversão; não foi executado (" + target.getName() + ")";
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
            int classes = 0, resources = 0;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                if (path.endsWith(".class")) classes++;
                if (path.startsWith("assets/") || path.startsWith("data/")) resources++;
            }
            Object mixins = json.opt("mixins");
            boolean hasMixins = mixins != null && mixins != JSONObject.NULL
                    && (!(mixins instanceof JSONArray) || ((JSONArray) mixins).length() > 0);
            boolean hasAccessWidener = !json.optString("accessWidener", "").trim().isEmpty();
            return new FabricJarInfo(id, name, version, classes, resources, hasMixins, hasAccessWidener);
        } catch (IOException error) {
            throw new IllegalArgumentException("O arquivo não é um JAR/ZIP válido.");
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
            ZipEntry wasm = zip.getEntry("mod.wasm");
            if (manifest == null || wasm == null || wasm.isDirectory()) return null;
            try (InputStream input = zip.getInputStream(manifest)) {
                String text = new String(readLimitedBytes(input, 64 * 1024), StandardCharsets.UTF_8);
                Matcher matcher = MOD_ID.matcher(text);
                return matcher.find() ? matcher.group(1) : null;
            }
        } catch (IOException error) { return null; }
    }

    private boolean modIdExists(String id) {
        File[] files = modDir().listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".hmod"));
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
        ensureUiDir(textureDir()); ensureUiDir(modDir()); ensureUiDir(shaderDir()); ensureUiDir(fabricInboxDir());
        List<ResourceItem> textures = new ArrayList<>(), mods = new ArrayList<>(),
                shaders = new ArrayList<>(), fabricJars = new ArrayList<>();

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
                String details = "ID: " + info.id + " · versão " + info.version
                        + " · " + info.classes + " classes · " + info.resources + " recursos"
                        + (info.hasMixins ? " · Mixins" : "")
                        + (info.hasAccessWidener ? " · Access Widener" : "")
                        + " · pendente de conversão; não executado.";
                fabricJars.add(new ResourceItem(Kind.FABRIC_JAR, file.getName(), file,
                        info.id, false, details));
            } catch (Exception error) {
                fabricJars.add(new ResourceItem(Kind.FABRIC_JAR, file.getName(), file,
                        "", false, "JAR importado, mas sua análise falhou: " + message(error)));
            }
        }

        Comparator<ResourceItem> byName = Comparator.comparing(item -> item.name.toLowerCase(Locale.ROOT));
        Collections.sort(textures, byName); Collections.sort(mods, byName);
        Collections.sort(shaders, byName); Collections.sort(fabricJars, byName);
        addSection("Pacotes de textura", "ZIPs Java com pack.mcmeta e assets/.", textures);
        addSection(fabricBridgeEnabled() ? "Mods Horizon — perfil Fabric Bridge" : "Mods Horizon — perfil Vanilla",
                "Arquivos .hmod separados por perfil; a ativação vale no próximo início.", mods);
        addSection("Mods Fabric importados — aguardando conversão",
                "JARs são inspecionados sem extração/execução. Ainda não são mods jogáveis no Horizon.", fabricJars);
        addSection("Shader packs", "Pastas pack.toml/shaders/ e shader ZIPs Java reconhecidos.", shaders);
        if (textures.isEmpty() && mods.isEmpty() && shaders.isEmpty() && fabricJars.isEmpty()) {
            TextView empty = text("Nenhum recurso compatível instalado. Importe um arquivo ou uma pasta de shader pack.", 14, 0xff68728a, false);
            LinearLayout.LayoutParams p = params(-1, -2); p.topMargin = dp(8); content.addView(empty, p);
        }
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
        active.setText(item.kind == Kind.FABRIC_JAR ? "Aguardando conversão"
                : item.active ? "Ativo" : "Desativado");
        active.setTextColor(0xff283550);
        active.setChecked(item.kind != Kind.FABRIC_JAR && item.active);
        active.setEnabled(item.kind != Kind.FABRIC_JAR
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
        actions.addView(button("Excluir", false, v -> confirmDelete(item)),
                new LinearLayout.LayoutParams(-2, dp(42)));
    }

    private void setActive(ResourceItem item, boolean enabled) throws Exception {
        if (item.kind == Kind.FABRIC_JAR) {
            throw new IllegalArgumentException("JARs Fabric aguardam conversão e não podem ser ativados diretamente.");
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
                        if (item.kind != Kind.FABRIC_JAR) setActive(item, false);
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
        view.setText(label); view.setTextSize(14); view.setAllCaps(false);
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
