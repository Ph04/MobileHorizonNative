package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Installs, enables, disables and removes vanilla data packs for one world. */
public final class WorldDataPacksActivity extends Activity {
    static final String EXTRA_WORLD_FOLDER = "dev.mobilehorizon.app.EXTRA_WORLD_DATA_PACKS_FOLDER";
    private static final int PICK_PACK = 701;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String worldFolder;
    private LinearLayout list;
    private TextView status;
    private ProgressBar progress;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        worldFolder = getIntent() == null ? null
                : getIntent().getStringExtra(EXTRA_WORLD_FOLDER);
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

        page.addView(text("MOBILEHORIZON  /  MUNDOS", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("Data packs", 26, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text("Pacotes de dados do mundo selecionado. Ative, desative, instale ou remova arquivos compatíveis com Minecraft vanilla.", 12, 0xffb9b3d1, false), top(-1, -2, 6));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 48, 10));

        LinearLayout controls = card();
        controls.addView(button("Adicionar data pack (.zip)", true, v -> choosePack()), lp(-1, 50));
        controls.addView(button("Atualizar lista", false, v -> refresh()), top(-1, 44, 7));
        page.addView(controls, top(-1, -2, 10));

        status = text("", 11, 0xffc7bce6, false);
        page.addView(status, top(-1, -2, 8));
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, top(-1, 4, 5));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, top(-1, -2, 5));
        page.addView(text("Arquivos desativados são movidos para uma pasta separada dentro do mundo e voltam a ser carregados quando reativados. O Minecraft precisa recarregar os data packs para aplicar as alterações.", 11, 0xffaaa2c6, false), top(-1, -2, 10));
    }

    private void refresh() {
        if (list == null || busy || isFinishing() || isDestroyed()) return;
        list.removeAllViews();
        try {
            List<WorldDataPackManager.Pack> packs = WorldDataPackManager.list(this, worldFolder);
            status.setText(UiLanguage.translate(this, "Data packs: ")
                    + packs.size() + " • " + UiLanguage.translate(this, "Ativos: ")
                    + packs.stream().filter(p -> p.enabled).count());
            if (packs.isEmpty()) {
                list.addView(text("Este mundo ainda não possui data packs.", 12,
                        0xffaaa2c6, false), top(-1, -2, 8));
                return;
            }
            for (WorldDataPackManager.Pack pack : packs) {
                list.addView(packCard(pack), top(-1, -2, 8));
            }
        } catch (Exception error) {
            status.setText(UiLanguage.translate(this, "Não foi possível listar data packs: ")
                    + message(error));
        }
    }

    private View packCard(WorldDataPackManager.Pack pack) {
        LinearLayout card = card();
        card.addView(text(pack.fileName, 14, 0xfff0ecff, true), lp(-1, -2));
        card.addView(text(pack.description + " • pack_format " + pack.packFormat
                        + " • " + formatSize(pack.sizeBytes),
                10, 0xffaaa2c6, false), top(-1, -2, 3));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, top(-1, -2, 8));
        Button toggle = button(pack.enabled ? "Desativar" : "Ativar",
                pack.enabled, v -> setEnabled(pack));
        row.addView(toggle, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button remove = button("Remover", false, v -> confirmRemove(pack));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        removeParams.leftMargin = dp(7);
        row.addView(remove, removeParams);
        return card;
    }

    private void setEnabled(WorldDataPackManager.Pack pack) {
        runOperation(pack.enabled ? "Desativando data pack…" : "Ativando data pack…",
                () -> WorldDataPackManager.setEnabled(this, worldFolder, pack.fileName, !pack.enabled));
    }

    private void confirmRemove(WorldDataPackManager.Pack pack) {
        new AlertDialog.Builder(this)
                .setTitle(UiLanguage.translate(this, "Remover data pack?"))
                .setMessage(pack.fileName + "\n\n" + UiLanguage.translate(this,
                        "O arquivo será removido permanentemente deste mundo."))
                .setNegativeButton(UiLanguage.translate(this, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(this, "Remover"),
                        (dialog, which) -> runOperation("Removendo data pack…",
                                () -> WorldDataPackManager.delete(this, worldFolder,
                                        pack.fileName, pack.enabled)))
                .show();
    }

    private void choosePack() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_PACK); }
        catch (Exception error) { showError("Não foi possível abrir o seletor de arquivos", error); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PACK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        setBusy(true, "Validando e instalando data pack…");
        io.execute(() -> {
            Exception failure = null;
            WorldDataPackManager.Pack installed = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                installed = WorldDataPackManager.installZip(this, worldFolder, input, name);
            } catch (Exception error) { failure = error; }
            final Exception error = failure;
            final WorldDataPackManager.Pack resultPack = installed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false, "");
                if (error != null) showError("Falha ao instalar data pack", error);
                else status.setText(UiLanguage.translate(this, "Data pack instalado e ativo: ")
                        + resultPack.fileName);
                refresh();
            });
        });
    }

    private void runOperation(String label, Operation action) {
        if (busy) return;
        setBusy(true, label);
        io.execute(() -> {
            Exception failure = null;
            try { action.run(); } catch (Exception error) { failure = error; }
            final Exception error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false, "");
                if (error != null) showError("Operação não concluída", error);
                else status.setText(UiLanguage.translate(this, "Data packs atualizados."));
                refresh();
            });
        });
    }

    private String displayName(Uri uri) {
        String name = "datapack.zip";
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception ignored) {}
        return name == null ? "datapack.zip" : name;
    }

    private void setBusy(boolean value, String label) {
        busy = value;
        if (progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
        if (status != null && value && label != null) status.setText(UiLanguage.translate(this, label));
        if (list != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                list.getChildAt(i).setAlpha(value ? 0.55f : 1f);
                setChildrenEnabled(list.getChildAt(i), !value);
            }
        }
    }

    private void setBusy(boolean value) { setBusy(value, ""); }

    private void setChildrenEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setChildrenEnabled(group.getChildAt(i), enabled);
        }
    }

    private interface Operation { void run() throws Exception; }

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
