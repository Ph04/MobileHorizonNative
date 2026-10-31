package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local package manager for optional Horizon compatibility modules. */
public final class CompatibilityModulesActivity extends Activity {
    private static final int REQUEST_IMPORT_MODULE = 701;
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
        refreshModules();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshModules();
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
        page.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("MOBILEHORIZON  /  MÓDULOS", 11, 0xff9e88ff, true), params(-1, -2));
        page.addView(text("Módulos de compatibilidade", 25, 0xfff0ecff, true),
                topParams(-1, -2, 5));
        page.addView(text(
                "Instale módulos uma vez e associe-os às instâncias que usam o loader correspondente. Os módulos não exigem um APK diferente.",
                13, 0xffb9b3d1, false), topParams(-1, -2, 6));

        Button back = button("Voltar", false, v -> finish());
        page.addView(back, topParams(-1, 52, 12));
        Button install = button("Importar módulo .mhplugin", true, v -> confirmImport());
        page.addView(install, topParams(-1, 58, 8));
        Button fabric = button("Instalar/atualizar Fabric Java Bridge", false,
                v -> installFabricBridge());
        page.addView(fabric, topParams(-1, 54, 8));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, topParams(-1, 4, 10));
        status = text("", 12, 0xffc7bce6, false);
        page.addView(status, topParams(-1, -2, 8));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, topParams(-1, -2, 6));

        LinearLayout warning = card();
        warning.addView(text("Atenção: módulos de terceiros", 14, 0xffffd18b, true),
                params(-1, -2));
        warning.addView(text(
                "Um módulo pode conter código executável e alterar o comportamento do jogo. Importe apenas pacotes de fontes em que você confia. O launcher valida a estrutura e os caminhos do pacote, mas isso não equivale a uma assinatura do desenvolvedor.",
                12, 0xffc7bce6, false), topParams(-1, -2, 5));
        page.addView(warning, topParams(-1, -2, 14));

        LinearLayout info = card();
        info.addView(text("Fabric Java Bridge externo", 14, 0xffeee9ff, true), params(-1, -2));
        info.addView(text(
                "O bridge iaxivers/horizon é um runtime separado e experimental para Minecraft 26.3. Depois de instalá-lo, crie uma instância Fabric e use Gerenciar mods Fabric para importar JARs. O perfil Vanilla continua usando o motor normal.",
                12, 0xffb9b3d1, false), topParams(-1, -2, 5));
        info.addView(text(
                "Versão instalada: " + (FabricBridgeRuntimeManager.installedVersion(this).isEmpty()
                        ? "nenhuma" : FabricBridgeRuntimeManager.installedVersion(this)),
                11, 0xffc4adff, true), topParams(-1, -2, 7));
        page.addView(info, topParams(-1, -2, 10));
    }

    private void installFabricBridge() {
        if (busy) return;
        busy = true;
        setBusy(true);
        status.setText("Consultando o release do Fabric Java Bridge…");
        io.execute(() -> {
            Exception failure = null;
            FabricBridgeRuntimeManager.PreparedRuntime installed = null;
            try {
                installed = FabricBridgeRuntimeManager.install(this, (stage, bytes, total) -> {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (total > 0) {
                            status.setText(stage + "  "
                                    + String.format(java.util.Locale.ROOT, "%.1f / %.1f MiB",
                                            bytes / (1024.0 * 1024.0), total / (1024.0 * 1024.0)));
                        } else {
                            status.setText(stage);
                        }
                    });
                });
            } catch (Exception error) {
                failure = error;
            }
            final Exception error = failure;
            final FabricBridgeRuntimeManager.PreparedRuntime result = installed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                setBusy(false);
                if (error != null) {
                    status.setText("Falha ao instalar Fabric Java Bridge: " + safeMessage(error));
                } else {
                    status.setText("Fabric Java Bridge instalado e verificado: " + result.activeVersion
                            + ". Agora você pode criar uma instância Fabric.");
                }
                refreshModules();
                if (error == null) {
                    status.setText("Fabric Java Bridge instalado e verificado: " + result.activeVersion
                            + ". Crie uma instância Fabric para começar.");
                }
            });
        });
    }

    private void confirmImport() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Importar módulo de compatibilidade?"))
                .setMessage(UiLanguage.translate(this,
                        "O pacote pode fornecer código executável ao runtime do jogo. Continue somente se confiar na origem do arquivo."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Escolher arquivo"),
                        (dialog, which) -> openModulePicker())
                .show();
    }

    private void openModulePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed",
                "application/octet-stream", "application/vnd.mobilehorizon.plugin"
        });
        startActivityForResult(intent, REQUEST_IMPORT_MODULE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_MODULE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        busy = true;
        setBusy(true);
        status.setText(UiLanguage.translate(this, "Validando e instalando o pacote..."));
        io.execute(() -> {
            Exception failure = null;
            CompatibilityPluginManager.Module result = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                result = CompatibilityPluginManager.install(this, input);
            } catch (Exception error) {
                failure = error;
            }
            final Exception error = failure;
            final CompatibilityPluginManager.Module installed = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                setBusy(false);
                if (error != null) {
                    status.setText(UiLanguage.translate(this, "Falha ao instalar: ")
                            + safeMessage(error));
                } else {
                    status.setText(UiLanguage.translate(this, "Módulo instalado: ")
                            + installed.displayLabel());
                }
                refreshModules();
            });
        });
    }

    private void refreshModules() {
        if (list == null || isFinishing() || isDestroyed()) return;
        list.removeAllViews();
        try {
            List<CompatibilityPluginManager.Module> modules =
                    CompatibilityPluginManager.listModules(this);
            status.setText(UiLanguage.translate(this, "Módulos instalados: ") + modules.size());
            if (modules.isEmpty()) {
                list.addView(text(
                        "Nenhum módulo instalado. Quando houver um pacote compatível, importe-o aqui para disponibilizá-lo nas instâncias.",
                        13, 0xffb9b3d1, false), params(-1, -2));
                return;
            }
            for (CompatibilityPluginManager.Module module : modules) {
                list.addView(moduleCard(module), topParams(-1, -2, 8));
            }
        } catch (Exception error) {
            status.setText(UiLanguage.translate(this, "Não foi possível ler os módulos: ")
                    + safeMessage(error));
        }
    }

    private View moduleCard(CompatibilityPluginManager.Module module) {
        LinearLayout card = card();
        card.addView(text(module.name + "  " + module.version,
                16, 0xfff0ecff, true), params(-1, -2));
        card.addView(text(module.loaderId.toUpperCase() + " " + module.loaderVersion
                        + "  •  Minecraft " + join(module.minecraftVersions),
                12, 0xffc4adff, true), topParams(-1, -2, 5));
        card.addView(text(module.id, 11, 0xffa8a1c6, false), topParams(-1, -2, 4));
        if (!module.description.isEmpty()) {
            card.addView(text(module.description, 12, 0xffb9b3d1, false),
                    topParams(-1, -2, 6));
        }
        card.addView(text("Entrada: " + module.entrypointJar,
                11, 0xffa8a1c6, false), topParams(-1, -2, 6));
        Button remove = button("Desinstalar", false, v -> confirmUninstall(module));
        card.addView(remove, topParams(-1, 44, 8));
        return card;
    }

    private void confirmUninstall(CompatibilityPluginManager.Module module) {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Desinstalar módulo?"))
                .setMessage(UiLanguage.translate(this,
                        "O pacote será removido do armazenamento compartilhado. Instâncias que ainda o utilizam precisam ser reconfiguradas primeiro."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Desinstalar"), (dialog, which) -> {
                    busy = true;
                    setBusy(true);
                    io.execute(() -> {
                        Exception failure = null;
                        try {
                            CompatibilityPluginManager.uninstall(this, module.id, module.version);
                        } catch (Exception error) {
                            failure = error;
                        }
                        final Exception result = failure;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            busy = false;
                            setBusy(false);
                            status.setText(result == null
                                    ? UiLanguage.translate(this, "Módulo desinstalado.")
                                    : UiLanguage.translate(this, "Não foi possível desinstalar: ")
                                        + safeMessage(result));
                            refreshModules();
                        });
                    });
                }).show();
    }

    private void setBusy(boolean value) {
        if (progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
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
        button.setPadding(dp(8), 0, dp(8), 0);
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
