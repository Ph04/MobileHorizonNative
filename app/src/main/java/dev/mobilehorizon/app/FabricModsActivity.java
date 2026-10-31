package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Per-instance import/catalogue for real Fabric .jar mods used by iaxivers' Java bridge. */
public final class FabricModsActivity extends Activity {
    static final String EXTRA_INSTANCE_ID = "dev.mobilehorizon.app.FABRIC_MOD_INSTANCE_ID";
    private static final int REQUEST_IMPORT = 8107;
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String instanceId;
    private LinearLayout list;
    private TextView status;
    private ProgressBar progress;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instanceId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_INSTANCE_ID);
        if (instanceId == null || instanceId.trim().isEmpty()) {
            finish();
            return;
        }
        buildUi();
        refreshMods();
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
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

        page.addView(text("MOBILEHORIZON  /  FABRIC", 11, 0xff9e88ff, true), params(-1, -2));
        page.addView(text("Mods Fabric", 27, 0xfff0ecff, true), topParams(-1, -2, 5));
        page.addView(text("Os arquivos .jar são guardados somente nesta instância. O bridge cria links em mods-java/ e carrega Fabric Loader 0.20.1 junto com a Fabric API fixada no motor.", 13, 0xffb9b3d1, false), topParams(-1, -2, 6));

        Button back = button("Voltar", false, v -> finish());
        page.addView(back, topParams(-1, 52, 12));
        Button add = button("Importar mods .jar", true, v -> openPicker());
        page.addView(add, topParams(-1, 58, 8));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, topParams(-1, 4, 10));
        status = text("", 12, 0xffc7bce6, false);
        page.addView(status, topParams(-1, -2, 8));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, topParams(-1, -2, 6));

        LinearLayout note = card();
        note.addView(text("Compatibilidade ainda experimental", 14, 0xffffd18b, true), params(-1, -2));
        note.addView(text("O bridge suporta Fabric para Minecraft 26.3 e traduz parte do comportamento dos mods para o mundo Horizon. Nem todo mod Fabric funciona: mods que exigem GPU, renderizadores próprios, classes Java não espelhadas ou mixins fora da cobertura podem falhar. Importe apenas JARs de fontes confiáveis.", 12, 0xffc7bce6, false), topParams(-1, -2, 5));
        page.addView(note, topParams(-1, -2, 12));
    }

    private void openPicker() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/java-archive");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> selected = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData clips = data.getClipData();
            for (int i = 0; i < clips.getItemCount(); i++) {
                Uri uri = clips.getItemAt(i).getUri();
                if (uri != null) selected.add(uri);
            }
        } else if (data.getData() != null) {
            selected.add(data.getData());
        }
        if (selected.isEmpty()) return;

        busy = true;
        setBusy(true);
        status.setText("Validando os mods Fabric selecionados…");
        io.execute(() -> {
            int imported = 0;
            Exception failure = null;
            try {
                for (Uri uri : selected) {
                    importMod(uri);
                    imported++;
                }
            } catch (Exception error) {
                failure = error;
            }
            final int count = imported;
            final Exception error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                setBusy(false);
                status.setText(error == null
                        ? "Mods importados: " + count
                        : "Importados " + count + "; falha: " + safeMessage(error));
                refreshMods();
            });
        });
    }

    private void importMod(Uri uri) throws Exception {
        String displayName = displayName(uri);
        String safeName = safeJarName(displayName);
        File home = GameInstanceManager.getInstanceHome(this, instanceId).getCanonicalFile();
        File mods = safeDirectory(home, "mods");
        File target = new File(mods, safeName);
        if (Files.isSymbolicLink(target.toPath())) throw new IOException("O destino do mod usa link simbólico.");
        File temporary = new File(mods, "." + safeName + ".importing");
        if (target.exists()) throw new IOException("Já existe um mod com o nome " + safeName + ".");
        if (temporary.exists()) temporary.delete();

        long total = 0;
        try (InputStream input = new BufferedInputStream(getContentResolver().openInputStream(uri));
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            if (input == null) throw new IOException("Não foi possível abrir o arquivo selecionado.");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > FabricModManager.MAX_MOD_JAR_BYTES) {
                    throw new IOException("O mod ultrapassa o limite de 256 MiB: " + safeName);
                }
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            throw error;
        }

        try {
            JSONObject descriptor = readFabricDescriptor(temporary);
            String modId = descriptor.optString("id", "");
            if (!modId.matches("[a-z0-9_-]{1,64}")) {
                throw new IOException("O fabric.mod.json contém um ID de mod inválido.");
            }
            if (!temporary.renameTo(target)) {
                throw new IOException("Não foi possível ativar o mod " + safeName + ".");
            }
        } catch (Exception error) {
            temporary.delete();
            throw error;
        }
    }

    private JSONObject readFabricDescriptor(File jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null || entry.getSize() <= 0 || entry.getSize() > MAX_DESCRIPTOR_BYTES) {
                throw new IOException("O arquivo não contém um fabric.mod.json válido.");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = new byte[(int) entry.getSize()];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) throw new IOException("O fabric.mod.json está incompleto.");
                    offset += count;
                }
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            }
        }
    }

    private void refreshMods() {
        if (list == null || isFinishing() || isDestroyed()) return;
        list.removeAllViews();
        try {
            File home = GameInstanceManager.getInstanceHome(this, instanceId).getCanonicalFile();
            File mods = safeDirectory(home, "mods");
            File[] files = mods.listFiles();
            int count = 0;
            if (files != null) {
                for (File file : files) {
                    if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                    list.addView(modCard(file), topParams(-1, -2, 8));
                    count++;
                }
            }
            status.setText("Mods instalados nesta instância: " + count);
            if (count == 0) {
                list.addView(text("Nenhum mod Fabric instalado. Importe arquivos .jar para começar.", 13, 0xffb9b3d1, false), params(-1, -2));
            }
        } catch (Exception error) {
            status.setText("Não foi possível listar os mods: " + safeMessage(error));
        }
    }

    private View modCard(File jar) {
        LinearLayout card = card();
        String title = jar.getName();
        String details = "Arquivo JAR • " + human(jar.length());
        try {
            JSONObject descriptor = readFabricDescriptor(jar);
            title = descriptor.optString("name", descriptor.optString("id", jar.getName()));
            details = descriptor.optString("id", "mod") + " "
                    + descriptor.optString("version", "versão desconhecida")
                    + " • " + human(jar.length());
        } catch (Exception ignored) {
            // Preserve the entry in the list so it can be removed after an interrupted import.
        }
        card.addView(text(title, 15, 0xfff0ecff, true), params(-1, -2));
        card.addView(text(details, 11, 0xffaaa2c6, false), topParams(-1, -2, 4));
        Button remove = button("Remover", false, v -> confirmRemove(jar));
        card.addView(remove, topParams(-1, 42, 8));
        return card;
    }

    private void confirmRemove(File jar) {
        new AlertDialog.Builder(this)
                .setTitle("Remover mod Fabric?")
                .setMessage("O arquivo " + jar.getName() + " será removido apenas desta instância.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Remover", (dialog, which) -> {
                    if (Files.isSymbolicLink(jar.toPath()) || !jar.isFile() || !jar.delete()) {
                        status.setText("Não foi possível remover o arquivo selecionado.");
                    } else {
                        status.setText("Mod removido.");
                    }
                    refreshMods();
                }).show();
    }

    private String displayName(Uri uri) throws IOException {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        }
        throw new IOException("O provedor de arquivos não informou o nome do mod.");
    }

    private String safeJarName(String value) throws IOException {
        if (value == null || !value.toLowerCase(Locale.ROOT).endsWith(".jar")
                || value.length() > 180 || !value.matches("[A-Za-z0-9._+() -]+\\.jar")) {
            throw new IOException("Selecione um arquivo .jar com nome simples e válido.");
        }
        return value;
    }

    private File safeDirectory(File root, String name) throws IOException {
        File target = new File(root, name);
        if (Files.isSymbolicLink(target.toPath())) throw new IOException("A pasta " + name + " usa link simbólico.");
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Não foi possível criar a pasta " + name + ".");
        File canonical = target.getCanonicalFile();
        if (!root.equals(canonical.getParentFile())) throw new IOException("Pasta fora do diretório privado da instância.");
        return canonical;
    }

    private void setBusy(boolean value) {
        if (progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private String human(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff151321);
        background.setCornerRadius(dp(7));
        background.setStroke(dp(1), 0xff37304f);
        card.setBackground(background);
        return card;
    }

    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(UiLanguage.translate(this, label));
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? 0xff5b3fd6 : 0xff2b2641);
        background.setCornerRadius(dp(5));
        background.setStroke(dp(1), primary ? 0xff8a74ff : 0xff3d3560);
        button.setBackground(background);
        button.setTextColor(Color.WHITE);
        button.setOnClickListener(action);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(UiLanguage.translate(this, value));
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        return view;
    }

    private LinearLayout.LayoutParams topParams(int width, int height, int marginDp) {
        LinearLayout.LayoutParams params = params(width, height);
        params.topMargin = dp(marginDp);
        return params;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
