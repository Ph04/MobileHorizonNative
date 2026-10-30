package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Skin and cape management through the signed-in player's official Minecraft profile. */
public final class SkinCapeActivity extends Activity {
    private static final int PICK_SKIN = 301;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout skins;
    private LinearLayout capes;
    private SkinModelPreviewView skinPreview;
    private TextView status;
    private ProgressBar progress;
    private JSONObject profile;
    private MicrosoftAuthentication.MinecraftAccount refreshed;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        buildUi();
        loadProfile();
    }
    @Override protected void onResume() {
        super.onResume();
        if (skinPreview != null) skinPreview.setAnimationEnabled(true);
    }

    @Override protected void onPause() {
        if (skinPreview != null) skinPreview.setAnimationEnabled(false);
        super.onPause();
    }

    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xff090911);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(28)); scroll.addView(page); setContentView(scroll);
        page.addView(text("MOBILEHORIZON  /  COSMÉTICOS", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("Skins e capas", 26, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text("Gerencie os cosméticos da conta Minecraft autenticada. As alterações são enviadas diretamente ao perfil oficial.", 13, 0xffb9b3d1, false), top(-1, -2, 6));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 48, 12));
        status = text("Conectando ao perfil Minecraft…", 12, 0xffc7bce6, false); page.addView(status, top(-1, -2, 8));
        progress = new ProgressBar(this); progress.setIndeterminate(true); page.addView(progress, top(-1, 4, 8));
        LinearLayout skinCard = card();
        skinCard.addView(text("Skins", 16, 0xfff0ecff, true), lp(-1, -2));
        skinPreview = new SkinModelPreviewView(this);
        skinPreview.setContentDescription(UiLanguage.translate(this,
                "Prévia 3D da skin ativa. Arraste para girar o avatar inteiro."));
        skinCard.addView(skinPreview, top(-1, 224, 8));
        skinCard.addView(text("O avatar gira como um único modelo. Arraste para controlar a rotação; o modelo clássico e o slim são detectados automaticamente.", 11, 0xffaaa2c6, false), top(-1, -2, 4));
        skinCard.addView(button("Importar skin PNG 64 × 64", true, v -> pickSkin()), top(-1, 50, 8));
        skinCard.addView(button("Redefinir skin", false, v -> resetSkin()), top(-1, 46, 6));
        skins = new LinearLayout(this); skins.setOrientation(LinearLayout.VERTICAL); skinCard.addView(skins, top(-1, -2, 8));
        page.addView(skinCard, top(-1, -2, 12));
        LinearLayout capeCard = card();
        capeCard.addView(text("Capas", 16, 0xfff0ecff, true), lp(-1, -2));
        capeCard.addView(button("Ocultar capa ativa", false, v -> hideCape()), top(-1, 48, 8));
        capes = new LinearLayout(this); capes.setOrientation(LinearLayout.VERTICAL); capeCard.addView(capes, top(-1, -2, 8));
        page.addView(capeCard, top(-1, -2, 12));
        page.addView(text("Apenas skins PNG de 64 × 64 são aceitas. O modelo clássico (Steve) e o slim (Alex) são selecionáveis. Capas só podem ser ativadas quando já pertencem à conta.", 11, 0xffaaa2c6, false), top(-1, -2, 10));
    }

    private void loadProfile() {
        setBusy(true); status.setText("Atualizando sessão e lendo perfil…");
        io.execute(() -> {
            JSONObject loaded = null; MicrosoftAuthentication.MinecraftAccount account = null; Exception failure = null;
            try {
                account = MinecraftCosmeticsService.refreshAccount(this);
                loaded = MinecraftCosmeticsService.fetchProfile(account.minecraftAccessToken);
            } catch (Exception error) { failure = error; }
            final JSONObject result = loaded; final MicrosoftAuthentication.MinecraftAccount updated = account; final Exception problem = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (problem != null) { status.setText("Falha: " + message(problem)); return; }
                profile = result; refreshed = updated; status.setText("Perfil: " + result.optString("name", updated.name));
                renderProfile();
            });
        });
    }

    private void renderProfile() {
        skins.removeAllViews(); capes.removeAllViews();
        JSONArray skinList = profile.optJSONArray("skins");
        JSONObject activeSkin = null;
        if (skinList == null || skinList.length() == 0) skins.addView(text("Nenhuma skin listada pelo serviço oficial.", 12, 0xffb9b3d1, false), lp(-1, -2));
        else for (int i = 0; i < skinList.length(); i++) {
            JSONObject skin = skinList.optJSONObject(i); if (skin == null) continue;
            String url = skin.optString("url", "");
            String variant = skin.optString("variant", "CLASSIC");
            boolean active = "ACTIVE".equalsIgnoreCase(skin.optString("state", ""));
            if (active) activeSkin = skin;
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = text((active ? "● " : "") + variant + (active ? " • ATIVA" : ""), 12, 0xffeee9ff, true);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(46), 1f));
            Button use = button("Aplicar", false, v -> selectSkin(url, variant));
            use.setEnabled(!active && !url.isEmpty());
            row.addView(use, new LinearLayout.LayoutParams(dp(104), dp(46)));
            skins.addView(row, top(-1, -2, 5));
        }
        if (activeSkin != null) {
            loadSkinPreview(activeSkin.optString("url", ""),
                    activeSkin.optString("variant", "CLASSIC"));
        } else if (skinPreview != null) {
            skinPreview.setSkin(null, "CLASSIC");
        }

        JSONArray capeList = profile.optJSONArray("capes");
        if (capeList == null || capeList.length() == 0) capes.addView(text("Esta conta não possui capas disponíveis.", 12, 0xffb9b3d1, false), lp(-1, -2));
        else for (int i = 0; i < capeList.length(); i++) {
            JSONObject cape = capeList.optJSONObject(i); if (cape == null) continue;
            String id = cape.optString("id", "");
            boolean active = "ACTIVE".equalsIgnoreCase(cape.optString("state", ""));
            String name = cape.optString("alias", cape.optString("name", "Capa"));
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(text(name + (active ? " • ATIVA" : ""), 12, 0xffeee9ff, true),
                    new LinearLayout.LayoutParams(0, dp(46), 1f));
            Button use = button("Ativar", false, v -> activateCape(id));
            use.setEnabled(!active && !id.isEmpty()); row.addView(use, new LinearLayout.LayoutParams(dp(104), dp(46)));
            capes.addView(row, top(-1, -2, 5));
        }
    }

    private void loadSkinPreview(String textureUrl, String variant) {
        if (skinPreview == null || textureUrl == null || textureUrl.isEmpty()) return;
        skinPreview.setTag(textureUrl);
        io.execute(() -> {
            Bitmap bitmap = null;
            Exception failure = null;
            try { bitmap = MinecraftCosmeticsService.fetchSkinTexture(textureUrl); }
            catch (Exception error) { failure = error; }
            final Bitmap preview = bitmap;
            final Exception problem = failure;
            final String modelVariant = variant == null ? "CLASSIC" : variant;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || skinPreview == null
                        || !textureUrl.equals(skinPreview.getTag())) return;
                if (problem == null) skinPreview.setSkin(preview, modelVariant);
                else status.setText("Prévia indisponível: " + message(problem));
            });
        });
    }

    private void pickSkin() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/png"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_SKIN);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_SKIN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            new AlertDialog.Builder(this).setTitle("Modelo da skin")
                    .setItems(new String[]{"Clássico (Steve)", "Slim (Alex)"}, (d, which) ->
                            uploadSkin(uri, which == 0 ? "classic" : "slim")).show();
        }
    }

    private void uploadSkin(Uri uri, String variant) {
        runOperation("Enviando skin para a conta…", () -> {
            if (refreshed == null) throw new IllegalStateException("Atualize o perfil antes de enviar uma skin.");
            return MinecraftCosmeticsService.uploadSkin(this, uri, refreshed.minecraftAccessToken, variant);
        });
    }

    private void selectSkin(String url, String variant) {
        runOperation("Aplicando skin…", () -> MinecraftCosmeticsService.selectSkin(
                refreshed.minecraftAccessToken, url, "SLIM".equalsIgnoreCase(variant) ? "slim" : "classic"));
    }

    private void activateCape(String id) {
        runOperation("Ativando capa…", () -> MinecraftCosmeticsService.activateCape(refreshed.minecraftAccessToken, id));
    }

    private void hideCape() {
        runOperation("Ocultando capa…", () -> MinecraftCosmeticsService.hideCape(refreshed.minecraftAccessToken));
    }

    private void resetSkin() {
        new AlertDialog.Builder(this).setTitle("Redefinir skin?")
                .setMessage("A skin atual será removida do perfil e voltará ao modelo padrão.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Redefinir", (d, w) -> runOperation("Redefinindo skin…",
                        () -> MinecraftCosmeticsService.resetSkin(refreshed.minecraftAccessToken)))
                .show();
    }

    private interface JsonOperation { JSONObject run() throws Exception; }
    private void runOperation(String label, JsonOperation operation) {
        if (busy) return; setBusy(true); status.setText(label);
        io.execute(() -> {
            JSONObject response = null; Exception failure = null;
            try { response = operation.run(); } catch (Exception error) { failure = error; }
            if (refreshed != null) {
                try { profile = MinecraftCosmeticsService.fetchProfile(refreshed.minecraftAccessToken); }
                catch (Exception error) { if (failure == null) failure = error; }
            }
            final Exception problem = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (problem != null) status.setText("Falha: " + message(problem));
                else { status.setText("Alteração aplicada ao perfil Minecraft."); if (profile != null) renderProfile(); }
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        if (progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }
    private String message(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xff151321); bg.setCornerRadius(dp(7)); bg.setStroke(dp(1), 0xff37304f); v.setBackground(bg); return v;
    }
    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(UiLanguage.translate(this, label)); b.setTextSize(12); b.setTextColor(Color.WHITE);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(7), 0, dp(7), 0);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? 0xff5b3fd6 : 0xff2b2641); bg.setCornerRadius(dp(5)); bg.setStroke(dp(1), primary ? 0xff8a74ff : 0xff3d3560);
        b.setBackground(bg); b.setOnClickListener(action); return b;
    }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(UiLanguage.translate(this, s)); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT); return t;
    }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams top(int w, int h, int margin) { LinearLayout.LayoutParams p = lp(w, h); p.topMargin = dp(margin); return p; }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
