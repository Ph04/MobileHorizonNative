package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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

/** Per-instance Fabric mod manager with explicit enable/disable controls and consistent launcher styling. */
public final class FabricModsActivity extends Activity {
    static final String EXTRA_INSTANCE_ID = "dev.mobilehorizon.app.FABRIC_MOD_INSTANCE_ID";
    private static final int REQUEST_IMPORT = 8107;
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String instanceId;
    private LinearLayout list;
    private TextView status;
    private TextView activeCount;
    private TextView disabledCount;
    private ProgressBar progress;
    private Button importButton;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instanceId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_INSTANCE_ID);
        if (instanceId == null || instanceId.trim().isEmpty()) {
            finish();
            return;
        }
        LauncherButtonStyle.install(this);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        buildUi();
        refreshMods();
    }

    @Override protected void onResume() {
        super.onResume();
        LauncherButtonStyle.install(this);
        refreshMods();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff090911);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("MOBILEHORIZON  /  Java Bridge By:iaxivers", 11, 0xff9e88ff, true),
                params(-1, -2));
        page.addView(text("Gerenciador de mods", 25, 0xfff0ecff, true),
                topParams(-1, -2, 5));
        page.addView(text(
                "Ative ou desative cada mod para esta instância. Mods desativados ficam guardados e não são carregados pelo jogo.",
                13, 0xffb9b3d1, false), topParams(-1, -2, 6));

        Button back = button("Voltar", false, 42, v -> finish());
        page.addView(back, topParams(-1, 42, 12));
        importButton = button("＋  Importar mods .jar", true, 52, v -> openPicker());
        page.addView(importButton, topParams(-1, 52, 8));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, topParams(-1, 4, 10));

        status = text("", 12, 0xffc7bce6, false);
        page.addView(status, topParams(-1, -2, 10));

        LinearLayout counters = new LinearLayout(this);
        counters.setOrientation(LinearLayout.HORIZONTAL);
        counters.setGravity(Gravity.CENTER_VERTICAL);
        counters.setPadding(0, dp(5), 0, dp(2));
        activeCount = text("Ativos: 0", 12, 0xff9be0b0, true);
        disabledCount = text("Desativados: 0", 12, 0xffffcb83, true);
        counters.addView(activeCount, new LinearLayout.LayoutParams(0, -2, 1f));
        counters.addView(disabledCount, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(counters, topParams(-1, -2, 5));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, topParams(-1, -2, 7));

        LinearLayout note = card();
        note.addView(text("Como funciona", 14, 0xffeee9ff, true),
                params(-1, -2));
        note.addView(text(
                "O Fabric Loader 0.20.1 e a Fabric API 0.165.0+26.3 já acompanham o runtime experimental. Aqui você gerencia somente os mods adicionais. Alterações entram em vigor na próxima inicialização do jogo.",
                12, 0xffb9b3d1, false), topParams(-1, -2, 5));
        page.addView(note, topParams(-1, -2, 12));

        LinearLayout warning = card();
        warning.addView(text("Compatibilidade experimental", 14, 0xffffd18b, true),
                params(-1, -2));
        warning.addView(text(
                "Nem todo mod Fabric funciona neste bridge. Mods que exigem renderizadores próprios, APIs não mapeadas ou mixins fora da cobertura podem falhar. Importe apenas arquivos de fontes confiáveis.",
                12, 0xffc7bce6, false), topParams(-1, -2, 5));
        page.addView(warning, topParams(-1, -2, 9));
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
        status.setText(UiLanguage.translate(this, "Validando os mods Fabric selecionados…"));
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
                        ? UiLanguage.translate(this, "Mods importados e ativados: ") + count
                        : UiLanguage.translate(this, "Importados ") + count + "; "
                            + UiLanguage.translate(this, "falha: ") + safeMessage(error));
                refreshMods();
            });
        });
    }

    private void importMod(Uri uri) throws Exception {
        String safeName = safeJarName(displayName(uri));
        File mods = FabricModManager.getDirectory(this, instanceId, true);
        File disabledMods = FabricModManager.getDirectory(this, instanceId, false);
        File target = new File(mods, safeName);
        File disabledCopy = new File(disabledMods, safeName);
        if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(disabledCopy.toPath())) {
            throw new IOException("O destino do mod usa link simbólico.");
        }
        if (target.exists() || disabledCopy.exists()) {
            throw new IOException("Este mod já foi importado nesta instância: " + safeName);
        }
        File temporary = new File(mods, "." + safeName + ".importing");
        if (target.exists()) throw new IOException("Já existe um mod com o nome " + safeName + ".");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Não foi possível limpar a importação anterior de " + safeName + ".");
        }

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
            File enabled = FabricModManager.getDirectory(this, instanceId, true);
            File disabled = FabricModManager.getDirectory(this, instanceId, false);
            List<File> activeFiles = listJars(enabled);
            List<File> disabledFiles = listJars(disabled);

            activeCount.setText(UiLanguage.translate(this, "Ativos: ") + activeFiles.size());
            disabledCount.setText(UiLanguage.translate(this, "Desativados: ") + disabledFiles.size());
            status.setText(UiLanguage.translate(this, "Total de mods nesta instância: ")
                    + (activeFiles.size() + disabledFiles.size()));

            if (activeFiles.isEmpty() && disabledFiles.isEmpty()) {
                list.addView(text(
                        "Nenhum mod instalado. Use Importar mods .jar para adicionar arquivos Fabric.",
                        13, 0xffb9b3d1, false), params(-1, -2));
                return;
            }

            if (!activeFiles.isEmpty()) {
                list.addView(sectionTitle("MODS ATIVOS", 0xff9be0b0), topParams(-1, -2, 2));
                for (File jar : activeFiles) {
                    list.addView(modCard(jar, true), topParams(-1, -2, 7));
                }
            }
            if (!disabledFiles.isEmpty()) {
                list.addView(sectionTitle("MODS DESATIVADOS", 0xffffcb83),
                        topParams(-1, -2, activeFiles.isEmpty() ? 2 : 13));
                for (File jar : disabledFiles) {
                    list.addView(modCard(jar, false), topParams(-1, -2, 7));
                }
            }
        } catch (Exception error) {
            status.setText(UiLanguage.translate(this, "Não foi possível listar os mods: ")
                    + safeMessage(error));
        }
    }

    private List<File> listJars(File directory) throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Não foi possível listar " + directory.getName() + ".");
        List<File> jars = new ArrayList<>();
        for (File file : entries) {
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile()
                    || file.length() <= 0 || file.length() > FabricModManager.MAX_MOD_JAR_BYTES) {
                continue;
            }
            if (!directory.equals(file.getCanonicalFile().getParentFile())) continue;
            jars.add(file);
        }
        jars.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        return jars;
    }

    private View modCard(File jar, boolean enabled) {
        LinearLayout card = card();
        String title = jar.getName();
        String details = UiLanguage.translate(this, "Arquivo JAR") + " • " + human(jar.length());
        try {
            JSONObject descriptor = readFabricDescriptor(jar);
            title = descriptor.optString("name", descriptor.optString("id", jar.getName()));
            details = descriptor.optString("id", "mod") + " "
                    + descriptor.optString("version", "versão desconhecida")
                    + " • " + human(jar.length());
        } catch (Exception ignored) {
            // Keep invalid older entries visible so the user can remove them.
        }

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, 15, 0xfff0ecff, true);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView state = text(enabled ? "ATIVO" : "DESATIVADO", 10,
                enabled ? 0xff9be0b0 : 0xffffcb83, true);
        state.setGravity(Gravity.CENTER);
        heading.addView(state, params(-2, -2));
        card.addView(heading, params(-1, -2));
        card.addView(text(details, 11, 0xffaaa2c6, false), topParams(-1, -2, 5));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button toggle = button(enabled ? "Desativar" : "Ativar", true, 42,
                v -> toggleMod(jar, enabled));
        Button remove = button("Remover", false, 42, v -> confirmRemove(jar));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        actionParams.topMargin = dp(10);
        actionParams.rightMargin = dp(5);
        actions.addView(toggle, actionParams);
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        removeParams.topMargin = dp(10);
        removeParams.leftMargin = dp(5);
        actions.addView(remove, removeParams);
        card.addView(actions, params(-1, -2));
        return card;
    }

    private TextView sectionTitle(String value, int color) {
        TextView title = text(value, 11, color, true);
        title.setLetterSpacing(0.08f);
        return title;
    }

    private void toggleMod(File jar, boolean currentlyEnabled) {
        if (busy) return;
        try {
            FabricModManager.setEnabled(this, instanceId, jar, !currentlyEnabled);
            status.setText(UiLanguage.translate(this,
                    currentlyEnabled ? "Mod desativado; não será carregado no próximo início."
                            : "Mod ativado; será carregado no próximo início."));
            refreshMods();
        } catch (Exception error) {
            status.setText(UiLanguage.translate(this, "Não foi possível alterar o mod: ")
                    + safeMessage(error));
        }
    }

    private void confirmRemove(File jar) {
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Remover mod Fabric?"))
                .setMessage(UiLanguage.translate(this, "O arquivo ")
                        + jar.getName() + UiLanguage.translate(this,
                        " será removido apenas desta instância."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Remover"), (dialog, which) -> {
                    File enabled;
                    File disabled;
                    try {
                        enabled = FabricModManager.getDirectory(this, instanceId, true);
                        disabled = FabricModManager.getDirectory(this, instanceId, false);
                        File canonical = jar.getCanonicalFile();
                        if (Files.isSymbolicLink(jar.toPath())
                                || (!enabled.equals(canonical.getParentFile())
                                && !disabled.equals(canonical.getParentFile()))
                                || !canonical.isFile() || !canonical.delete()) {
                            status.setText(UiLanguage.translate(this,
                                    "Não foi possível remover o arquivo selecionado."));
                        } else {
                            status.setText(UiLanguage.translate(this, "Mod removido."));
                        }
                    } catch (Exception error) {
                        status.setText(UiLanguage.translate(this, "Falha: ") + safeMessage(error));
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

    private void setBusy(boolean value) {
        if (progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
        if (importButton != null) importButton.setEnabled(!value);
        if (list != null) setChildrenEnabled(list, !value);
    }

    private void setChildrenEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                setChildrenEnabled(group.getChildAt(index), enabled);
            }
        }
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

    private Button button(String label, boolean primary, int heightDp, View.OnClickListener action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(UiLanguage.translate(this, label));
        button.setTextSize(12);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), dp(2), dp(10), dp(2));
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setTextColor(Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? 0xff5b3fd6 : 0xff2b2641);
        background.setCornerRadius(dp(5));
        background.setStroke(dp(1), primary ? 0xff8a74ff : 0xff3d3560);
        button.setBackground(background);
        button.setOnClickListener(action);
        LauncherButtonStyle.keepOriginalSize(button);
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(heightDp)));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(UiLanguage.translate(this, value));
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
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
