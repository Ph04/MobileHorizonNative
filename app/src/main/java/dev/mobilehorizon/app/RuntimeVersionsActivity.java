package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Verified runtime inventory and per-instance version pinning. */
public final class RuntimeVersionsActivity extends Activity {
    static final String EXTRA_INSTANCE_ID = "dev.mobilehorizon.app.EXTRA_RUNTIME_INSTANCE_ID";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String instanceId;
    private LinearLayout list;
    private TextView status;
    private ProgressBar progress;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        instanceId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_INSTANCE_ID);
        if (!GameInstanceManager.isValidInstanceId(instanceId)) {
            finish();
            return;
        }
        buildUi();
        refresh();
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

        page.addView(text("MOBILEHORIZON  /  MOTOR", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("Minecraft 26.3 • versões do motor", 26, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text("O Horizon Rust executa Minecraft Java Edition 26.3. Os controles abaixo selecionam releases do motor Horizon para essa mesma versão do jogo; não são versões diferentes do Minecraft.", 12, 0xffb9b3d1, false), top(-1, -2, 6));

        LinearLayout gameVersionCard = card();
        gameVersionCard.addView(text("Versão do Minecraft", 15, 0xfff0ecff, true), lp(-1, -2));
        gameVersionCard.addView(text("Minecraft Java Edition 26.3", 18, 0xffbcaaff, true), top(-1, -2, 6));
        gameVersionCard.addView(text("Mundos de 1.8 a 26.2 podem ser atualizados para 26.3; o motor não executa essas versões antigas.", 11, 0xffaaa2c6, false), top(-1, -2, 4));
        page.addView(gameVersionCard, top(-1, -2, 10));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 48, 10));

        LinearLayout actions = card();
        actions.addView(button("Seguir runtime automática", true, v -> clearPin()), lp(-1, 48));
        actions.addView(button("Atualizar catálogo", false, v -> refresh()), top(-1, 44, 7));
        page.addView(actions, top(-1, -2, 10));

        status = text("", 11, 0xffc7bce6, false);
        page.addView(status, top(-1, -2, 8));
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, top(-1, 4, 5));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, top(-1, -2, 6));

        page.addView(text("As versões listadas abaixo são releases do motor Horizon para Minecraft 26.3, não versões do jogo. Runtimes baixadas pelo launcher só são consideradas instaladas depois de validar manifesto, ELF ARM64 e SHA-256. Uma versão fixada é ativada apenas quando esta instância inicia.", 11, 0xffaaa2c6, false), top(-1, -2, 10));
    }

    private void refresh() {
        if (busy || list == null || isFinishing() || isDestroyed()) return;
        setBusy(true, "Lendo runtimes instaladas e catálogo publicado…");
        io.execute(() -> {
            List<HorizonRuntimeManager.InstalledVersion> installed = new ArrayList<>();
            List<HorizonRuntimeUpdateService.PublishedRuntime> published = new ArrayList<>();
            Exception localFailure = null;
            Exception remoteFailure = null;
            try { installed = HorizonRuntimeManager.listInstalledVersions(this); }
            catch (Exception error) { localFailure = error; }
            try { published = HorizonRuntimeUpdateService.listPublishedRuntimes(); }
            catch (Exception error) { remoteFailure = error; }
            final List<HorizonRuntimeManager.InstalledVersion> local = installed;
            final List<HorizonRuntimeUpdateService.PublishedRuntime> remote = published;
            final Exception localError = localFailure;
            final Exception remoteError = remoteFailure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false, "");
                render(local, remote, localError, remoteError);
            });
        });
    }

    private void render(List<HorizonRuntimeManager.InstalledVersion> installed,
                        List<HorizonRuntimeUpdateService.PublishedRuntime> published,
                        Exception localError, Exception remoteError) {
        list.removeAllViews();
        InstanceMetadataStore.Metadata metadata;
        try { metadata = InstanceMetadataStore.get(this, instanceId); }
        catch (Exception error) {
            status.setText(UiLanguage.translate(this, "Não foi possível ler os metadados da instância: ")
                    + message(error));
            return;
        }
        String automatic = HorizonRuntimeUpdateService.automaticRuntimeVersion(this);
        String pin = metadata.pinnedVersion;
        boolean pinnedIsInstalled = containsVersion(installed, pin);
        String selected = pin.isEmpty()
                ? "Automática" + (automatic.isEmpty() ? " (ainda não definida)" : " • " + automatic)
                : pin + (pinnedIsInstalled ? "" : " (não instalada)");
        status.setText(UiLanguage.translate(this, "Esta instância: ")
                + selected + "\n" + UiLanguage.translate(this, "Versão padrão global: ")
                + (automatic.isEmpty() ? "não definida" : automatic));

        LinearLayout installedCard = card();
        installedCard.addView(text("Instaladas e verificadas", 16, 0xfff0ecff, true), lp(-1, -2));
        if (localError != null) {
            installedCard.addView(text(message(localError), 11, 0xffffb0a8, false), top(-1, -2, 5));
        } else if (installed.isEmpty()) {
            installedCard.addView(text("Nenhuma runtime local verificada.", 11, 0xffaaa2c6, false), top(-1, -2, 5));
        } else {
            for (HorizonRuntimeManager.InstalledVersion item : installed) {
                installedCard.addView(installedRow(item, pin, automatic), top(-1, -2, 8));
            }
        }
        list.addView(installedCard, top(-1, -2, 8));

        LinearLayout publishedCard = card();
        publishedCard.addView(text("Runtimes publicadas", 16, 0xfff0ecff, true), lp(-1, -2));
        publishedCard.addView(text("Somente releases completas com manifesto e digest compatíveis aparecem aqui.", 11, 0xffaaa2c6, false), top(-1, -2, 4));
        if (remoteError != null) {
            publishedCard.addView(text("Não foi possível consultar GitHub: " + message(remoteError),
                    11, 0xffffb0a8, false), top(-1, -2, 6));
        } else if (published.isEmpty()) {
            publishedCard.addView(text("Nenhuma runtime publicada foi encontrada.", 11, 0xffaaa2c6, false), top(-1, -2, 6));
        } else {
            for (HorizonRuntimeUpdateService.PublishedRuntime item : published) {
                publishedCard.addView(publishedRow(item, pin, automatic,
                        containsVersion(installed, item.version)), top(-1, -2, 8));
            }
        }
        list.addView(publishedCard, top(-1, -2, 8));
    }

    private View installedRow(HorizonRuntimeManager.InstalledVersion item,
                              String pin, String automatic) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(text(item.version, 12, 0xffeee9ff, true), lp(-1, -2));
        String label = "Horizon PC " + (item.upstreamTag.isEmpty() ? "tag desconhecida" : item.upstreamTag)
                + (item.active ? " • ativa no ponteiro" : "")
                + (item.version.equals(pin) ? " • fixada nesta instância" : "")
                + (item.version.equals(automatic) ? " • padrão automática" : "");
        row.addView(text(label, 10, 0xffaaa2c6, false), top(-1, -2, 2));
        String date = item.installedAt <= 0L ? "" : new SimpleDateFormat(
                "dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date(item.installedAt));
        if (!date.isEmpty()) row.addView(text("Instalada: " + date, 10, 0xff817a9e, false), top(-1, -2, 2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(actions, top(-1, -2, 5));
        Button pinButton = button(item.version.equals(pin) ? "Fixada" : "Fixar nesta instância",
                !item.version.equals(pin), v -> pinInstalled(item.version));
        pinButton.setEnabled(!item.version.equals(pin) && !busy);
        actions.addView(pinButton, new LinearLayout.LayoutParams(0, dp(40), 1f));
        Button defaultButton = button("Usar como padrão", false,
                v -> installAsDefault(item.version));
        LinearLayout.LayoutParams defaultParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        defaultParams.leftMargin = dp(6);
        defaultButton.setEnabled(!item.version.equals(automatic) && !busy);
        actions.addView(defaultButton, defaultParams);
        return row;
    }

    private boolean containsVersion(List<HorizonRuntimeManager.InstalledVersion> installed,
                                    String version) {
        if (version == null || version.isEmpty()) return false;
        for (HorizonRuntimeManager.InstalledVersion item : installed) {
            if (item.version.equals(version)) return true;
        }
        return false;
    }

    private View publishedRow(HorizonRuntimeUpdateService.PublishedRuntime item,
                              String pin, String automatic, boolean installed) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(text(item.version, 12, 0xffeee9ff, true), lp(-1, -2));
        String date = item.publishedAt == null || item.publishedAt.isEmpty()
                ? "" : item.publishedAt;
        row.addView(text("Horizon PC " + item.upstreamTag + " • " + formatSize(item.sizeBytes)
                        + (date.isEmpty() ? "" : " • " + date),
                10, 0xffaaa2c6, false), top(-1, -2, 2));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(actions, top(-1, -2, 5));
        Button instanceButton = button(installed
                        ? (item.version.equals(pin) ? "Fixada" : "Fixar nesta instância")
                        : "Instalar e fixar",
                !item.version.equals(pin), v -> installForInstance(item.version));
        instanceButton.setEnabled(!busy && !item.version.equals(pin));
        actions.addView(instanceButton, new LinearLayout.LayoutParams(0, dp(40), 1f));
        Button defaultButton = button(installed ? "Usar como padrão" : "Instalar como padrão",
                false, v -> installAsDefault(item.version));
        defaultButton.setEnabled(!busy && !item.version.equals(automatic));
        LinearLayout.LayoutParams defaultParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        defaultParams.leftMargin = dp(6);
        actions.addView(defaultButton, defaultParams);
        return row;
    }

    private void pinInstalled(String version) {
        // runInstall verifies the binary on the I/O executor before saving this instance pin.
        runInstall(version, false);
    }

    private void clearPin() {
        if (busy) return;
        setBusy(true, "Removendo versão fixada…");
        io.execute(() -> {
            Exception failure = null;
            try {
                InstanceMetadataStore.setPinnedVersion(this, instanceId, "");
                HorizonRuntimeUpdateService.restoreAutomaticRuntimeVersion(this);
            } catch (Exception error) { failure = error; }
            final Exception result = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false, "");
                if (result != null) showError("Não foi possível remover a versão fixada", result);
                else status.setText(UiLanguage.translate(this,
                        "Esta instância seguirá novamente a runtime automática."));
                refresh();
            });
        });
    }

    private void installForInstance(String version) {
        runInstall(version, false);
    }

    private void installAsDefault(String version) {
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Alterar runtime padrão?"))
                .setMessage(UiLanguage.translate(this,
                        "A versão escolhida passará a ser a padrão global. A fixação desta instância será removida."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Continuar"),
                        (dialog, which) -> runInstall(version, true))
                .show();
    }

    private void runInstall(String version, boolean makeDefault) {
        if (busy) return;
        setBusy(true, makeDefault ? "Instalando runtime padrão…" : "Instalando runtime para esta instância…");
        io.execute(() -> {
            Exception failure = null;
            try {
                if (!HorizonRuntimeManager.hasInstalledVersion(this, version)) {
                    HorizonRuntimeUpdateService.installSpecificRuntime(this, version, makeDefault);
                } else if (makeDefault) {
                    HorizonRuntimeManager.activateInstalledVersion(this, version);
                    HorizonRuntimeUpdateService.setAutomaticRuntimeVersion(this, version);
                }
                if (makeDefault) {
                    InstanceMetadataStore.setPinnedVersion(this, instanceId, "");
                } else {
                    InstanceMetadataStore.setPinnedVersion(this, instanceId, version);
                    HorizonRuntimeUpdateService.restoreAutomaticRuntimeVersion(this);
                }
            } catch (Exception error) { failure = error; }
            final Exception result = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false, "");
                if (result != null) showError("Falha ao instalar a runtime", result);
                else status.setText(UiLanguage.translate(this, makeDefault
                        ? "Runtime padrão atualizada."
                        : "Runtime instalada e fixada para esta instância."));
                refresh();
            });
        });
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
        if (status != null && value && message != null) status.setText(UiLanguage.translate(this, message));
        if (list != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                list.getChildAt(i).setAlpha(value ? 0.55f : 1f);
            }
        }
    }

    private void showError(String title, Exception error) {
        new AlertDialog.Builder(this).setTitle(UiLanguage.translate(this, title))
                .setMessage(message(error))
                .setPositiveButton(UiLanguage.translate(this, "Fechar"), null).show();
    }

    private String message(Exception error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024d);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024d * 1024d));
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff151321); bg.setCornerRadius(dp(7)); bg.setStroke(dp(1), 0xff37304f);
        v.setBackground(bg);
        return v;
    }

    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(UiLanguage.translate(this, label)); b.setTextSize(12);
        b.setTextColor(Color.WHITE); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
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
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
