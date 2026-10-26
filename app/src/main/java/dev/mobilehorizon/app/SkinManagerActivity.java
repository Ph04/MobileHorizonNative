package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Imports local Minecraft Java skins, stores them privately and previews Steve/Alex models. */
public final class SkinManagerActivity extends Activity {
    static final String PREFERENCES = "mobilehorizon_skins";
    static final String KEY_SKIN_LIST = "skins_v1";
    static final String KEY_ACTIVE_SKIN_ID = "active_skin_id";
    static final String KEY_ACTIVE_SKIN_NAME = "active_skin_name";
    static final String KEY_ACTIVE_SKIN_MODEL = "active_skin_model";
    static final String MODEL_CLASSIC = "classic";
    static final String MODEL_SLIM = "slim";
    private static final int REQUEST_IMPORT_SKIN = 6207;
    private static final Pattern SAFE_ID = Pattern.compile("skin_[0-9a-f]{32}");

    private final List<SkinEntry> entries = new ArrayList<>();
    private LinearLayout skinList;
    private SkinPreviewView preview;
    private TextView statusText;
    private TextView selectionTitle;
    private Button classicModelButton;
    private Button slimModelButton;
    private Button applyButton;
    private Button deleteButton;
    private String selectedId = "";
    private Bitmap previewBitmap;

    private static final class SkinEntry {
        final String id;
        String name;
        String model;
        SkinEntry(String id, String name, String model) {
            this.id = id;
            this.name = name;
            this.model = safeModel(model);
        }
    }

    static File skinDirectory(Context context) {
        return new File(context.getFilesDir(), "skins");
    }

    /** Returns only a path contained by the app-private skin directory. */
    static File activeSkinFile(Context context) {
        android.content.SharedPreferences prefs =
                context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_ACTIVE_SKIN_ID, "");
        if (id == null || !SAFE_ID.matcher(id).matches()) return null;
        try {
            File root = skinDirectory(context).getCanonicalFile();
            File file = new File(root, id + ".png").getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator)
                    || !file.isFile() || file.length() <= 0) return null;
            return file;
        } catch (IOException error) {
            return null;
        }
    }

    static String activeSkinModel(Context context) {
        android.content.SharedPreferences prefs =
                context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return safeModel(prefs.getString(KEY_ACTIVE_SKIN_MODEL, MODEL_CLASSIC));
    }

    private static String safeModel(String model) {
        return MODEL_SLIM.equals(model) ? MODEL_SLIM : MODEL_CLASSIC;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff0b0a11);
        getWindow().setNavigationBarColor(0xff0b0a11);
        buildLayout();
        loadEntries();
        String active = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(KEY_ACTIVE_SKIN_ID, "");
        if (active != null && entries.stream().anyMatch(entry -> entry.id.equals(active))) {
            selectedId = active;
        } else if (!entries.isEmpty()) {
            selectedId = entries.get(0).id;
        }
        refreshContent();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0f0d17);
        root.setPadding(dp(14), dp(10), dp(14), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", false);
        back.setTextSize(28);
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        back.setOnClickListener(v -> finish());
        TextView title = label("Gerenciador de skins", 21, 0xfff2edff, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = dp(9);
        header.addView(title, titleParams);
        Button importButton = button("Importar PNG", true);
        importButton.setOnClickListener(v -> chooseSkinFile());
        header.addView(importButton, new LinearLayout.LayoutParams(-2, dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        statusText = label("Importe uma skin PNG de 64×64 ou 64×32.", 12, 0xffb8b0ce, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(5);
        statusParams.bottomMargin = dp(8);
        root.addView(statusText, statusParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        LinearLayout previewCard = card();
        previewCard.setOrientation(LinearLayout.VERTICAL);
        body.addView(previewCard, new LinearLayout.LayoutParams(-1, -2));
        selectionTitle = label("Pré-visualização", 15, 0xfff2edff, true);
        previewCard.addView(selectionTitle, new LinearLayout.LayoutParams(-1, -2));
        preview = new SkinPreviewView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(205));
        previewParams.topMargin = dp(6);
        previewCard.addView(preview, previewParams);
        TextView previewHint = label("Arraste o personagem para girar a visualização.", 11, 0xffaaa4c2, false);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(4);
        previewCard.addView(previewHint, hintParams);

        TextView modelHeading = label("Modelo da skin", 13, 0xffded7f7, true);
        LinearLayout.LayoutParams modelHeadingParams = new LinearLayout.LayoutParams(-1, -2);
        modelHeadingParams.topMargin = dp(12);
        previewCard.addView(modelHeading, modelHeadingParams);
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        classicModelButton = button("Steve · Clássico", false);
        slimModelButton = button("Alex · Slim", false);
        LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        choiceParams.rightMargin = dp(5);
        modelRow.addView(classicModelButton, choiceParams);
        LinearLayout.LayoutParams slimParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        slimParams.leftMargin = dp(5);
        modelRow.addView(slimModelButton, slimParams);
        previewCard.addView(modelRow, new LinearLayout.LayoutParams(-1, -2));
        classicModelButton.setOnClickListener(v -> changeSelectedModel(MODEL_CLASSIC));
        slimModelButton.setOnClickListener(v -> changeSelectedModel(MODEL_SLIM));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = dp(8);
        previewCard.addView(actions, actionParams);
        applyButton = button("Aplicar skin", true);
        deleteButton = button("Excluir", false);
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        applyParams.rightMargin = dp(5);
        actions.addView(applyButton, applyParams);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        deleteParams.leftMargin = dp(5);
        actions.addView(deleteButton, deleteParams);
        applyButton.setOnClickListener(v -> applySelectedSkin());
        deleteButton.setOnClickListener(v -> confirmDeleteSelected());

        Button clearActive = button("Voltar à skin padrão da conta", false);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-1, dp(42));
        clearParams.topMargin = dp(7);
        previewCard.addView(clearActive, clearParams);
        clearActive.setOnClickListener(v -> clearActiveSkin());

        TextView listHeading = label("Suas skins", 16, 0xfff2edff, true);
        LinearLayout.LayoutParams listHeadingParams = new LinearLayout.LayoutParams(-1, -2);
        listHeadingParams.topMargin = dp(15);
        listHeadingParams.bottomMargin = dp(6);
        body.addView(listHeading, listHeadingParams);
        skinList = new LinearLayout(this);
        skinList.setOrientation(LinearLayout.VERTICAL);
        body.addView(skinList, new LinearLayout.LayoutParams(-1, -2));

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void chooseSkinFile() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/png");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(picker, REQUEST_IMPORT_SKIN);
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível abrir o seletor de arquivos.", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_SKIN || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            String name = displayName(data.getData());
            Bitmap normalized = readSkin(data.getData());
            String id = "skin_" + UUID.randomUUID().toString().replace("-", "");
            File directory = skinDirectory(this);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                normalized.recycle();
                throw new IOException("Não foi possível criar a pasta privada de skins.");
            }
            File target = new File(directory, id + ".png");
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                if (!normalized.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("A skin não pôde ser gravada como PNG.");
                }
                output.flush();
                output.getFD().sync();
            } finally {
                normalized.recycle();
            }
            String extensionRemoved = name.replaceFirst("(?i)\\.png$", "").trim();
            if (extensionRemoved.isEmpty()) extensionRemoved = "Skin " + (entries.size() + 1);
            if (extensionRemoved.length() > 42) extensionRemoved = extensionRemoved.substring(0, 42);
            entries.add(0, new SkinEntry(id, extensionRemoved, MODEL_CLASSIC));
            selectedId = id;
            saveEntries();
            refreshContent();
            Toast.makeText(this, "Skin importada. Escolha Steve ou Alex e toque em Aplicar skin.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            new AlertDialog.Builder(this)
                    .setTitle("Skin inválida")
                    .setMessage(error.getMessage() == null
                            ? "Não foi possível importar esta imagem." : error.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {}
        return "Skin importada";
    }

    private Bitmap readSkin(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("O arquivo selecionado não pôde ser aberto.");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (!isSupportedSkinDimensions(bounds.outWidth, bounds.outHeight)) {
            throw new IOException("Use uma skin Java PNG de 64×64 pixels ou o formato clássico 64×32.");
        }
        Bitmap raw;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("O arquivo selecionado não pôde ser aberto novamente.");
            raw = BitmapFactory.decodeStream(input, null, options);
        }
        if (raw == null) throw new IOException("O arquivo não é um PNG de imagem válido.");
        try {
            if (raw.getWidth() == 64 && raw.getHeight() == 64) {
                return raw.copy(Bitmap.Config.ARGB_8888, true);
            }
            return normalizeLegacySkin(raw);
        } finally {
            raw.recycle();
        }
    }

    static boolean isSupportedSkinDimensions(int width, int height) {
        return width == 64 && (height == 64 || height == 32);
    }

    /** Expands a legacy 64×32 skin using the same per-face mirrors as Horizon's decoder. */
    private Bitmap normalizeLegacySkin(Bitmap source) {
        Bitmap result = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint pixels = new Paint();
        pixels.setFilterBitmap(false);
        pixels.setDither(false);
        canvas.drawBitmap(source, new Rect(0, 0, 64, 32),
                new Rect(0, 0, 64, 32), pixels);

        // Source x/y, destination x offset from source x, destination y, width, height.
        int[][] mirroredFaces = {
                {4, 16, 16, 32, 4, 4},
                {8, 16, 16, 32, 4, 4},
                {0, 20, 24, 32, 4, 12},
                {4, 20, 16, 32, 4, 12},
                {8, 20, 8, 32, 4, 12},
                {12, 20, 16, 32, 4, 12},
                {44, 16, -8, 32, 4, 4},
                {48, 16, -8, 32, 4, 4},
                {40, 20, 0, 32, 4, 12},
                {44, 20, -8, 32, 4, 12},
                {48, 20, -16, 32, 4, 12},
                {52, 20, -8, 32, 4, 12}
        };
        for (int[] face : mirroredFaces) {
            int sx = face[0], sy = face[1], dxOffset = face[2], dy = face[3];
            int width = face[4], height = face[5];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int src = source.getPixel(sx + x, sy + y);
                    int dstX = sx + dxOffset + width - 1 - x;
                    result.setPixel(dstX, dy + y, src);
                }
            }
        }

        // Base skin parts are opaque in legacy skins; the outer hat layer is preserved when it
        // actually contains transparency. Match the decoder's legacy opaque-hat workaround.
        forceOpaque(result, 0, 0, 32, 16);
        boolean opaqueHat = true;
        for (int x = 32; x < 64 && opaqueHat; x++) {
            for (int y = 0; y < 32; y++) {
                if (Color.alpha(result.getPixel(x, y)) < 128) {
                    opaqueHat = false;
                    break;
                }
            }
        }
        if (opaqueHat) {
            for (int x = 32; x < 64; x++) {
                for (int y = 0; y < 32; y++) {
                    result.setPixel(x, y, result.getPixel(x, y) & 0x00ffffff);
                }
            }
        }
        forceOpaque(result, 0, 16, 64, 32);
        forceOpaque(result, 16, 48, 48, 64);
        return result;
    }

    private void forceOpaque(Bitmap bitmap, int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                bitmap.setPixel(x, y, bitmap.getPixel(x, y) | 0xff000000);
            }
        }
    }

    private void loadEntries() {
        entries.clear();
        String json = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_SKIN_LIST, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String id = object.optString("id", "");
                if (!SAFE_ID.matcher(id).matches() || !skinFile(id).isFile()) continue;
                String name = object.optString("name", "Skin importada");
                if (name.trim().isEmpty()) name = "Skin importada";
                entries.add(new SkinEntry(id, name, object.optString("model", MODEL_CLASSIC)));
            }
        } catch (Exception error) {
            android.util.Log.w("MobileHorizonSkins", "A lista de skins salva não pôde ser lida.", error);
        }
    }

    private void saveEntries() {
        JSONArray array = new JSONArray();
        for (SkinEntry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", entry.id);
                object.put("name", entry.name);
                object.put("model", safeModel(entry.model));
                array.put(object);
            } catch (org.json.JSONException impossible) {
                android.util.Log.w("MobileHorizonSkins", "Skin omitida ao salvar metadados.", impossible);
            }
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(KEY_SKIN_LIST, array.toString()).commit();
    }

    private File skinFile(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) return new File(getFilesDir(), "invalid-skin");
        return new File(skinDirectory(this), id + ".png");
    }

    private SkinEntry selectedEntry() {
        for (SkinEntry entry : entries) if (entry.id.equals(selectedId)) return entry;
        return null;
    }

    private void changeSelectedModel(String model) {
        SkinEntry selected = selectedEntry();
        if (selected == null) return;
        selected.model = safeModel(model);
        saveEntries();
        refreshContent();
    }

    private void refreshContent() {
        SkinEntry selected = selectedEntry();
        boolean hasSelected = selected != null && skinFile(selected.id).isFile();
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        if (hasSelected) previewBitmap = BitmapFactory.decodeFile(skinFile(selected.id).getAbsolutePath());
        preview.setSkin(previewBitmap, hasSelected && MODEL_SLIM.equals(selected.model));
        selectionTitle.setText(hasSelected ? selected.name : "Pré-visualização");
        String activeId = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(KEY_ACTIVE_SKIN_ID, "");
        if (activeId != null && !activeId.isEmpty()) {
            SkinEntry active = entries.stream().filter(entry -> entry.id.equals(activeId))
                    .findFirst().orElse(null);
            statusText.setText(active == null ? "A skin ativa não está disponível; será usada a padrão."
                    : "Ativa: " + active.name + " · " + modelName(active.model)
                        + ". A mudança entra na próxima abertura do jogo.");
        } else {
            statusText.setText("Nenhuma skin personalizada ativa; será usada a skin do perfil/servidor.");
        }
        boolean classic = hasSelected && MODEL_CLASSIC.equals(selected.model);
        setButtonSelected(classicModelButton, classic);
        setButtonSelected(slimModelButton, hasSelected && MODEL_SLIM.equals(selected.model));
        applyButton.setEnabled(hasSelected);
        deleteButton.setEnabled(hasSelected);
        renderList(activeId == null ? "" : activeId);
    }

    private void renderList(String activeId) {
        skinList.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = label("Nenhuma skin importada ainda. Toque em Importar PNG para escolher uma imagem do dispositivo.",
                    13, 0xffaaa4c2, false);
            empty.setPadding(dp(5), dp(8), dp(5), dp(12));
            skinList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (SkinEntry entry : entries) {
            boolean selected = entry.id.equals(selectedId);
            boolean active = entry.id.equals(activeId);
            LinearLayout card = card();
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(selected ? 0xff242039 : 0xff171522);
            bg.setCornerRadius(dp(9));
            bg.setStroke(dp(1), selected ? 0xff8e63ff : 0xff373248);
            card.setBackground(bg);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(6);
            skinList.addView(card, cardParams);

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(entry.name, 13, 0xfff2edff, true);
            TextView meta = label((active ? "● Ativa · " : "") + modelName(entry.model), 11,
                    active ? 0xffb8ffd5 : 0xffaaa4c2, false);
            details.addView(name, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
            metaParams.topMargin = dp(2);
            details.addView(meta, metaParams);
            LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1f);
            detailsParams.rightMargin = dp(8);
            card.addView(details, detailsParams);

            Button select = button(selected ? "Selecionada" : "Selecionar", selected);
            select.setOnClickListener(v -> {
                selectedId = entry.id;
                refreshContent();
            });
            card.addView(select, new LinearLayout.LayoutParams(-2, dp(38)));
        }
    }

    private void applySelectedSkin() {
        SkinEntry selected = selectedEntry();
        if (selected == null || !skinFile(selected.id).isFile()) return;
        boolean saved = getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE_SKIN_ID, selected.id)
                .putString(KEY_ACTIVE_SKIN_NAME, selected.name)
                .putString(KEY_ACTIVE_SKIN_MODEL, safeModel(selected.model))
                .commit();
        if (!saved) {
            Toast.makeText(this, "Não foi possível salvar a skin ativa.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Skin aplicada. Feche e abra o jogo para atualizar o personagem.",
                Toast.LENGTH_LONG).show();
        refreshContent();
    }

    private void clearActiveSkin() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .remove(KEY_ACTIVE_SKIN_ID)
                .remove(KEY_ACTIVE_SKIN_NAME)
                .remove(KEY_ACTIVE_SKIN_MODEL)
                .commit();
        Toast.makeText(this, "Skin personalizada desativada.", Toast.LENGTH_SHORT).show();
        refreshContent();
    }

    private void confirmDeleteSelected() {
        SkinEntry selected = selectedEntry();
        if (selected == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Excluir skin?")
                .setMessage("A imagem “" + selected.name + "” será removida do armazenamento privado do app.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (dialog, which) -> deleteSelected(selected))
                .show();
    }

    private void deleteSelected(SkinEntry selected) {
        String activeId = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(KEY_ACTIVE_SKIN_ID, "");
        if (selected.id.equals(activeId)) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .remove(KEY_ACTIVE_SKIN_ID)
                    .remove(KEY_ACTIVE_SKIN_NAME)
                    .remove(KEY_ACTIVE_SKIN_MODEL)
                    .commit();
        }
        File file = skinFile(selected.id);
        if (file.exists() && !file.delete()) {
            Toast.makeText(this, "Não foi possível excluir o arquivo da skin.", Toast.LENGTH_LONG).show();
            return;
        }
        entries.remove(selected);
        selectedId = entries.isEmpty() ? "" : entries.get(0).id;
        saveEntries();
        refreshContent();
    }

    private static String modelName(String model) {
        return MODEL_SLIM.equals(model) ? "Alex · Slim" : "Steve · Clássico";
    }

    private void setButtonSelected(Button button, boolean selected) {
        button.setBackground(background(selected ? 0xff6843b8 : 0xff302a43,
                selected ? 0xffaa90ff : 0xff4b4560, 9));
        button.setTextColor(0xfff7f3ff);
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(0xfff7f3ff);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(background(primary ? 0xff6843b8 : 0xff302a43,
                primary ? 0xffaa90ff : 0xff4b4560, 9));
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setPadding(dp(11), dp(10), dp(11), dp(10));
        card.setBackground(background(0xff171522, 0xff373248, 12));
        return card;
    }

    private GradientDrawable background(int color, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private TextView label(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        if (previewBitmap != null && !previewBitmap.isRecycled()) previewBitmap.recycle();
        previewBitmap = null;
        super.onDestroy();
    }

    /** Pixel-art cuboid preview with real skin UV regions and draggable yaw rotation. */
    private final class SkinPreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bitmapPaint = new Paint();
        private final Paint sidePaint = new Paint();
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap skin;
        private boolean slim;
        private float yaw = 0.30f;
        private float previousX;

        SkinPreviewView(Context context) {
            super(context);
            bitmapPaint.setFilterBitmap(false);
            bitmapPaint.setDither(false);
            sidePaint.setFilterBitmap(false);
            sidePaint.setDither(false);
            sidePaint.setColorFilter(new PorterDuffColorFilter(0xffb7b7b7, PorterDuff.Mode.MULTIPLY));
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setSkin(Bitmap bitmap, boolean slim) {
            skin = bitmap;
            this.slim = slim;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            canvas.drawColor(0xff151321);
            paint.setShader(new android.graphics.LinearGradient(0, 0, w, h,
                    0xff222039, 0xff11101a, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setColor(0x663b3159);
            canvas.drawOval(new RectF(w * 0.22f, h - 23f * density, w * 0.78f,
                    h - 10f * density), paint);
            if (skin == null || skin.isRecycled()) {
                textPaint.setColor(0xffc3badf);
                textPaint.setTextSize(13f * density);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Importe uma skin para visualizar", w / 2f, h / 2f, textPaint);
                return;
            }

            float unit = Math.min(4.4f * density, (h - 35f * density) / 32f);
            float center = w / 2f;
            float baseY = h - 17f * density;
            float sideRatio = Math.abs((float) Math.sin(yaw));
            float frontRatio = Math.max(0.30f, Math.abs((float) Math.cos(yaw)));
            Rect none = null;

            // Legs and torso are drawn first; arms and head sit above them.
            drawPart(canvas, center - 2f * unit, baseY - 12f * unit, 4, 12, 4,
                    new Rect(4, 20, 8, 32), new Rect(0, 20, 4, 32), new Rect(8, 20, 12, 32),
                    new Rect(4, 36, 8, 48), new Rect(0, 36, 4, 48), new Rect(8, 36, 12, 48),
                    unit, frontRatio, sideRatio, yaw);
            drawPart(canvas, center + 2f * unit, baseY - 12f * unit, 4, 12, 4,
                    new Rect(20, 52, 24, 64), new Rect(16, 52, 20, 64), new Rect(24, 52, 28, 64),
                    new Rect(4, 52, 8, 64), new Rect(0, 52, 4, 64), new Rect(8, 52, 12, 64),
                    unit, frontRatio, sideRatio, yaw);
            drawPart(canvas, center, baseY - 24f * unit, 8, 12, 4,
                    new Rect(20, 20, 28, 32), new Rect(16, 20, 20, 32), new Rect(28, 20, 32, 32),
                    new Rect(20, 36, 28, 48), new Rect(16, 36, 20, 48), new Rect(28, 36, 32, 48),
                    unit, frontRatio, sideRatio, yaw);

            float armWidth = slim ? 3f : 4f;
            float armOffset = 4f + armWidth / 2f;
            drawPart(canvas, center - armOffset * unit, baseY - 24f * unit, armWidth, 12, 4,
                    new Rect(36, 52, 36 + (int) armWidth, 64),
                    new Rect(32, 52, 36, 64), new Rect(36 + (int) armWidth, 52, 40 + (int) armWidth, 64),
                    new Rect(52, 52, 52 + (int) armWidth, 64),
                    new Rect(48, 52, 52, 64), new Rect(52 + (int) armWidth, 52, 56 + (int) armWidth, 64),
                    unit, frontRatio, sideRatio, yaw);
            drawPart(canvas, center + armOffset * unit, baseY - 24f * unit, armWidth, 12, 4,
                    new Rect(44, 20, 44 + (int) armWidth, 32),
                    new Rect(40, 20, 44, 32), new Rect(44 + (int) armWidth, 20, 48 + (int) armWidth, 32),
                    new Rect(44, 36, 44 + (int) armWidth, 48),
                    new Rect(40, 36, 44, 48), new Rect(44 + (int) armWidth, 36, 48 + (int) armWidth, 48),
                    unit, frontRatio, sideRatio, yaw);

            drawPart(canvas, center, baseY - 32f * unit, 8, 8, 8,
                    new Rect(8, 8, 16, 16), new Rect(0, 8, 8, 16), new Rect(16, 8, 24, 16),
                    new Rect(40, 8, 48, 16), new Rect(32, 8, 40, 16), new Rect(48, 8, 56, 16),
                    unit, frontRatio, sideRatio, yaw);

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(11f * density);
            textPaint.setColor(0xffc3badf);
            canvas.drawText(slim ? "ALEX · SLIM" : "STEVE · CLÁSSICO",
                    w / 2f, 15f * density, textPaint);
        }

        private void drawPart(Canvas canvas, float centerX, float topY, float widthUnits,
                              float heightUnits, float depthUnits, Rect front, Rect sideRight,
                              Rect sideLeft, Rect overlayFront, Rect overlayRight, Rect overlayLeft,
                              float unit, float frontRatio, float sideRatio, float yaw) {
            float density = getResources().getDisplayMetrics().density;
            float frontWidth = widthUnits * unit * frontRatio;
            float sideWidth = depthUnits * unit * sideRatio;
            boolean showRightSide = yaw >= 0f;
            float shift = showRightSide ? -sideWidth / 2f : sideWidth / 2f;
            float left = centerX - frontWidth / 2f + shift;
            float height = heightUnits * unit;
            RectF frontDst = new RectF(left, topY, left + frontWidth, topY + height);
            RectF sideDst = null;
            Rect sideSource = showRightSide ? sideRight : sideLeft;
            Rect overlaySideSource = showRightSide ? overlayRight : overlayLeft;
            if (sideWidth > 0.8f * density) {
                sideDst = showRightSide
                        ? new RectF(frontDst.right, topY, frontDst.right + sideWidth, topY + height)
                        : new RectF(frontDst.left - sideWidth, topY, frontDst.left, topY + height);
                bitmapPaint.setColorFilter(sidePaint.getColorFilter());
                canvas.drawBitmap(skin, sideSource, sideDst, bitmapPaint);
                bitmapPaint.setColorFilter(null);
            }
            canvas.drawBitmap(skin, front, frontDst, bitmapPaint);
            // Outer layer (hat, jacket, sleeves and trousers) is transparent in the PNG.
            if (overlayFront != null) {
                float inflate = 0.8f * density;
                RectF overlayDst = new RectF(frontDst.left - inflate, frontDst.top - inflate,
                        frontDst.right + inflate, frontDst.bottom + inflate);
                canvas.drawBitmap(skin, overlayFront, overlayDst, bitmapPaint);
                if (sideDst != null && overlaySideSource != null) {
                    RectF overlaySideDst = new RectF(sideDst.left - inflate, sideDst.top - inflate,
                            sideDst.right + inflate, sideDst.bottom + inflate);
                    bitmapPaint.setColorFilter(sidePaint.getColorFilter());
                    canvas.drawBitmap(skin, overlaySideSource, overlaySideDst, bitmapPaint);
                    bitmapPaint.setColorFilter(null);
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.45f * density);
            paint.setColor(0x5520182e);
            canvas.drawRect(frontDst, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    previousX = event.getX();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float x = event.getX();
                    yaw += (x - previousX) / Math.max(1f, getWidth()) * 4.0f;
                    yaw = Math.max(-1.0f, Math.min(1.0f, yaw));
                    previousX = x;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    performClick();
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
