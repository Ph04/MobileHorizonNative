package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import java.util.List;

/** Offline profile and saved Microsoft account selector. */
public final class ProfilesActivity extends Activity {
    private LinearLayout offlineList;
    private LinearLayout onlineList;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        
        LauncherButtonStyle.install(this);
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
        page.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("MOBILEHORIZON  /  PERFIS", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("Perfis e contas", 26, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text("Alterne entre identidades locais e contas Microsoft verificadas. Perfil offline não substitui autenticação nem licença.", 13, 0xffb9b3d1, false), top(-1, -2, 6));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 48, 12));

        LinearLayout offlineCard = card();
        offlineCard.addView(text("Perfis offline", 16, 0xfff0ecff, true), lp(-1, -2));
        offlineCard.addView(text("Use nicks locais diferentes. Cada nick continua sem autenticação online.", 12, 0xffb9b3d1, false), top(-1, -2, 4));
        offlineList = new LinearLayout(this);
        offlineList.setOrientation(LinearLayout.VERTICAL);
        offlineCard.addView(offlineList, top(-1, -2, 8));
        offlineCard.addView(button("Novo perfil offline", true, v -> createOffline()), top(-1, 52, 8));
        page.addView(offlineCard, top(-1, -2, 12));

        LinearLayout onlineCard = card();
        onlineCard.addView(text("Contas Microsoft", 16, 0xfff0ecff, true), lp(-1, -2));
        onlineCard.addView(text("Os tokens de cada conta ficam criptografados separadamente com chaves do Android Keystore.", 12, 0xffb9b3d1, false), top(-1, -2, 4));
        onlineList = new LinearLayout(this);
        onlineList.setOrientation(LinearLayout.VERTICAL);
        onlineCard.addView(onlineList, top(-1, -2, 8));
        onlineCard.addView(button("Gerenciar skins e capas", true,
                v -> startActivity(new Intent(this, SkinCapeActivity.class))), top(-1, 52, 8));
        page.addView(onlineCard, top(-1, -2, 12));
        page.addView(text("Para adicionar outra conta Microsoft, use “Entrar com Microsoft” na tela principal. A conta recém-verificada será adicionada sem apagar as anteriores.", 11, 0xffaaa2c6, false), top(-1, -2, 9));
    }

    private void refresh() {
        offlineList.removeAllViews();
        String selected = OfflineProfile.loadName(this);
        for (String name : OfflineProfile.listNames(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(name + (name.equalsIgnoreCase(selected) ? "  •  Em uso" : ""), 14, 0xffeee9ff, true);
            row.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1f));
            Button use = button("Usar", false, v -> {
                try { OfflineProfile.selectProfile(this, name); refresh(); }
                catch (Exception error) { showError(error.getMessage()); }
            });
            use.setEnabled(!name.equalsIgnoreCase(selected));
            row.addView(use, new LinearLayout.LayoutParams(dp(92), dp(46)));
            Button remove = button("Excluir", false, v -> confirmDeleteOffline(name));
            remove.setEnabled(!name.equalsIgnoreCase(selected) && OfflineProfile.listNames(this).size() > 1);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(96), dp(46));
            rp.leftMargin = dp(6);
            row.addView(remove, rp);
            offlineList.addView(row, top(-1, -2, 5));
        }

        onlineList.removeAllViews();
        List<MicrosoftAccountStore.Account> accounts = MicrosoftAccountStore.list(this);
        MicrosoftAccountStore.Account active = MicrosoftAccountStore.active(this);
        if (accounts.isEmpty()) {
            onlineList.addView(text("Nenhuma conta Microsoft verificada.", 12, 0xffb9b3d1, false), lp(-1, -2));
        }
        for (MicrosoftAccountStore.Account account : accounts) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            boolean isActive = active != null && active.uuid.equals(account.uuid);
            row.addView(text(account.name + (isActive ? "  •  Em uso" : ""), 14, 0xffeee9ff, true),
                    new LinearLayout.LayoutParams(0, dp(46), 1f));
            Button use = button("Usar", false, v -> {
                try { MicrosoftAccountStore.select(this, account.uuid); refresh(); }
                catch (Exception error) { showError(error.getMessage()); }
            });
            use.setEnabled(!isActive);
            row.addView(use, new LinearLayout.LayoutParams(dp(92), dp(46)));
            Button remove = button("Remover", false, v -> confirmRemoveMicrosoft(account));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(104), dp(46));
            rp.leftMargin = dp(6);
            row.addView(remove, rp);
            onlineList.addView(row, top(-1, -2, 5));
        }
    }

    private void createOffline() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        input.setHint("Nick (3–16 caracteres)");
        new AlertDialog.Builder(this).setTitle("Novo perfil offline").setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Criar", (d, w) -> {
                    try { OfflineProfile.createProfile(this, input.getText().toString()); refresh(); }
                    catch (Exception error) { showError(error.getMessage()); }
                }).show();
    }

    private void confirmDeleteOffline(String name) {
        new AlertDialog.Builder(this).setTitle("Excluir perfil?")
                .setMessage("O nick salvo será removido. Mundos e arquivos das instâncias não serão apagados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (d, w) -> {
                    try { OfflineProfile.deleteProfile(this, name); refresh(); }
                    catch (Exception error) { showError(error.getMessage()); }
                }).show();
    }

    private void confirmRemoveMicrosoft(MicrosoftAccountStore.Account account) {
        new AlertDialog.Builder(this).setTitle("Remover conta Microsoft?")
                .setMessage("O token criptografado desta conta será removido. As outras contas e os perfis offline serão preservados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Remover", (d, w) -> {
                    try {
                        EncryptedRefreshTokenStore.clear(this, account.uuid);
                        MicrosoftAccountStore.remove(this, account.uuid);
                        refresh();
                    } catch (Exception error) { showError(error.getMessage()); }
                }).show();
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setMessage(message == null ? "Operação não concluída." : message)
                .setPositiveButton("Fechar", null).show();
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff151321); bg.setCornerRadius(dp(7)); bg.setStroke(dp(1), 0xff37304f);
        v.setBackground(bg); return v;
    }

    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(UiLanguage.translate(this, label)); b.setTextSize(12);
        b.setTextColor(Color.WHITE); b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(7), 0, dp(7), 0);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? 0xff5b3fd6 : 0xff2b2641);
        bg.setCornerRadius(dp(5)); bg.setStroke(dp(1), primary ? 0xff8a74ff : 0xff3d3560);
        b.setBackground(bg); b.setOnClickListener(action); return b;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(UiLanguage.translate(this, s)); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        return t;
    }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams top(int w, int h, int margin) {
        LinearLayout.LayoutParams p = lp(w, h); p.topMargin = dp(margin); return p;
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
