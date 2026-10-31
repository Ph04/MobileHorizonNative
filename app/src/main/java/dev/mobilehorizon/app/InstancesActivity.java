package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        LauncherButtonStyle.install(this);
getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        buildUi();
        refreshList();
    }

    @Override protected void onResume() {
        super.onResume();
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

        Button modules = button("Módulos de compatibilidade", false,
                v -> startActivity(new android.content.Intent(this, CompatibilityModulesActivity.class)));
        page.addView(modules, topParams(-1, 52, 8));

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
        titleBox.addView(text(kind + "  •  " + (active ? "Em uso" : "Disponível"),
                11, active ? 0xffc4adff : 0xffaaa2c6, true), topParams(-1, -2, 3));
        try {
            GameProfileManager.Profile profile = GameProfileManager.getProfile(this, instance.id);
            titleBox.addView(text(profile.displayName(), 12, 0xff9ee0c0, true),
                    topParams(-1, -2, 5));
        } catch (Exception error) {
            titleBox.addView(text("Perfil não validado: " + error.getMessage(),
                    11, 0xffffb2a8, false), topParams(-1, -2, 5));
        }

        String lastPlayed = instance.lastPlayed <= 0L
                ? UiLanguage.translate(this, "Último uso: nunca")
                : UiLanguage.translate(this, "Último uso: ")
                    + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                        .format(new Date(instance.lastPlayed));
        card.addView(text(lastPlayed, 11, 0xffa8a1c6, false), topParams(-1, -2, 8));

        try {
            GameProfileManager.Profile profile = GameProfileManager.getProfile(this, instance.id);
            if (profile.isFabricJavaBridge()) {
                Button manageMods = button("Gerenciar mods Fabric", false, v -> {
                    android.content.Intent intent = new android.content.Intent(this, FabricModsActivity.class);
                    intent.putExtra(FabricModsActivity.EXTRA_INSTANCE_ID, instance.id);
                    startActivity(intent);
                });
                manageMods.setEnabled(!busy);
                card.addView(manageMods, topParams(-1, 46, 8));
            }
        } catch (Exception ignored) {
            // The profile validation line above already reports malformed profiles.
        }

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

        Button configure = button("Versão e loader", false, v -> showProfileDialog(instance));
        configure.setEnabled(!busy && !instance.isDefault());
        card.addView(configure, topParams(-1, 42, 7));

        Button delete = button("Excluir", false, v -> confirmDelete(instance));
        setProminentActionSize(delete);
        delete.setEnabled(!busy && !active && !instance.isDefault());
        card.addView(delete, topParams(-1, 60, 7));
        return card;
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

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), 0);
        content.addView(input, params(-1, -2));
        Spinner loader = null;
        List<GameProfileManager.LoaderChoice> choices = java.util.Collections.emptyList();
        if (editing == null) {
            try {
                choices = GameProfileManager.listLoaderChoices(
                        this, OfficialMinecraftInstaller.VERSION);
            } catch (Exception error) {
                showError("Não foi possível consultar os módulos", error);
                return;
            }
            TextView loaderLabel = text("Versão: Minecraft " + OfficialMinecraftInstaller.VERSION
                    + "  •  Tipo de instalação", 12, 0xffb9b3d1, true);
            content.addView(loaderLabel, topParams(-1, -2, 12));
            loader = new Spinner(this);
            ArrayAdapter<GameProfileManager.LoaderChoice> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, choices);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            loader.setAdapter(adapter);
            content.addView(loader, topParams(-1, -2, 4));
        }
        final Spinner loaderPicker = loader;
        final List<GameProfileManager.LoaderChoice> availableChoices = choices;

        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this,
                        editing == null ? "Criar instância" : "Renomear instância"))
                .setMessage(UiLanguage.translate(this, editing == null
                        ? "Cada loader terá sua própria instância. Apenas módulos instalados aparecem nesta lista."
                        : "Use um nome de até 24 caracteres."))
                .setView(content)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this,
                        editing == null ? "Criar" : "Salvar"), (dialog, which) -> {
                    GameInstanceManager.Instance created = null;
                    try {
                        if (editing == null) {
                            int selected = loaderPicker == null ? 0 : loaderPicker.getSelectedItemPosition();
                            GameProfileManager.LoaderChoice choice = availableChoices.get(
                                    Math.max(0, Math.min(selected, availableChoices.size() - 1)));
                            created = GameInstanceManager.createInstance(
                                    this, input.getText().toString());
                            try {
                                GameProfileManager.configureProfile(this, created.id,
                                        OfficialMinecraftInstaller.VERSION, choice);
                            } catch (Exception profileError) {
                                try { GameInstanceManager.deleteInstance(this, created.id); }
                                catch (Exception cleanupError) {
                                    profileError.addSuppressed(cleanupError);
                                }
                                throw profileError;
                            }
                            status.setText(UiLanguage.translate(this, "Instância criada: ")
                                    + choice.label);
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

    private void showProfileDialog(GameInstanceManager.Instance instance) {
        final List<GameProfileManager.LoaderChoice> choices;
        final GameProfileManager.Profile current;
        try {
            current = GameProfileManager.getProfile(this, instance.id);
            choices = GameProfileManager.listLoaderChoices(
                    this, OfficialMinecraftInstaller.VERSION);
        } catch (Exception error) {
            showError("Não foi possível consultar os perfis disponíveis", error);
            return;
        }
        Spinner loader = new Spinner(this);
        ArrayAdapter<GameProfileManager.LoaderChoice> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        loader.setAdapter(adapter);
        for (int i = 0; i < choices.size(); i++) {
            GameProfileManager.LoaderChoice choice = choices.get(i);
            if (choice.loaderId.equals(current.loaderId)
                    && choice.moduleId.equals(current.moduleId)
                    && choice.moduleVersion.equals(current.moduleVersion)) {
                loader.setSelection(i);
                break;
            }
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), 0);
        content.addView(text("Minecraft " + OfficialMinecraftInstaller.VERSION
                + "  •  Loader", 12, 0xffb9b3d1, true), params(-1, -2));
        content.addView(loader, topParams(-1, -2, 5));
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Alterar versão e loader"))
                .setMessage(UiLanguage.translate(this,
                        "A configuração afeta somente esta instância. Mundos e configurações permanecem na pasta dela."))
                .setView(content)
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Aplicar"), (dialog, which) -> {
                    try {
                        int selected = loader.getSelectedItemPosition();
                        GameProfileManager.configureProfile(this, instance.id,
                                OfficialMinecraftInstaller.VERSION, choices.get(selected));
                        status.setText(UiLanguage.translate(this, "Perfil atualizado."));
                        refreshList();
                    } catch (Exception error) {
                        showError("Não foi possível alterar o perfil", error);
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
                            else status.setText(UiLanguage.translate(this, "Instância excluída."));
                            refreshList();
                        });
                    });
                }).show();
    }

    private void setBusy(boolean value) {
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
