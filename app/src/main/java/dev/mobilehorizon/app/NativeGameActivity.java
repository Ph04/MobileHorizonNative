package dev.mobilehorizon.app;

import android.app.NativeActivity;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.widget.Toast;
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
    private boolean gestureResultReceiverRegistered;
    private boolean touchInjectionPending;
    private boolean warnedAccessibilityUnavailable;

    private final BroadcastReceiver touchInjectionResultReceiver = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (intent == null || !CursorGestureAccessibilityService.ACTION_TAP_FINISHED.equals(intent.getAction())) return;
            touchInjectionPending = false;
            getWindow().getDecorView().postDelayed(NativeGameActivity.this::showTouchControlsPopupWhenAttached, 90);
        }
    };

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

        IntentFilter injectedTapFilter = new IntentFilter(CursorGestureAccessibilityService.ACTION_TAP_FINISHED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(touchInjectionResultReceiver, injectedTapFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(touchInjectionResultReceiver, injectedTapFilter);
        }
        gestureResultReceiverRegistered = true;

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
                || touchInjectionPending || decor.getWindowToken() == null) return;
        try {
            popup.showAtLocation(decor, Gravity.TOP | Gravity.START, 0, 0);
        } catch (WindowManager.BadTokenException error) {
            android.util.Log.w("MobileHorizon", "Não foi possível exibir os controles touchscreen", error);
        }
    }

    /** Requests a real Android touchscreen tap at the virtual cursor's screen coordinate. */
    void requestTouchAtCursor(float x, float y) {
        if (touchControls == null || touchControlsPopup == null || isFinishing() || isDestroyed()) return;
        if (!isCursorGestureServiceEnabled()) {
            if (!warnedAccessibilityUnavailable) {
                warnedAccessibilityUnavailable = true;
                Toast.makeText(this,
                        "Ative “Ponte de toque do MobileHorizon” nas Configurações de acessibilidade para clicar no menu.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (touchInjectionPending) return;

        int[] location = new int[2];
        touchControls.getLocationOnScreen(location);
        final float screenX = location[0] + x;
        final float screenY = location[1] + y;
        touchInjectionPending = true;
        if (touchControlsPopup.isShowing()) touchControlsPopup.dismiss();

        // Let WindowManager remove the overlay before the service injects the tap.
        getWindow().getDecorView().postDelayed(() -> {
            Intent request = new Intent(CursorGestureAccessibilityService.ACTION_INJECT_TAP);
            request.setPackage(getPackageName());
            request.putExtra(CursorGestureAccessibilityService.EXTRA_X, screenX);
            request.putExtra(CursorGestureAccessibilityService.EXTRA_Y, screenY);
            sendBroadcast(request);
        }, 90);
    }

    private boolean isCursorGestureServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) return false;
        String component = new ComponentName(this, CursorGestureAccessibilityService.class).flattenToString();
        return enabled.toLowerCase(java.util.Locale.ROOT)
                .contains(component.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    protected void onPause() {
        if (touchControls != null) touchControls.releaseAllInputs();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (gestureResultReceiverRegistered) {
            try { unregisterReceiver(touchInjectionResultReceiver); } catch (IllegalArgumentException ignored) {}
            gestureResultReceiverRegistered = false;
        }
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
