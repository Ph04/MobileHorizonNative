package dev.mobilehorizon.app;

import android.app.NativeActivity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/** Fullscreen NativeActivity wrapper with optional Android touch controls. */
public final class NativeGameActivity extends NativeActivity {
    static final String EXTRA_TOUCH_CONTROLS = "dev.mobilehorizon.app.EXTRA_TOUCH_CONTROLS";
    private NativeTouchControlsView touchControls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Horizon resolves options, saves, logs and prepared resources from these paths.
        // Set them before NativeActivity loads the native game library.
        try {
            java.io.File gameRoot = getApplicationContext().getFilesDir();
            if (!gameRoot.isDirectory() && !gameRoot.mkdirs()) {
                throw new java.io.IOException("Não foi possível criar o diretório privado do jogo.");
            }
            java.io.File home = new java.io.File(gameRoot, "horizon");
            if (!home.isDirectory() && !home.mkdirs()) {
                throw new java.io.IOException("Não foi possível criar o diretório de dados Horizon.");
            }
            // NativeActivity already starts the runtime with the app's private working directory
            // on supported builds; Os.chdir is not exposed by Android's public SDK.
            android.system.Os.setenv("HORIZON_HOME", home.getAbsolutePath(), true);
        } catch (Exception error) {
            android.util.Log.e("MobileHorizon", "Não foi possível preparar o diretório do jogo", error);
        }
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (RuntimeException ignored) {
            // This scheduling hint is optional and must never block startup.
        }
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setNavigationBarColor(Color.TRANSPARENT);
        applyImmersiveMode();

        boolean requested = getIntent() == null
                || getIntent().getBooleanExtra(EXTRA_TOUCH_CONTROLS, true);
        SharedPreferences prefs = getSharedPreferences(NativeTouchControlsView.PREFERENCES, MODE_PRIVATE);
        if (requested && prefs.getBoolean(NativeTouchControlsView.KEY_ENABLED, true)) {
            touchControls = new NativeTouchControlsView(this);
            addContentView(touchControls, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    @Override
    protected void onPause() {
        if (touchControls != null) touchControls.releaseAllInputs();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (touchControls != null) touchControls.releaseAllInputs();
        super.onDestroy();
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
