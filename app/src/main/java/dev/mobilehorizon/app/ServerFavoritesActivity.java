package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import java.io.File;
import java.util.List;
import java.util.ArrayList;

public final class ServerFavoritesActivity extends Activity {
    private LinearLayout list;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setStatusBarColor(0xff090911); getWindow().setNavigationBarColor(0xff090911);
        buildUi(); refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xff090911);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(page); setContentView(scroll);
        page.addView(text("MOBILEHORIZON  /  MULTIPLAYER", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("Servidores favoritos", 26, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text("Salve endereços por instância e sincronize-os com a lista Multiplayer lida pelo Minecraft.", 13, 0xffb9b3d1, false), top(-1, -2, 6));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 48, 12));
        page.addView(button("Adicionar servidor", true, v -> addDialog()), top(-1, 52, 8));
        page.addView(button("Sincronizar lista no Minecraft", false, v -> sync()), top(-1, 52, 7));
        status = text("", 12, 0xffc7bce6, false); page.addView(status, top(-1, -2, 8));
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); page.addView(list, top(-1, -2, 4));
        page.addView(text("Entrar inicia o cliente diretamente no servidor. Para servidores online, selecione uma conta Microsoft com licença Java; o modo offline depende da configuração do servidor.", 11, 0xffaaa2c6, false), top(-1, -2, 12));
    }

    private void refresh() {
        list.removeAllViews();
        List<ServerFavoriteStore.Favorite> favorites = ServerFavoriteStore.list(this);
        status.setText("Favoritos nesta instância: " + favorites.size());
        for (ServerFavoriteStore.Favorite server : favorites) {
            LinearLayout card = card();
            card.addView(text(server.name, 15, 0xfff0ecff, true), lp(-1, -2));
            card.addView(text(server.address, 12, 0xffaaa2c6, false), top(-1, -2, 3));
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); card.addView(row, top(-1, -2, 8));
            Button join = button("Entrar", true, v -> {
                Intent intent = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_QUICK_PLAY_SERVER, server.address);
                startActivity(intent);
            });
            row.addView(join, new LinearLayout.LayoutParams(0, dp(48), 1f));
            Button copy = button("Copiar endereço", false, v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Servidor Minecraft", server.address));
                status.setText("Endereço copiado.");
            });
            LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
            copyParams.leftMargin = dp(6);
            row.addView(copy, copyParams);
            Button remove = button("Excluir", false, v -> confirmDelete(server));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            rp.leftMargin = dp(6);
            row.addView(remove, rp);
            list.addView(card, top(-1, -2, 8));
        }
    }

    private void addDialog() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12), dp(6), dp(12), 0);
        EditText name = new EditText(this); name.setSingleLine(true); name.setHint("Nome do servidor"); panel.addView(name, lp(-1, -2));
        EditText address = new EditText(this); address.setSingleLine(true); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setHint("endereco.exemplo:25565"); panel.addView(address, top(-1, -2, 6));
        new AlertDialog.Builder(this).setTitle("Adicionar favorito").setView(panel)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", (d, w) -> {
                    try { ServerFavoriteStore.add(this, name.getText().toString(), address.getText().toString()); refresh(); }
                    catch (Exception error) { showError(error.getMessage()); }
                }).show();
    }

    private void confirmDelete(ServerFavoriteStore.Favorite server) {
        new AlertDialog.Builder(this).setTitle("Excluir favorito?")
                .setMessage("Remover " + server.name + " da lista deste perfil?")
                .setNegativeButton("Cancelar", null).setPositiveButton("Excluir", (d, w) -> {
                    ServerFavoriteStore.delete(this, server.address); refresh();
                }).show();
    }

    private void sync() {
        try {
            List<ServerFavoriteStore.Favorite> favorites = ServerFavoriteStore.list(this);
            ArrayList<MinecraftServerList.Server> entries = new ArrayList<>();
            for (ServerFavoriteStore.Favorite item : favorites) entries.add(new MinecraftServerList.Server(item.name, item.address));
            File target = new File(GameInstanceManager.getSelectedInstanceHome(this), "servers.dat");
            MinecraftServerList.merge(target, entries);
            status.setText("Lista sincronizada em servers.dat. Os servidores existentes foram preservados.");
        } catch (Exception error) { showError(error.getMessage()); }
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle("Não foi possível concluir").setMessage(message == null ? "Erro desconhecido." : message)
                .setPositiveButton("Fechar", null).show();
    }
    private LinearLayout card() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(13), dp(12), dp(13), dp(12)); GradientDrawable bg = new GradientDrawable(); bg.setColor(0xff151321); bg.setCornerRadius(dp(7)); bg.setStroke(dp(1), 0xff37304f); v.setBackground(bg); return v; }
    private Button button(String label, boolean primary, android.view.View.OnClickListener action) { Button b=new Button(this); b.setAllCaps(false); b.setText(UiLanguage.translate(this, label)); b.setTextSize(12); b.setTextColor(Color.WHITE); b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(7),0,dp(7),0); GradientDrawable bg=new GradientDrawable(); bg.setColor(primary?0xff5b3fd6:0xff2b2641); bg.setCornerRadius(dp(5)); bg.setStroke(dp(1),primary?0xff8a74ff:0xff3d3560); b.setBackground(bg); b.setOnClickListener(action); return b; }
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(UiLanguage.translate(this,s));t.setTextSize(size);t.setTextColor(color);t.setTypeface(bold?android.graphics.Typeface.DEFAULT_BOLD:android.graphics.Typeface.DEFAULT);return t;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams top(int w,int h,int margin){LinearLayout.LayoutParams p=lp(w,h);p.topMargin=dp(margin);return p;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
