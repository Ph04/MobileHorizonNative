package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installed vanilla resource packs and Horizon shader packs for the selected instance. */
public final class InstalledPacksActivity extends Activity {
    private static final int PICK_PACK = 601;
    private LinearLayout resources;
    private LinearLayout shaders;
    private TextView status;
    private ProgressBar progress;

    private static final class Pack {
        final File file;
        final boolean shader;
        final String title;
        final String description;
        Pack(File file, boolean shader, String title, String description) {
            this.file = file; this.shader = shader; this.title = title; this.description = description;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        buildUi();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff090911);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(26));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("MOBILEHORIZON  /  PACOTES", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("Pacotes instalados", 26, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text("Ative, desative ou remova resource packs e shaders da instância selecionada. As opções são gravadas nos mesmos arquivos que o jogo lê.", 12, 0xffb9b3d1, false), top(-1, -2, 6));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 48, 10));

        LinearLayout actions = card();
        actions.addView(button("Adicionar pacote (.zip / .hshader)", true, v -> choosePack()), lp(-1, 50));
        Button refresh = button("Atualizar lista", false, v -> refresh());
        actions.addView(refresh, top(-1, 44, 7));
        page.addView(actions, top(-1, -2, 10));

        status = text("Lendo pacotes instalados…", 11, 0xffc7bce6, false);
        page.addView(status, top(-1, -2, 8));
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, top(-1, 4, 5));

        LinearLayout resourceCard = card();
        resourceCard.addView(text("Resource packs", 16, 0xfff0ecff, true), lp(-1, -2));
        resourceCard.addView(text("A lista segue options.txt. Pacotes ativados recentemente ficam no topo da pilha do jogo.", 11, 0xffaaa2c6, false), top(-1, -2, 4));
        resources = new LinearLayout(this);
        resources.setOrientation(LinearLayout.VERTICAL);
        resourceCard.addView(resources, top(-1, -2, 7));
        page.addView(resourceCard, top(-1, -2, 10));

        LinearLayout shaderCard = card();
        shaderCard.addView(text("Shaders", 16, 0xfff0ecff, true), lp(-1, -2));
        shaderCard.addView(text("Somente um shader pode ficar ativo por vez. O Horizon também aceita pacotes .hshader.", 11, 0xffaaa2c6, false), top(-1, -2, 4));
        shaders = new LinearLayout(this);
        shaders.setOrientation(LinearLayout.VERTICAL);
        shaderCard.addView(shaders, top(-1, -2, 7));
        page.addView(shaderCard, top(-1, -2, 10));

        page.addView(text("As alterações entram em vigor quando o Minecraft recarregar os recursos ou for iniciado novamente. Este painel não instala mods nem carregadores Fabric/Forge.", 11, 0xffaaa2c6, false), top(-1, -2, 10));
    }

    private void refresh() {
        if (resources == null || isFinishing() || isDestroyed()) return;
        resources.removeAllViews();
        shaders.removeAllViews();
        try {
            File home = GameInstanceManager.getSelectedInstanceHome(this);
            List<Pack> resourcePacks = scan(new File(home, "resourcepacks"), false);
            List<Pack> shaderPacks = scan(new File(home, "shaderpacks"), true);
            JSONArray enabled = enabledResources(new File(home, "options.txt"));
            String selectedShader = graphicsValue(new File(home, "horizon-graphics.txt"), "pickedShaderPack");
            for (Pack pack : resourcePacks) {
                boolean active = enabled.optStringListContains("file/" + pack.file.getName());
                resources.addView(packRow(pack, active), top(-1, -2, 5));
            }
            for (Pack pack : shaderPacks) {
                boolean active = pack.file.getName().equals(selectedShader);
                shaders.addView(packRow(pack, active), top(-1, -2, 5));
            }
            if (resourcePacks.isEmpty()) resources.addView(text("Nenhum resource pack instalado nesta instância.", 12, 0xffaaa2c6, false), lp(-1, -2));
            if (shaderPacks.isEmpty()) shaders.addView(text("Nenhum shader instalado nesta instância.", 12, 0xffaaa2c6, false), lp(-1, -2));
            status.setText(UiLanguage.translate(this, "Pacotes: ")
                    + resourcePacks.size() + " resource packs • " + shaderPacks.size() + " shaders");
        } catch (Exception error) {
            status.setText(UiLanguage.translate(this, "Não foi possível ler os pacotes: ") + message(error));
        }
    }

    private List<Pack> scan(File folder, boolean shader) throws Exception {
        if (!folder.isDirectory()) return Collections.emptyList();
        File[] files = folder.listFiles();
        if (files == null) throw new java.io.IOException("Diretório ilegível: " + folder.getName());
        ArrayList<Pack> result = new ArrayList<>();
        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath()) || file.getName().startsWith(".")) continue;
            if (!file.isFile() && !file.isDirectory()) continue;
            String title = file.getName();
            String description = shader ? "Pacote de shader" : "Pacote de recursos";
            if (shader) {
                if (!isShader(file)) continue;
                if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".hshader")) {
                    description = "Shader nativo Horizon (.hshader)";
                } else if (file.isDirectory() && new File(file, "horizon-shaders.toml").isFile()) {
                    description = readShaderTitle(file, title);
                } else {
                    description = "Shader no formato shaders/";
                }
            } else {
                if (!isResourcePack(file)) continue;
                description = readPackDescription(file);
            }
            result.add(new Pack(file, shader, title, description));
        }
        result.sort(Comparator.comparing(p -> p.title.toLowerCase(Locale.ROOT)));
        return result;
    }

    private boolean isResourcePack(File file) {
        if (file.isDirectory()) return new File(file, "pack.mcmeta").isFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) return false;
        try (ZipFile zip = new ZipFile(file)) { return zip.getEntry("pack.mcmeta") != null; }
        catch (Exception ignored) { return false; }
    }

    private boolean isShader(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (file.isFile() && name.endsWith(".hshader")) return true;
        if (file.isDirectory()) {
            return new File(file, "horizon-shaders.toml").isFile()
                    || new File(file, "shaders").isDirectory();
        }
        if (!name.endsWith(".zip")) return false;
        try (ZipFile zip = new ZipFile(file)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String path = entries.nextElement().getName();
                if (path.startsWith("shaders/")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String readPackDescription(File file) {
        try {
            byte[] bytes;
            if (file.isDirectory()) {
                File metadata = new File(file, "pack.mcmeta");
                if (metadata.length() > 256 * 1024) return "Resource pack";
                bytes = Files.readAllBytes(metadata.toPath());
            } else {
                try (ZipFile zip = new ZipFile(file)) {
                    ZipEntry entry = zip.getEntry("pack.mcmeta");
                    if (entry == null || entry.getSize() > 256 * 1024) return "Resource pack";
                    try (InputStream input = zip.getInputStream(entry)) {
                        bytes = readLimited(input, 256 * 1024);
                    }
                }
            }
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONObject pack = root.optJSONObject("pack");
            Object description = pack == null ? null : pack.opt("description");
            return description == null ? "Resource pack" : description.toString();
        } catch (Exception ignored) { return "Resource pack"; }
    }

    private String readShaderTitle(File file, String fallback) {
        try {
            File manifest = new File(file, "horizon-shaders.toml");
            if (manifest.length() > 128 * 1024) return fallback;
            String text = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
            for (String line : text.split("\\r?\\n")) {
                String value = line.trim();
                if (value.startsWith("name")) {
                    int eq = value.indexOf('=');
                    if (eq >= 0) return value.substring(eq + 1).trim().replace("\"", "");
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private View packRow(Pack pack, boolean active) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        info.addView(text(pack.title, 13, 0xffeee9ff, true), lp(-1, -2));
        info.addView(text(pack.description, 10, 0xffaaa2c6, false), top(-1, -2, 2));
        CheckBox enabled = new CheckBox(this);
        enabled.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff9e88ff));
        enabled.setText(UiLanguage.translate(this, active ? "Ativo" : "Ativar"));
        enabled.setTextColor(0xffb9b3d1);
        enabled.setTextSize(10);
        enabled.setChecked(active);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            try {
                setEnabled(pack, checked);
                refresh();
            } catch (Exception error) {
                button.setChecked(!checked);
                showError("Não foi possível alterar o pacote", error);
            }
        });
        row.addView(enabled, lp(-2, 48));
        Button remove = button("Remover", false, v -> confirmRemove(pack));
        row.addView(remove, lp(dp(88), 42));
        return row;
    }

    private void setEnabled(Pack pack, boolean active) throws Exception {
        File home = GameInstanceManager.getSelectedInstanceHome(this);
        if (pack.shader) {
            File graphics = new File(home, "horizon-graphics.txt");
            setKeyLine(graphics, "pickedShaderPack", active ? pack.file.getName() : "");
            return;
        }
        File options = new File(home, "options.txt");
        String line = readKeyLine(options, "resourcePacks");
        JSONArray list;
        try { list = line == null ? new JSONArray() : new JSONArray(line); }
        catch (Exception error) { throw new java.io.IOException("resourcePacks em options.txt está inválido.", error); }
        String id = "file/" + pack.file.getName();
        JSONArray updated = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            String existing = list.optString(i, "");
            if (!id.equals(existing)) updated.put(existing);
        }
        if (updated.length() == 0) updated.put("vanilla");
        if (active) updated.put(id);
        setKeyLine(options, "resourcePacks", updated.toString());
    }

    private void confirmRemove(Pack pack) {
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Remover pacote?"))
                .setMessage(pack.title + "\n\n" + UiLanguage.translate(this,
                        "O arquivo será removido da pasta desta instância."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Remover"), (dialog, which) -> {
                    try {
                        if (pack.shader) {
                            String selected = graphicsValue(new File(
                                    GameInstanceManager.getSelectedInstanceHome(this),
                                    "horizon-graphics.txt"), "pickedShaderPack");
                            if (pack.file.getName().equals(selected)) {
                                setEnabled(pack, false);
                            }
                        } else {
                            setEnabled(pack, false);
                        }
                        File root = pack.file.getParentFile().getCanonicalFile();
                        File target = pack.file.getCanonicalFile();
                        if (!root.equals(target.getParentFile())
                                || Files.isSymbolicLink(pack.file.toPath())) {
                            throw new java.io.IOException("Caminho de pacote inválido.");
                        }
                        deleteTree(pack.file);
                        status.setText(UiLanguage.translate(this, "Pacote removido: ") + pack.title);
                        refresh();
                    } catch (Exception error) { showError("Falha ao remover pacote", error); }
                }).show();
    }

    private void choosePack() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_PACK); }
        catch (Exception error) { showError("Não foi possível abrir o seletor", error); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PACK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        setBusy(true);
        new Thread(() -> {
            Exception failure = null;
            String installed = "";
            try {
                String name = displayName(uri);
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".zip") || lower.endsWith(".hshader"))) {
                    throw new IllegalArgumentException("Escolha um .zip ou .hshader.");
                }
                File stage = new File(getCacheDir(), "pack-import-" + System.currentTimeMillis());
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(stage, false)) {
                    if (input == null) throw new java.io.IOException("Arquivo de origem indisponível.");
                    byte[] buffer = new byte[32768];
                    long total = 0; int n;
                    while ((n = input.read(buffer)) != -1) {
                        total += n;
                        if (total > 220L * 1024L * 1024L) throw new java.io.IOException("Pacote maior que 220 MiB.");
                        output.write(buffer, 0, n);
                    }
                    output.flush();
                }
                boolean shader = lower.endsWith(".hshader") || isShader(stage);
                boolean resource = isResourcePack(stage);
                if (!shader && !resource) throw new IllegalArgumentException(
                        "O ZIP não contém pack.mcmeta nem uma pasta shaders/ reconhecida.");
                if (shader && lower.endsWith(".hshader") && !lower.endsWith(".hshader")) {
                    throw new IllegalArgumentException("Extensão de shader inválida.");
                }
                File home = GameInstanceManager.getSelectedInstanceHome(this);
                File folder = new File(home, shader ? "shaderpacks" : "resourcepacks");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("Não foi possível criar a pasta do pacote.");
                String safe = safeFileName(name);
                File target = new File(folder, safe).getCanonicalFile();
                if (!folder.getCanonicalFile().equals(target.getParentFile())) throw new java.io.IOException("Nome de pacote inválido.");
                int index = 2;
                String base = safe;
                while (target.exists()) {
                    int dot = safe.lastIndexOf('.');
                    String stem = dot > 0 ? safe.substring(0, dot) : safe;
                    String ext = dot > 0 ? safe.substring(dot) : "";
                    target = new File(folder, stem + " (" + index++ + ")" + ext).getCanonicalFile();
                }
                if (!stage.renameTo(target)) throw new java.io.IOException("Não foi possível instalar o pacote.");
                installed = target.getName();
            } catch (Exception error) { failure = error; }
            final Exception problem = failure;
            final String done = installed;
            runOnUiThread(() -> {
                setBusy(false);
                if (problem != null) showError("Não foi possível adicionar o pacote", problem);
                else status.setText(UiLanguage.translate(this, "Pacote instalado: ") + done
                        + UiLanguage.translate(this, ". Ele começa desativado."));
                refresh();
            });
        }).start();
    }

    private String displayName(Uri uri) {
        String name = "pack.zip";
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception ignored) {}
        return name == null ? "pack.zip" : name;
    }

    private String safeFileName(String value) throws Exception {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 140 || name.contains("/") || name.contains("\\")
                || name.contains("..") || name.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Nome de arquivo de pacote inválido.");
        }
        return name;
    }

    private JSONArray enabledResources(File file) throws Exception {
        String line = readKeyLine(file, "resourcePacks");
        return line == null ? new JSONArray() : new JSONArray(line);
    }

    private String graphicsValue(File file, String key) throws Exception {
        String[] lines = file.isFile()
                ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\\r?\\n")
                : new String[0];
        for (String line : lines) if (line.startsWith(key + ":")) return line.substring(key.length() + 1);
        return "";
    }

    private String readKeyLine(File file, String key) throws Exception {
        if (!file.isFile()) return null;
        String[] lines = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\\r?\\n");
        for (String line : lines) if (line.startsWith(key + ":")) return line.substring(key.length() + 1);
        return null;
    }

    private void setKeyLine(File file, String key, String value) throws Exception {
        ArrayList<String> lines = new ArrayList<>();
        boolean found = false;
        if (file.isFile()) {
            String[] current = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\\r?\\n");
            for (String line : current) {
                if (line.startsWith(key + ":")) {
                    if (!found) { lines.add(key + ":" + value); found = true; }
                } else lines.add(line);
            }
        }
        if (!found) lines.add(key + ":" + value);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Não foi possível criar a pasta das opções.");
        File temporary = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(file)) {
            if (file.exists() && !file.delete()) throw new java.io.IOException("Não foi possível atualizar " + key);
            if (!temporary.renameTo(file)) throw new java.io.IOException("Não foi possível finalizar " + key);
        }
    }

    private void deleteTree(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath())) throw new java.io.IOException("Link simbólico recusado.");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new java.io.IOException("Pasta ilegível.");
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new java.io.IOException("Não foi possível remover " + file.getName());
    }

    private byte[] readLimited(InputStream input, int max) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > max) throw new java.io.IOException("Arquivo excede o limite permitido.");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void showError(String title, Exception error) {
        new AlertDialog.Builder(this).setTitle(UiLanguage.translate(this, title))
                .setMessage(message(error))
                .setPositiveButton(UiLanguage.translate(this, "Fechar"), null).show();
    }

    private String message(Exception error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff151321); bg.setCornerRadius(dp(7)); bg.setStroke(dp(1), 0xff37304f);
        card.setBackground(bg);
        return card;
    }

    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(UiLanguage.translate(this, label)); b.setTextSize(12);
        b.setTextColor(Color.WHITE); b.setMinHeight(0); b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? 0xff5b3fd6 : 0xff2b2641);
        bg.setCornerRadius(dp(5)); bg.setStroke(dp(1), primary ? 0xff8a74ff : 0xff3d3560);
        b.setBackground(bg); b.setOnClickListener(action);
        return b;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(UiLanguage.translate(this, value)); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        return t;
    }

    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private LinearLayout.LayoutParams top(int width, int height, int margin) {
        LinearLayout.LayoutParams p = lp(width, height); p.topMargin = dp(margin); return p;
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
