package dev.mobilehorizon.app;

import android.app.NativeActivity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.PopupWindow;

/** Fullscreen NativeActivity wrapper with optional Android touch controls. */
public final class NativeGameActivity extends NativeActivity {
    static final String EXTRA_TOUCH_CONTROLS = "dev.mobilehorizon.app.EXTRA_TOUCH_CONTROLS";
    static final String EXTRA_PLAYER_NAME = "dev.mobilehorizon.app.EXTRA_PLAYER_NAME";
    static final String EXTRA_SESSION_FILE = "dev.mobilehorizon.app.EXTRA_SESSION_FILE";
    private NativeTouchControlsView touchControls;
    private PopupWindow touchControlsPopup;

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
            // NativeGameActivity runs in :game, so copy launch context explicitly:
            // environment changes made in MainActivity are not inherited across processes.
            android.content.Intent launchIntent = getIntent();
            if (launchIntent != null) {
                String playerName = launchIntent.getStringExtra(EXTRA_PLAYER_NAME);
                if (playerName != null && playerName.matches("[A-Za-z0-9_]{1,16}")) {
                    android.system.Os.setenv("HORIZON_PLAYER_NAME", playerName, true);
                }
                String sessionPath = launchIntent.getStringExtra(EXTRA_SESSION_FILE);
                if (sessionPath != null && !sessionPath.isEmpty()) {
                    java.io.File session = new java.io.File(sessionPath).getCanonicalFile();
                    java.io.File sessions = new java.io.File(gameRoot, "sessions").getCanonicalFile();
                    if (session.toPath().startsWith(sessions.toPath()) && session.isFile()) {
                        android.system.Os.setenv("HORIZON_SESSION_FILE", session.getAbsolutePath(), true);
                    }
                }
            }
        } catch (Exception error) {
            android.util.Log.e("MobileHorizon", "Não foi possível preparar o diretório do jogo", error);
        }
        // Keep Android's Activity/UI thread at its normal scheduling priority. Raising it
        // before native renderer startup can starve system input/UI work on slower devices.
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
            // NativeActivity takes the window's native input queue, so a view attached to its
            // normal content hierarchy may draw above the game but never receive Android touches.
            // Use a touchable application-panel window to give the controls their own input target.
            touchControlsPopup = new PopupWindow(touchControls,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, false);
            touchControlsPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            touchControlsPopup.setTouchable(true);
            touchControlsPopup.setFocusable(false);
            touchControlsPopup.setOutsideTouchable(false);
            touchControlsPopup.setClippingEnabled(false);
            touchControlsPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
            // Defer until the Activity's window token is attached; onCreate can be too early.
            window.getDecorView().post(this::showTouchControlsPopupWhenAttached);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
            showTouchControlsPopupWhenAttached();
        }
    }

    private void showTouchControlsPopupWhenAttached() {
        PopupWindow popup = touchControlsPopup;
        View decor = getWindow().getDecorView();
        if (popup == null || popup.isShowing() || isFinishing() || isDestroyed()
                || decor.getWindowToken() == null) return;
        try {
            popup.showAtLocation(decor, Gravity.TOP | Gravity.START, 0, 0);
        } catch (WindowManager.BadTokenException error) {
            android.util.Log.w("MobileHorizon", "Não foi possível exibir os controles touchscreen", error);
        }
    }

    @Override
    protected void onPause() {
        if (touchControls != null) touchControls.releaseAllInputs();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (touchControls != null) touchControls.releaseAllInputs();
        if (touchControlsPopup != null) {
            try { touchControlsPopup.dismiss(); } catch (IllegalArgumentException ignored) {}
            touchControlsPopup = null;
            touchControls = null;
        }
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
