package dev.mobilehorizon.app;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** Application preferences and the changelog for the current launcher build. */
public final class SettingsActivity extends Activity {
    private TextView autoStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        buildUi();
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

        page.addView(text("MOBILEHORIZON  /  SISTEMA", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("Configurações e novidades", 25, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text("Preferências do launcher e resumo dos recursos desta compilação.", 12, 0xffb9b3d1, false), top(-1, -2, 6));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 52, 12));

        LinearLayout preferences = card();
        preferences.addView(text("Preferências", 16, 0xfff0ecff, true), lp(-1, -2));
        preferences.addView(text("Idioma da interface", 13, 0xffb9b3d1, true), top(-1, -2, 9));
        preferences.addView(button("Alternar português / inglês", true, v -> UiLanguage.toggle(this)), top(-1, 50, 5));

        CheckBox autoUpdates = new CheckBox(this);
        autoUpdates.setText(UiLanguage.translate(this, "Verificar atualizações da runtime automaticamente"));
        autoUpdates.setTextColor(0xffeee9ff);
        autoUpdates.setTextSize(13);
        autoUpdates.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff9e88ff));
        autoUpdates.setChecked(HorizonRuntimeUpdateService.isEnabled(this));
        autoUpdates.setOnCheckedChangeListener((button, enabled) -> {
            HorizonRuntimeUpdateService.setEnabled(this, enabled);
            updateAutoStatus(enabled);
        });
        preferences.addView(autoUpdates, top(-1, 52, 9));
        autoStatus = text("", 11, 0xffaaa2c6, false);
        preferences.addView(autoStatus, top(-1, -2, 2));
        updateAutoStatus(autoUpdates.isChecked());
        page.addView(preferences, top(-1, -2, 12));

        LinearLayout notes = card();
        notes.addView(text("Novidades desta compilação", 16, 0xfff0ecff, true), lp(-1, -2));
        addNote(notes, "Runtime Horizon Nightly",
                "O launcher acompanha o canal Nightly oficial e prepara a runtime Android separadamente do APK.");
        addNote(notes, "Instâncias e mundos",
                "Perfis de jogo isolados, mundos por instância, backups ZIP e restauração validada.");
        addNote(notes, "Perfis e contas",
                "Vários perfis offline e contas Microsoft salvas com tokens criptografados por conta.");
        addNote(notes, "Skins e capas",
                "Gerenciamento de skins Classic e Slim, prévia da textura ativa e capas já pertencentes à conta.");
        addNote(notes, "Servidores favoritos",
                "Favoritos separados por instância e sincronização com a lista Multiplayer do Minecraft.");
        addNote(notes, "Biblioteca e sistema",
                "Catálogo de resource packs e shaders, galeria de capturas, console com filtro e exportação.");
        page.addView(notes, top(-1, -2, 12));

        LinearLayout limitations = card();
        limitations.addView(text("Limitações conhecidas", 15, 0xfff0ecff, true), lp(-1, -2));
        limitations.addView(text(
                "A runtime Android atual ainda não oferece um ponto de integração documentado para conexão direta a servidores; os favoritos são escritos em servers.dat para aparecerem na lista Multiplayer. A prévia de skin mostra a textura 64 × 64, não um personagem 3D giratório. Modpacks que exigem carregadores ou mods são bloqueados nesta linha vanilla.",
                11, 0xffaaa2c6, false), top(-1, -2, 6));
        page.addView(limitations, top(-1, -2, 12));

        page.addView(text("Versão instalada: " + appVersion(), 10, 0xff817a9e, false), top(-1, -2, 8));
    }

    private void updateAutoStatus(boolean enabled) {
        if (autoStatus == null) return;
        autoStatus.setText(UiLanguage.translate(this, enabled
                ? "As verificações automáticas estão ativas. A runtime é atualizada separadamente do APK."
                : "As verificações automáticas estão pausadas. A primeira instalação da runtime continua obrigatória; reative esta opção para voltar a receber verificações."));
    }

    private String appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "desconhecida" : info.versionName;
        } catch (Exception ignored) {
            return "desconhecida";
        }
    }

    private void addNote(LinearLayout parent, String title, String description) {
        parent.addView(text(title, 13, 0xffeee9ff, true), top(-1, -2, 9));
        parent.addView(text(description, 11, 0xffaaa2c6, false), top(-1, -2, 3));
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff151321);
        background.setCornerRadius(dp(7));
        background.setStroke(dp(1), 0xff37304f);
        view.setBackground(background);
        return view;
    }

    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(UiLanguage.translate(this, label));
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? 0xff5b3fd6 : 0xff2b2641);
        bg.setCornerRadius(dp(5));
        bg.setStroke(dp(1), primary ? 0xff8a74ff : 0xff3d3560);
        b.setBackground(bg);
        b.setOnClickListener(action);
        return b;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(UiLanguage.translate(this, value));
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        return view;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams top(int width, int height, int margin) {
        LinearLayout.LayoutParams params = lp(width, height);
        params.topMargin = dp(margin);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
