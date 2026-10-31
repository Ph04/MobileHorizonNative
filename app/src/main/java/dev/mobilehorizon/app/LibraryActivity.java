package dev.mobilehorizon.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** Centralizes launcher library tools. */
public final class LibraryActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        
        LauncherButtonStyle.install(this);
getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xff090911);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(28)); scroll.addView(page); setContentView(scroll);
        page.addView(text("MOBILEHORIZON  /  BIBLIOTECA", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("Biblioteca", 27, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text("Mundos, conteúdo local, capturas e ferramentas da instância ativa.", 13, 0xffb9b3d1, false), top(-1, -2, 6));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 52, 12));
        addEntry(page, "Mundos e backups", "Criar, restaurar e excluir mundos da instância ativa.", WorldsActivity.class);
        addEntry(page, "Servidores favoritos", "Guardar servidores e sincronizar a lista Multiplayer do jogo.", ServerFavoritesActivity.class);
        addEntry(page, "Conteúdo e pacotes", "Explorar resource packs, shaders e modpacks sem mods.", ContentBrowserActivity.class);
        addEntry(page, "Capturas de tela", "Visualizar, exportar e remover screenshots locais.", ScreenshotsActivity.class);
        addEntry(page, "Console e logs", "Pesquisar o log do Horizon e acompanhar novas linhas.", ConsoleActivity.class);
    }

    private void addEntry(LinearLayout page, String title, String description, Class<?> target) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xff151321); bg.setCornerRadius(dp(7)); bg.setStroke(dp(1), 0xff37304f); card.setBackground(bg);
        card.addView(text(title, 15, 0xfff0ecff, true), lp(-1, -2));
        card.addView(text(description, 12, 0xffb9b3d1, false), top(-1, -2, 4));
        card.addView(button("Abrir", true, v -> startActivity(new Intent(this, target))), top(-1, 50, 9));
        page.addView(card, top(-1, -2, 9));
    }
    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(UiLanguage.translate(this, label)); b.setTextSize(12); b.setTextColor(Color.WHITE);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(7), 0, dp(7), 0);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? 0xff5b3fd6 : 0xff2b2641); bg.setCornerRadius(dp(5)); bg.setStroke(dp(1), primary ? 0xff8a74ff : 0xff3d3560);
        b.setBackground(bg); b.setOnClickListener(action); return b;
    }
    private TextView text(String s, int size, int color, boolean bold) { TextView t=new TextView(this); t.setText(UiLanguage.translate(this,s)); t.setTextSize(size); t.setTextColor(color); t.setTypeface(bold?android.graphics.Typeface.DEFAULT_BOLD:android.graphics.Typeface.DEFAULT); return t; }
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams top(int w,int h,int margin){LinearLayout.LayoutParams p=lp(w,h);p.topMargin=dp(margin);return p;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
