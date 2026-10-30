package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Manager for isolated game homes; the original horizon/ directory remains the default profile. */
public final class InstancesActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status;
    private ProgressBar progress;
    private boolean busy;
    private static final int REQUEST_IMPORT_INSTANCE = 501;
    private static final int REQUEST_EXPORT_INSTANCE = 502;
    private String pendingExportId;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        buildUi();
        refreshList();
    }

    @Override protected void onDestroy() {
        // shutdown() lets an already-running deletion finish without leaking the executor.
        io.shutdown();
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
        LauncherButtonStyle.install(this);

        page.addView(text("MOBILEHORIZON  /  INSTÂNCIAS", 11, 0xff9e88ff, true),
                params(-1, -2));
        TextView title = text("Instâncias", 27, 0xfff0ecff, true);
        LinearLayout.LayoutParams titleParams = params(-1, -2);
        titleParams.topMargin = dp(5);
        page.addView(title, titleParams);
        page.addView(text(
                "Cada instância mantém seus próprios mundos, opções, logs e pacotes. Os arquivos preparados do Minecraft e o motor Horizon continuam compartilhados para economizar espaço.",
                13, 0xffb9b3d1, false), topParams(-1, -2, 6));

        Button back = button("Voltar", false, v -> finish());
        setProminentActionSize(back);
        page.addView(back, topParams(-1, 60, 12));

        Button create = button("Nova instância", true, v -> showNameDialog(null));
        setProminentActionSize(create);
        page.addView(create, topParams(-1, 60, 10));

        Button importArchive = button("Importar instância (.zip)", false, v -> chooseImport());
        page.addView(importArchive, topParams(-1, 48, 8));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, topParams(-1, 4, 8));

        status = text("", 12, 0xffc7bce6, false);
        page.addView(status, topParams(-1, -2, 8));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, topParams(-1, -2, 4));

        LinearLayout info = card();
        info.addView(text("Perfil padrão preservado", 14, 0xffeee9ff, true),
                params(-1, -2));
        info.addView(text(
                "A instância Padrão continua usando a pasta antiga filesDir/horizon. Nenhum mundo, configuração ou pacote existente é movido durante esta migração.",
                12, 0xffb9b3d1, false), topParams(-1, -2, 5));
        page.addView(info, topParams(-1, -2, 12));
    }

    private void refreshList() {
        if (list == null || isFinishing() || isDestroyed()) return;
        list.removeAllViews();
        try {
            GameInstanceManager.Instance active = GameInstanceManager.getSelectedInstance(this);
            List<GameInstanceManager.Instance> instances = GameInstanceManager.listInstances(this);
            for (GameInstanceManager.Instance instance : instances) {
                list.addView(instanceCard(instance, active.id.equals(instance.id)),
                        topParams(-1, -2, 8));
            }
            status.setText(UiLanguage.translate(this, "Instâncias disponíveis: ")
                    + instances.size() + " / 12");
        } catch (Exception error) {
            status.setText(UiLanguage.translate(this, "Não foi possível ler o catálogo: ")
                    + error.getMessage());
        }
    }

    private View instanceCard(GameInstanceManager.Instance instance, boolean active) {
        LinearLayout card = card();
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(heading, params(-1, -2));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        heading.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1f));
        titleBox.addView(text(instance.name, 16, 0xfff0ecff, true), params(-1, -2));
        String kind = instance.isDefault() ? "Padrão" : "Perfil separado";
        InstanceMetadataStore.Metadata metadata;
        try { metadata = InstanceMetadataStore.get(this, instance.id); }
        catch (Exception ignored) { metadata = InstanceMetadataStore.Metadata.defaults(); }
        titleBox.addView(text(kind + "  •  " + (active ? "Em uso" : "Disponível")
                        + (metadata.group.isEmpty() ? "" : "  •  " + metadata.group),
                11, active ? 0xffc4adff : 0xffaaa2c6, true), topParams(-1, -2, 3));
        TextView icon = text(metadata.icon.toUpperCase(java.util.Locale.ROOT),
                9, metadata.color, true);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor((metadata.color & 0x00ffffff) | 0x33000000);
        iconBg.setCornerRadius(dp(5));
        icon.setBackground(iconBg);
        icon.setPadding(dp(7), dp(4), dp(7), dp(4));
        heading.addView(icon, params(-2, -2));

        String lastPlayed = instance.lastPlayed <= 0L
                ? UiLanguage.translate(this, "Último uso: nunca")
                : UiLanguage.translate(this, "Último uso: ")
                    + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                        .format(new Date(instance.lastPlayed));
        card.addView(text(lastPlayed, 11, 0xffa8a1c6, false), topParams(-1, -2, 8));
        card.addView(text(UiLanguage.translate(this, "Tempo jogado: ")
                        + formatPlayedTime(metadata.totalPlayedMs),
                11, 0xffa8a1c6, false), topParams(-1, -2, 3));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(actions, topParams(-1, -2, 9));
        Button select = button(active ? "Em uso" : "Selecionar", true, v -> select(instance));
        select.setEnabled(!active && !busy);
        actions.addView(select, new LinearLayout.LayoutParams(0, dp(38), 1f));
        Button rename = button("Renomear", false, v -> showNameDialog(instance));
        rename.setEnabled(!busy);
        LinearLayout.LayoutParams renameParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        renameParams.leftMargin = dp(7);
        actions.addView(rename, renameParams);

        LinearLayout extra = new LinearLayout(this);
        extra.setOrientation(LinearLayout.HORIZONTAL);
        extra.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(extra, topParams(-1, -2, 7));
        Button copy = button("Duplicar", false, v -> showCopyDialog(instance));
        extra.addView(copy, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button edit = button("Personalizar", false, v -> showMetadataDialog(instance));
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        editParams.leftMargin = dp(7);
        extra.addView(edit, editParams);
        Button export = button("Exportar", false, v -> chooseExport(instance));
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        exportParams.leftMargin = dp(7);
        extra.addView(export, exportParams);
        copy.setEnabled(!busy);
        edit.setEnabled(!busy);
        export.setEnabled(!busy);

        Button versions = button("Versão da runtime", false, v -> {
            Intent intent = new Intent(this, RuntimeVersionsActivity.class)
                    .putExtra(RuntimeVersionsActivity.EXTRA_INSTANCE_ID, instance.id);
            startActivity(intent);
        });
        versions.setEnabled(!busy);
        card.addView(versions, topParams(-1, 46, 7));

        Button delete = button("Excluir", false, v -> confirmDelete(instance));
        setProminentActionSize(delete);
        delete.setEnabled(!busy && !active && !instance.isDefault());
        card.addView(delete, topParams(-1, 60, 7));
        return card;
    }

    private void showCopyDialog(GameInstanceManager.Instance instance) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(24)});
        input.setText(instance.name + " (cópia)");
        input.setSelection(input.length());
        input.setHint(UiLanguage.translate(this, "Nome da cópia"));
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Duplicar instância"))
                .setView(input)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Duplicar"), (dialog, which) -> {
                    String name = input.getText().toString();
                    setBusy(true);
                    status.setText(UiLanguage.translate(this, "Duplicando mundos e configurações…"));
                    io.execute(() -> {
                        GameInstanceManager.Instance created = null;
                        Exception failure = null;
                        try {
                            created = InstanceArchiveManager.copyInstance(this, instance.id, name);
                            InstanceMetadataStore.Metadata source = InstanceMetadataStore.get(this, instance.id);
                            InstanceMetadataStore.update(this, created.id, source.group, source.icon, source.color);
                        } catch (Exception error) { failure = error; }
                        final GameInstanceManager.Instance result = created;
                        final Exception error = failure;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            setBusy(false);
                            if (error != null) showError("Não foi possível duplicar a instância", error);
                            else status.setText(UiLanguage.translate(this,
                                    "Instância duplicada: ") + result.name);
                            refreshList();
                        });
                    });
                }).show();
    }

    private void showMetadataDialog(GameInstanceManager.Instance instance) {
        InstanceMetadataStore.Metadata current;
        try { current = InstanceMetadataStore.get(this, instance.id); }
        catch (Exception error) { showError("Não foi possível ler as preferências", error); return; }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(6), dp(12), 0);
        EditText group = new EditText(this);
        group.setSingleLine(true);
        group.setHint(UiLanguage.translate(this, "Grupo (opcional)"));
        group.setText(current.group);
        panel.addView(group, params(-1, -2));
        EditText icon = new EditText(this);
        icon.setSingleLine(true);
        icon.setHint(UiLanguage.translate(this, "Ícone (ex.: grass, diamond, survival)"));
        icon.setText(current.icon);
        panel.addView(icon, topParams(-1, -2, 6));
        EditText color = new EditText(this);
        color.setSingleLine(true);
        color.setHint("#6551C8");
        color.setText(String.format(java.util.Locale.ROOT, "#%06X", current.color & 0x00ffffff));
        color.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        panel.addView(color, topParams(-1, -2, 6));
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Personalizar instância"))
                .setMessage(UiLanguage.translate(this,
                        "Organize por grupo e escolha um ícone e uma cor para identificar o perfil."))
                .setView(panel)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Salvar"), (dialog, which) -> {
                    try {
                        String colorValue = color.getText().toString().trim();
                        if (!colorValue.matches("#[0-9a-fA-F]{6}")) {
                            throw new IllegalArgumentException("A cor deve estar no formato #RRGGBB.");
                        }
                        int selectedColor = 0xff000000
                                | Integer.parseInt(colorValue.substring(1), 16);
                        InstanceMetadataStore.update(this, instance.id,
                                group.getText().toString(), icon.getText().toString(), selectedColor);
                        refreshList();
                    } catch (Exception error) { showError("Preferências inválidas", error); }
                }).show();
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_IMPORT_INSTANCE); }
        catch (Exception error) { showError("Não foi possível abrir o seletor de arquivos", error); }
    }

    private void chooseExport(GameInstanceManager.Instance instance) {
        pendingExportId = instance.id;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE,
                instance.name.replaceAll("[^A-Za-z0-9._ -]", "_") + ".zip");
        try { startActivityForResult(intent, REQUEST_EXPORT_INSTANCE); }
        catch (Exception error) { pendingExportId = null; showError("Não foi possível criar o arquivo", error); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExportId = null;
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_INSTANCE) {
            String instanceId = pendingExportId;
            pendingExportId = null;
            if (instanceId == null) return;
            setBusy(true);
            status.setText(UiLanguage.translate(this, "Exportando instância sem dados de conta ou logs…"));
            io.execute(() -> {
                Exception failure = null;
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new java.io.IOException("Destino de exportação indisponível.");
                    InstanceArchiveManager.exportZip(this, instanceId, output);
                } catch (Exception error) { failure = error; }
                final Exception error = failure;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false);
                    if (error != null) showError("Falha ao exportar instância", error);
                    else status.setText(UiLanguage.translate(this, "Instância exportada."));
                });
            });
        } else if (requestCode == REQUEST_IMPORT_INSTANCE) {
            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setHint(UiLanguage.translate(this, "Nome da instância importada"));
            new AlertDialog.Builder(this)
                    .setTitle(UiLanguage.translate(this, "Importar instância"))
                    .setView(input)
                    .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                    .setPositiveButton(UiLanguage.translate(this, "Importar"), (dialog, which) -> {
                        setBusy(true);
                        status.setText(UiLanguage.translate(this, "Validando arquivo da instância…"));
                        String name = input.getText().toString();
                        io.execute(() -> {
                            GameInstanceManager.Instance imported = null;
                            Exception failure = null;
                            try (InputStream source = getContentResolver().openInputStream(uri)) {
                                imported = InstanceArchiveManager.importZip(this, source, name);
                            } catch (Exception error) { failure = error; }
                            final GameInstanceManager.Instance result = imported;
                            final Exception error = failure;
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                setBusy(false);
                                if (error != null) showError("Falha ao importar instância", error);
                                else {
                                    try {
                                        GameInstanceManager.setSelectedInstance(this, result.id);
                                        setResult(RESULT_OK);
                                    } catch (Exception ignored) {}
                                    status.setText(UiLanguage.translate(this,
                                            "Instância importada e selecionada: ") + result.name);
                                }
                                refreshList();
                            });
                        });
                    }).show();
        }
    }

    private void select(GameInstanceManager.Instance instance) {
        try {
            GameInstanceManager.setSelectedInstance(this, instance.id);
            setResult(RESULT_OK);
            finish();
        } catch (Exception error) {
            showError("Não foi possível selecionar a instância", error);
        }
    }

    private void showNameDialog(GameInstanceManager.Instance editing) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        input.setHint(UiLanguage.translate(this, "Nome da instância"));
        if (editing != null) input.setText(editing.name);
        if (editing != null) input.setSelection(input.length());
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this,
                        editing == null ? "Criar instância" : "Renomear instância"))
                .setMessage(UiLanguage.translate(this, "Use um nome de até 24 caracteres."))
                .setView(input)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this,
                        editing == null ? "Criar" : "Salvar"), (dialog, which) -> {
                    try {
                        if (editing == null) {
                            GameInstanceManager.createInstance(this, input.getText().toString());
                            status.setText(UiLanguage.translate(this, "Instância criada."));
                        } else {
                            GameInstanceManager.renameInstance(
                                    this, editing.id, input.getText().toString());
                            status.setText(UiLanguage.translate(this, "Instância renomeada."));
                        }
                        refreshList();
                    } catch (Exception error) {
                        showError("Não foi possível salvar a instância", error);
                    }
                }).show();
    }

    private void confirmDelete(GameInstanceManager.Instance instance) {
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Excluir instância?"))
                .setMessage(UiLanguage.translate(this,
                        "Isso remove os mundos, opções, logs e pacotes desse perfil. A instância Padrão e a instância atualmente em uso não podem ser excluídas."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Excluir"), (dialog, which) -> {
                    busy = true;
                    setBusy(true);
                    io.execute(() -> {
                        Exception failure = null;
                        try {
                            GameInstanceManager.deleteInstance(this, instance.id);
                        } catch (Exception error) {
                            failure = error;
                        }
                        final Exception result = failure;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            busy = false;
                            setBusy(false);
                            if (result != null) showError("Não foi possível excluir a instância", result);
                            else {
                                try { InstanceMetadataStore.remove(this, instance.id); } catch (Exception ignored) {}
                                status.setText(UiLanguage.translate(this, "Instância excluída."));
                            }
                            refreshList();
                        });
                    });
                }).show();
    }

    private void setBusy(boolean value) {
        busy = value;
        if (progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
        if (list != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                list.getChildAt(i).setAlpha(value ? 0.55f : 1f);
                setChildrenEnabled(list.getChildAt(i), !value);
            }
        }
    }

    private void setChildrenEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setChildrenEnabled(group.getChildAt(i), enabled);
            }
        }
    }

    private void showError(String title, Exception error) {
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, title))
                .setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
                .setPositiveButton(UiLanguage.translate(this, "Fechar"), null)
                .show();
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
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(7), 0, dp(7), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? 0xff5b3fd6 : 0xff2b2641);
        background.setCornerRadius(dp(5));
        background.setStroke(dp(1), primary ? 0xff8a74ff : 0xff3d3560);
        button.setBackground(background);
        button.setTextColor(Color.WHITE);
        button.setOnClickListener(action);
        return button;
    }

    private String formatPlayedTime(long millis) {
        long totalMinutes = Math.max(0L, millis / 60000L);
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes / 60L) % 24L;
        long minutes = totalMinutes % 60L;
        if (days > 0L) return days + " d " + hours + " h";
        if (hours > 0L) return hours + " h " + minutes + " min";
        return minutes + " min";
    }

    private void setProminentActionSize(Button button) {
        button.setMinHeight(dp(60));
        button.setMinimumHeight(dp(60));
        button.setPadding(dp(14), dp(3), dp(14), dp(3));
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
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
