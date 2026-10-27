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
    private static final int REQUEST_INSTALL_DATA_PACK = 704;
    private static final long MAX_IMPORT_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_FOLDER_ENTRIES = 20_000;
    private static final int MAX_FOLDER_DEPTH = 64;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*id\\s*=\\s*\"([A-Za-z0-9_.-]+)\"\\s*(?:#.*)?$");

    private enum Kind { TEXTURE, MOD, SHADER, DATA_PACK }
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


    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private TextView status;
    private ProgressBar progress;
    private Button importFilesButton, importFolderButton;
    private File pendingDataPackInstall;
    private boolean busy;


    private File home() { return new File(getFilesDir(), "horizon"); }
    private File textureDir() { return new File(home(), "resourcepacks"); }
    private File vanillaModDir() { return new File(home(), "horizon-mods"); }
    private File dataPackDir() { return new File(home(), "horizon-datapacks"); }

    private File modDir() { return vanillaModDir(); }


    private File shaderDir() { return new File(home(), "shaderpacks"); }
    private File optionsFile() { return new File(home(), "options.txt"); }
    private File modListFile() { return new File(home(), "horizon-mods.txt"); }

    private File graphicsFile() { return new File(home(), "horizon-graphics.txt"); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        buildUi();
        refreshResources();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff090911);
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
        TextView intro = text("Organize os recursos instalados e escolha o que será carregado no Minecraft.",
                14, 0xff59647b, false);
        LinearLayout.LayoutParams introParams = params(-1, -2);
        introParams.topMargin = dp(5);
        page.addView(intro, introParams);

        // One clear primary action, with the two less frequently used actions
        // grouped side-by-side to reduce the tall stack of buttons.
        LinearLayout actions = card();
        LinearLayout.LayoutParams actionsParams = params(-1, -2);
        actionsParams.topMargin = dp(14);
        page.addView(actions, actionsParams);
        importFilesButton = button("Importar arquivos (.zip / .hmod)", true, v -> chooseFiles());
        actions.addView(importFilesButton, params(-1, dp(50)));
        importFolderButton = button("Pasta de shader", false, v -> chooseShaderFolder());
        Button refresh = button("Atualizar lista", false, v -> refreshResources());
        addButtonPair(actions, importFolderButton, refresh, 42, 6);

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams statusParams = params(-1, -2);
        statusParams.topMargin = dp(10);
        page.addView(statusCard, statusParams);
        TextView statusHeading = text("Status", 13, 0xff5363bd, true);
        statusCard.addView(statusHeading, params(-1, -2));
        status = text("Pronto.", 13, 0xff59647b, false);
        LinearLayout.LayoutParams statusTextParams = params(-1, -2);
        statusTextParams.topMargin = dp(3);
        statusCard.addView(status, statusTextParams);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        progress.setMax(100);
        LinearLayout.LayoutParams progressParams = params(-1, dp(5));
        progressParams.topMargin = dp(8);
        statusCard.addView(progress, progressParams);

        TextView note = text(
                "Texturas, mods Horizon, data packs e shaders compatíveis. As mudanças entram em vigor ao reiniciar o Minecraft.",
                12, 0xff68728a, false);
        LinearLayout.LayoutParams noteParams = params(-1, -2);
        noteParams.topMargin = dp(10);
        page.addView(note, noteParams);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentParams = params(-1, -2);
        contentParams.topMargin = dp(4);
        page.addView(content, contentParams);

        Button back = button("Voltar à tela principal", false, v -> finish());
        LinearLayout.LayoutParams backParams = params(-1, dp(46));
        backParams.topMargin = dp(12);
        page.addView(back, backParams);
    }

    private void addButtonPair(LinearLayout parent, View left, View right, int heightDp, int topMarginDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = params(-1, -2);
        rowParams.topMargin = dp(topMarginDp);
        parent.addView(row, rowParams);

        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
        leftParams.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
        rightParams.setMargins(dp(4), 0, 0, 0);
        row.addView(left, leftParams);
        row.addView(right, rightParams);
    }

    private void chooseFiles() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/octet-stream", "*/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_IMPORT_FILES); }
        catch (Exception error) { showStatus("Não foi possível abrir o seletor: " + message(error)); }
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
        if (requestCode == REQUEST_IMPORT_FILES) {
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
            if (lower.endsWith(".hmod")) {
                String id = readModId(staging);
                if (id == null) throw new IllegalArgumentException("O .hmod não contém horizon-mod.toml e mod.wasm válidos.");
                ensureDirs(modDir());
                if (modIdExists(id)) throw new IllegalArgumentException("Já existe um mod com ID '" + id + "'. Remova a versão antiga antes de importar outra.");
                File target = copyToTarget(staging, modDir(), fileName(name, ".hmod"));
                return "mod Horizon " + id + " importado desativado (" + target.getName() + ")";
            }
            if (!lower.endsWith(".zip")) {
                throw new IllegalArgumentException("Formato não suportado. Use .hmod, ZIP de textura, data pack ou shader pack.");
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
            if (isDataPackZip(staging)) {
                ensureDirs(dataPackDir());
                File target = copyToTarget(staging, dataPackDir(), fileName(name, ".zip"));
                return "pacote de dados importado; instale-o em datapacks/ de um mundo salvo (" + target.getName() + ")";
            }
            throw new IllegalArgumentException("ZIP não reconhecido. Use pacote de textura (pack.mcmeta e assets/), data pack (pack.mcmeta e data/) ou shader pack (shaders/).");
        } finally { staging.delete(); }
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
        ensureUiDir(textureDir());
        ensureUiDir(modDir());
        ensureUiDir(shaderDir());
        ensureUiDir(dataPackDir());

        List<ResourceItem> textures = new ArrayList<>();
        List<ResourceItem> mods = new ArrayList<>();
        List<ResourceItem> shaders = new ArrayList<>();
        List<ResourceItem> dataPacks = new ArrayList<>();

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
                    id == null ? "Manifest inválido; não pode ser ativado." : "ID: " + id + " · Mod Horizon."));
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
        items = dataPackDir().listFiles();
        if (items != null) for (File file : items) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
            if (isDataPackZip(file)) {
                dataPacks.add(new ResourceItem(Kind.DATA_PACK, file.getName(), file, file.getName(), false,
                        "Data pack em ZIP. Instale-o em datapacks/ de um mundo salvo."));
            }
        }
        Comparator<ResourceItem> byName = Comparator.comparing(item -> item.name.toLowerCase(Locale.ROOT));
        Collections.sort(textures, byName);
        Collections.sort(mods, byName);
        Collections.sort(shaders, byName);
        Collections.sort(dataPacks, byName);
        addSection("Pacotes de textura", "ZIPs Java com pack.mcmeta e assets/.", textures);
        addSection("Mods Horizon", "Arquivos .hmod; a ativação vale no próximo início.", mods);
        addSection("Pacotes de dados", "Selecione a pasta de um mundo salvo para copiar o pack para datapacks/.", dataPacks);
        addSection("Shader packs", "Pastas pack.toml/shaders/ e shader ZIPs Java reconhecidos.", shaders);
        if (textures.isEmpty() && mods.isEmpty() && shaders.isEmpty() && dataPacks.isEmpty()) {
            TextView empty = text("Nenhum recurso compatível instalado. Importe um arquivo ou uma pasta de shader pack.", 14, 0xff68728a, false);
            LinearLayout.LayoutParams p = params(-1, -2);
            p.topMargin = dp(8);
            content.addView(empty, p);
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
                if (pack == null || !pack.isFile() || !isDataPackZip(pack)) {
                    throw new IOException("O pacote de dados não existe ou não é válido.");
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


    private boolean isDataPackZip(File file) {
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

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(header, params(-1, -2));
        TextView heading = text(title, 16, 0xff18213a, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        String count = items.size() == 1 ? "1 item" : items.size() + " itens";
        TextView counter = text(count, 11, 0xff5363bd, true);
        counter.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(counter, params(-2, -2));

        LinearLayout.LayoutParams subParams = params(-1, -2);
        subParams.topMargin = dp(3);
        section.addView(text(subtitle, 12, 0xff68728a, false), subParams);
        if (items.isEmpty()) {
            TextView none = text("Nada instalado aqui ainda. Use Importar arquivos acima para adicionar recursos.",
                    13, 0xff68728a, false);
            LinearLayout.LayoutParams noneParams = params(-1, -2);
            noneParams.topMargin = dp(8);
            section.addView(none, noneParams);
            return;
        }
        for (ResourceItem item : items) addResourceRow(section, item);
    }

    private void addResourceRow(LinearLayout section, ResourceItem item) {
        // Resource entries use flat separators instead of a card nested inside
        // another card. This keeps names, state and actions easy to scan.
        View divider = new View(this);
        divider.setBackgroundColor(0xffe7ebf3);
        LinearLayout.LayoutParams dividerParams = params(-1, dp(1));
        dividerParams.topMargin = dp(8);
        section.addView(divider, dividerParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(2));
        section.addView(row, params(-1, -2));
        row.addView(text(item.name, 14, 0xff18213a, true), params(-1, -2));

        LinearLayout.LayoutParams detailParams = params(-1, -2);
        detailParams.topMargin = dp(2);
        row.addView(text(item.detail, 12, 0xff68728a, false), detailParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = params(-1, -2);
        actionsParams.topMargin = dp(4);
        row.addView(actions, actionsParams);

        CheckBox active = new CheckBox(this);
        active.setText(item.kind == Kind.DATA_PACK ? "Por mundo" : item.active ? "Ativo" : "Ativar");
        active.setTextSize(13);
        active.setTextColor(0xff283550);
        active.setMinHeight(dp(38));
        active.setChecked(item.kind != Kind.DATA_PACK && item.active);
        active.setEnabled(item.kind != Kind.DATA_PACK
                && (item.kind != Kind.MOD || !item.id.isEmpty()));
        active.setOnCheckedChangeListener((button, checked) -> {
            try {
                setActive(item, checked);
                button.setText(checked ? "Ativo" : "Ativar");
                showStatus(item.name + (checked ? " ativado" : " desativado")
                        + ". A mudança será aplicada quando o Minecraft iniciar novamente.");
                refreshResources();
            } catch (Exception error) {
                button.setOnCheckedChangeListener(null);
                button.setChecked(item.active);
                button.setText(item.active ? "Ativo" : "Ativar");
                button.setOnCheckedChangeListener((b, value) -> {
                    try {
                        setActive(item, value);
                        showStatus(item.name + (value ? " ativado" : " desativado") + ".");
                        refreshResources();
                    } catch (Exception e) {
                        showStatus(message(e));
                    }
                });
                showStatus("Não foi possível alterar " + item.name + ": " + message(error));
            }
        });
        actions.addView(active, new LinearLayout.LayoutParams(0, dp(40), 1f));

        if (item.kind == Kind.DATA_PACK) {
            Button install = button("Instalar", true, v -> chooseWorldForDataPack(item.path));
            install.setMinHeight(dp(38));
            install.setMinimumHeight(dp(38));
            LinearLayout.LayoutParams installParams = params(dp(78), dp(38));
            installParams.setMargins(dp(3), 0, dp(3), 0);
            actions.addView(install, installParams);
        }
        Button delete = button("Excluir", false, v -> confirmDelete(item));
        delete.setMinHeight(dp(38));
        delete.setMinimumHeight(dp(38));
        delete.setPadding(dp(5), dp(2), dp(5), dp(2));
        actions.addView(delete, params(dp(76), dp(38)));
    }

    private void setActive(ResourceItem item, boolean enabled) throws Exception {
        if (item.kind == Kind.DATA_PACK) {
            throw new IllegalArgumentException("Pacotes de dados são instalados por mundo.");
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
        new AlertDialog.Builder(this).setTitle(UiLanguage.translate(this, "Excluir recurso?"))
                .setMessage(UiLanguage.translate(this, "O arquivo ") + item.name + " será removido do armazenamento do jogo. Essa ação não pode ser desfeita.")
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Excluir"), (dialog, which) -> {
                    try {
                        if (item.kind != Kind.DATA_PACK) setActive(item, false);
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
        importFolderButton.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(value);
    }

    private void showStatus(String value) {
        if (status != null) status.setText(UiLanguage.translate(this, value == null ? "" : value));
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(UiLanguage.translate(this, value));
        view.setTextSize(size);
        if (color == 0xff17213b || color == 0xff18213a || color == 0xff26324d) {
            color = 0xfff1edff;
        } else if (color == 0xff59647b || color == 0xff68728a) {
            color = 0xffb9b3d1;
        } else if (color == 0xff5363bd) {
            color = 0xffb69aff;
        }
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff171622); bg.setCornerRadius(dp(5)); bg.setStroke(dp(1), 0xff302b43);
        view.setBackground(bg);
        return view;
    }

    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(UiLanguage.translate(this, label));
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setSingleLine(false);
        view.setMaxLines(2);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setMinHeight(dp(48));
        view.setTextColor(primary ? Color.WHITE : 0xffe7e0ff);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? 0xff6042d8 : 0xff242132);
        bg.setCornerRadius(dp(4));
        if (!primary) bg.setStroke(dp(1), 0xff413953);
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
