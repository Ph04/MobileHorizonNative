package dev.mobilehorizon.app;

import android.app.NativeActivity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.PopupWindow;

/** Fullscreen NativeActivity wrapper with optional Android touch controls. Native clicks are injected through the game's JNI input bridge; this must not depend on an Android AccessibilityService. */
public final class NativeGameActivity extends NativeActivity {
    private static native void nativeInjectCursorMove(float x, float y);
    private static native void nativeInjectMouseMotion(float dx, float dy);
    private static native void nativeInjectMouseButton(int button, boolean pressed, float x, float y);
    private static native void nativeInjectMouseScroll(float amount, float x, float y);
    private static native void nativeInjectKeyCode(int keyCode, boolean pressed);
    private static native void nativeInjectKeyboardText(String text, boolean submit);
    private static native boolean nativeIsGameplay();
    private static native boolean nativeHasLoadedWorld();
    private static native int[] nativeGetGuiMetrics();

    static {
        // NativeActivity later opens the same bundled library for android_main; loading
        // it in this process also makes the input bridge's JNI symbols callable from Java.
        System.loadLibrary("horizon_android");
    }

    static final String EXTRA_TOUCH_CONTROLS = "dev.mobilehorizon.app.EXTRA_TOUCH_CONTROLS";
    static final String EXTRA_PLAYER_NAME = "dev.mobilehorizon.app.EXTRA_PLAYER_NAME";
    static final String EXTRA_SESSION_FILE = "dev.mobilehorizon.app.EXTRA_SESSION_FILE";
    static final String EXTRA_NATIVE_SMOKE_TEST = "dev.mobilehorizon.app.EXTRA_NATIVE_SMOKE_TEST";
    private NativeTouchControlsView touchControls;
    private PopupWindow touchControlsPopup;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Horizon resolves options, saves, logs and prepared resources from these paths.
        // Set them before NativeActivity loads the native game library.
        try {
            android.content.Intent launchIntent = getIntent();

            java.io.File gameRoot = getApplicationContext().getFilesDir();
            if (!gameRoot.isDirectory() && !gameRoot.mkdirs()) {
                throw new java.io.IOException("Não foi possível criar o diretório privado do jogo.");
            }

            // Choose and verify a versioned Horizon engine before NativeActivity resolves the
            // stable libhorizon_android.so bootstrap. The selected engine is never overwritten
            // in place; the APK-provided engine remains the fallback.
            HorizonRuntimeManager.PreparedRuntime runtime = HorizonRuntimeManager.prepare(this);
            android.system.Os.setenv("HORIZON_RUNTIME_HOME", runtime.root.getAbsolutePath(), true);
            android.system.Os.setenv("HORIZON_RUNTIME_LIBRARY", runtime.activeLibrary.getAbsolutePath(), true);
            android.system.Os.setenv("HORIZON_RUNTIME_FALLBACK_LIBRARY", runtime.bundledLibrary.getAbsolutePath(), true);
            android.system.Os.setenv("HORIZON_RUNTIME_STATUS_FILE", runtime.statusFile.getAbsolutePath(), true);
            android.system.Os.setenv("HORIZON_RUNTIME_VERSION", runtime.activeVersion, true);

            java.io.File home = new java.io.File(gameRoot, "horizon");
            if (!home.isDirectory() && !home.mkdirs()) {
                throw new java.io.IOException("Não foi possível criar o diretório de dados Horizon.");
            }
            // Use an explicit per-launch flag. The old marker file could survive a
            // previous smoke test and accidentally suppress diagnostics for the real game.
            boolean smokeTest = launchIntent != null
                    && launchIntent.getBooleanExtra(EXTRA_NATIVE_SMOKE_TEST, false);
            if (smokeTest) {
                android.system.Os.setenv("HORIZON_NATIVE_SMOKE_TEST", "1", true);
            } else {
                android.system.Os.unsetenv("HORIZON_NATIVE_SMOKE_TEST");
            }
            // Clear leftovers from earlier versions/launches before the native entry point.
            new java.io.File(gameRoot, "horizon-native-smoke.flag").delete();
            new java.io.File(gameRoot, "horizon-native-smoke-result.txt").delete();
            new java.io.File(gameRoot, "horizon-client-result.txt").delete();

            // NativeActivity already starts the runtime with the app's private working directory
            // on supported builds; Os.chdir is not exposed by Android's public SDK.
            android.system.Os.setenv("HORIZON_HOME", home.getAbsolutePath(), true);
            // Keep game saves/logs under filesDir/horizon but point the newly built
            // Horizon client at the separately prepared resource tree under filesDir/.local.
            java.io.File resources = new java.io.File(
                    gameRoot, ".local/horizon-client/" + OfficialMinecraftInstaller.VERSION);
            android.system.Os.setenv("HORIZON_RESOURCES", resources.getAbsolutePath(), true);
            // NativeGameActivity runs in :game, so copy launch context explicitly:
            // environment changes made in MainActivity are not inherited across processes.
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

    void injectCursorMove(float x, float y) {
        nativeInjectCursorMove(x, y);
    }

    void injectMouseMotion(float dx, float dy) {
        nativeInjectMouseMotion(dx, dy);
    }

    void injectMouseButton(int button, boolean pressed, float x, float y) {
        nativeInjectMouseButton(button, pressed, x, y);
    }

    void injectMouseScroll(float amount, float x, float y) {
        nativeInjectMouseScroll(amount, x, y);
    }

    void injectKeyCode(int keyCode, boolean pressed) {
        nativeInjectKeyCode(keyCode, pressed);
    }

    void injectKeyboardText(String text) {
        nativeInjectKeyboardText(text == null ? "" : text, true);
    }

    boolean isGameplayMode() {
        try { return nativeIsGameplay(); }
        catch (UnsatisfiedLinkError error) { return false; }
    }

    boolean hasLoadedWorld() {
        try { return nativeHasLoadedWorld(); }
        catch (UnsatisfiedLinkError error) { return false; }
    }

    /** Current HUD viewport and GUI scale from the actual Horizon renderer. */
    int[] getGuiMetrics() {
        try { return nativeGetGuiMetrics(); }
        catch (UnsatisfiedLinkError error) { return null; }
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
