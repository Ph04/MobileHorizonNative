package dev.mobilehorizon.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Lets players arrange the real in-game touch controls before launching Minecraft.
 * The overlay reads and writes the same preferences as NativeGameActivity.
 */
public final class TouchControlsEditorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarColor(Color.TRANSPARENT);
        applyImmersiveMode();

        FrameLayout root = new FrameLayout(this);
        root.addView(new PreviewBackground(this),
                new FrameLayout.LayoutParams(-1, -1));

        NativeTouchControlsView controls = new NativeTouchControlsView(this, true);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        TextView settingsButton = new TextView(this);
        settingsButton.setText(UiLanguage.translate(this, "⚙  Ajustes"));
        settingsButton.setTextColor(Color.WHITE);
        settingsButton.setTextSize(12);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setPadding(dp(12), dp(5), dp(12), dp(5));
        settingsButton.setBackground(makePill(0xD51A2434));
        settingsButton.setClickable(true);
        settingsButton.setFocusable(true);
        settingsButton.setOnClickListener(v -> controls.showGlobalSettingsDialog());
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(
                dp(108), dp(34), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        settingsParams.topMargin = dp(43);
        root.addView(settingsButton, settingsParams);

        TextView hint = new TextView(this);
        hint.setText(UiLanguage.translate(this, "Arraste para mover · toque para editar · + adiciona · ✓ salva"));
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(12), dp(7), dp(12), dp(7));
        hint.setClickable(false);
        hint.setFocusable(false);
        GradientDrawable hintBackground = new GradientDrawable();
        hintBackground.setColor(0xB51A2434);
        hintBackground.setCornerRadius(dp(18));
        hint.setBackground(hintBackground);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.topMargin = dp(8);
        root.addView(hint, hintParams);

        setContentView(root);
    }

    private GradientDrawable makePill(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0x557F9ABF);
        return background;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** Lightweight landscape mock scene; it intentionally makes no game/native calls. */
    private static final class PreviewBackground extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PreviewBackground(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            paint.setShader(new LinearGradient(0, 0, 0, h * 0.76f,
                    new int[]{0xFF5A91C0, 0xFFB8D9E8, 0xFFE6E7C5},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            fill(canvas, 0.07f * w, 0.15f * h, 0.19f * w, 0.20f * h, 0xDDF4F5F2);
            fill(canvas, 0.10f * w, 0.12f * h, 0.23f * w, 0.18f * h, 0xDDF4F5F2);
            fill(canvas, 0.72f * w, 0.22f * h, 0.82f * w, 0.27f * h, 0xCFF4F5F2);
            fill(canvas, 0.77f * w, 0.19f * h, 0.86f * w, 0.25f * h, 0xCFF4F5F2);

            float horizon = h * 0.63f;
            fill(canvas, 0, horizon - h * 0.08f, w * 0.22f, horizon, 0xFF6C9A78);
            fill(canvas, w * 0.22f, horizon - h * 0.15f, w * 0.42f, horizon, 0xFF739F7A);
            fill(canvas, w * 0.42f, horizon - h * 0.05f, w * 0.62f, horizon, 0xFF628F71);
            fill(canvas, w * 0.62f, horizon - h * 0.12f, w * 0.81f, horizon, 0xFF739F7A);
            fill(canvas, w * 0.81f, horizon - h * 0.04f, w, horizon, 0xFF628F71);

            fill(canvas, 0, horizon, w, h, 0xFF735D43);
            fill(canvas, 0, horizon, w, horizon + dp(13), 0xFF6E9C4A);

            float block = Math.max(dp(26), Math.min(dp(44), w / 28f));
            paint.setColor(0x337A684C);
            paint.setStrokeWidth(Math.max(1f, dp(1)));
            for (float x = 0; x < w; x += block) {
                canvas.drawLine(x, horizon + dp(13), x, h, paint);
            }
            for (float y = horizon + dp(13); y < h; y += block) {
                canvas.drawLine(0, y, w, y, paint);
            }

            float cx = w * 0.5f, cy = h * 0.46f, arm = dp(5);
            paint.setColor(0xAA17202A);
            paint.setStrokeWidth(dp(2));
            canvas.drawLine(cx - arm, cy, cx + arm, cy, paint);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, paint);
        }

        private void fill(Canvas canvas, float left, float top, float right, float bottom, int color) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawRect(left, top, right, bottom, paint);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
