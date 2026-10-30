package dev.mobilehorizon.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.content.res.Resources;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Persisted launcher-only appearance preferences. Minecraft's in-game theme is untouched. */
final class LauncherAppearance {
    private static final String PREFS = "mobilehorizon_interface";
    private static final String KEY_ACCENT = "launcher_accent_index";
    private static final String KEY_CUSTOM_BACKGROUND = "launcher_custom_background";
    private static final String KEY_PANEL_OPACITY = "game_panel_opacity";
    private static final float DEFAULT_PANEL_OPACITY = 0.92f;
    private static final int DEFAULT_BACKGROUND = 0xff0f0d17;
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final int MAX_IMAGE_SIDE = 12000;
    private static final long MAX_PIXELS = 80_000_000L;

    private static final int[] ACCENTS = {
            0xff7557e8, 0xff3978df, 0xff238f8a, 0xff5d9653, 0xffc47732
    };
    private static final int[] BORDERS = {
            0xffa18cff, 0xff8bb4ff, 0xff74c8c0, 0xffa6cd82, 0xffffb36c
    };
    private static final String[] ACCENT_NAMES = {
            "Violeta", "Azul", "Turquesa", "Verde", "Âmbar"
    };

    private LauncherAppearance() {}

    static int signature(Context context) {
        android.content.SharedPreferences preferences =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File image = backgroundFile(context);
        long modified = image.isFile() ? image.lastModified() : 0L;
        long length = image.isFile() ? image.length() : 0L;
        int result = preferences.getInt(KEY_ACCENT, 0);
        result = 31 * result + (preferences.getBoolean(KEY_CUSTOM_BACKGROUND, false) ? 1 : 0);
        result = 31 * result + Math.round(panelOpacity(context) * 100f);
        result = 31 * result + (int) (modified ^ (modified >>> 32));
        result = 31 * result + (int) (length ^ (length >>> 32));
        return result;
    }

    static int backgroundColor(Context context) {
        return DEFAULT_BACKGROUND;
    }

    static int accentColor(Context context) {
        int index = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_ACCENT, 0);
        return ACCENTS[Math.max(0, Math.min(ACCENTS.length - 1, index))];
    }

    static int accentBorderColor(Context context) {
        int index = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_ACCENT, 0);
        return BORDERS[Math.max(0, Math.min(BORDERS.length - 1, index))];
    }

    static float panelOpacity(Context context) {
        float value = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_PANEL_OPACITY, DEFAULT_PANEL_OPACITY);
        return Math.max(0.25f, Math.min(1f, value));
    }

    static void setPanelOpacity(Context context, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException("Opacidade dos painéis inválida.");
        }
        float safe = Math.max(0.25f, Math.min(1f, value));
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_PANEL_OPACITY, safe).apply();
    }

    static String accentName(Context context) {
        int index = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_ACCENT, 0);
        return ACCENT_NAMES[Math.max(0, Math.min(ACCENT_NAMES.length - 1, index))];
    }

    static String[] accentNames() {
        return ACCENT_NAMES.clone();
    }

    static void setAccent(Context context, int index) {
        if (index < 0 || index >= ACCENTS.length) {
            throw new IllegalArgumentException("Cor de destaque inválida.");
        }
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_ACCENT, index).apply();
    }

    static boolean hasCustomBackground(Context context) {
        File file = backgroundFile(context);
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CUSTOM_BACKGROUND, false)
                && file.isFile() && file.length() > 0 && file.length() <= MAX_IMAGE_BYTES;
    }

    static void importBackground(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("Imagem não selecionada.");
        File target = backgroundFile(context);
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            if (input == null) throw new IOException("Não foi possível abrir a imagem selecionada.");
            byte[] buffer = new byte[32768];
            int read;
            long total = 0L;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_IMAGE_BYTES) {
                    throw new IOException("A imagem de fundo deve ter no máximo 15 MiB.");
                }
                output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Não foi possível importar a imagem.", error);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(temporary.getAbsolutePath(), bounds);
        long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > MAX_IMAGE_SIDE || bounds.outHeight > MAX_IMAGE_SIDE
                || pixels > MAX_PIXELS) {
            temporary.delete();
            throw new IOException("Imagem inválida ou com resolução excessiva.");
        }
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CUSTOM_BACKGROUND, true).apply();
    }

    static void clearBackground(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CUSTOM_BACKGROUND, false).apply();
        File file = backgroundFile(context);
        if (file.exists()) file.delete();
    }

    static Drawable createBackgroundDrawable(Context context) {
        if (!hasCustomBackground(context)) return null;
        File file = backgroundFile(context);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return null;
        BitmapDrawable image = new BitmapDrawable(context.getResources(), bitmap);
        image.setAlpha(118);
        image.setFilterBitmap(true);
        // Always keep a dark base behind the semi-transparent photograph.
        return new LayerDrawable(new Drawable[]{
                new ColorDrawable(DEFAULT_BACKGROUND), image
        });
    }

    private static File backgroundFile(Context context) {
        File root = new File(context.getApplicationContext().getFilesDir(), "launcher-ui");
        if (!root.isDirectory()) root.mkdirs();
        return new File(root, "background-image");
    }
}
