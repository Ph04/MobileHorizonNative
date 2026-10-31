package dev.mobilehorizon.app;

import android.app.NativeActivity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.PopupWindow;

/** Fullscreen NativeActivity wrapper with optional Android touch controls. Native clicks are injected through the game's JNI input bridge; this must not depend on an Android AccessibilityService. */
public final class NativeGameActivity extends NativeActivity {
    private static native int nativeChangeWorkingDirectory(String path);
    private static native void nativeInjectCursorMove(float x, float y);
    private static native void nativeInjectMouseMotion(float dx, float dy);
    private static native void nativeInjectMouseButton(int button, boolean pressed, float x, float y);
    private static native void nativeInjectMouseScroll(float amount, float x, float y);
    private static native void nativeInjectKeyCode(int keyCode, boolean pressed);
    private static native void nativeInjectKeyboardText(String text, boolean submit);
    private static native void nativeBeginChatDraft();
    private static native void nativeInjectImeText(String value, String inserted, int backspaces, boolean submit);
    private static native String nativeGetChatDraft();
    private static native boolean nativeIsChatTarget();
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
    static final String EXTRA_INSTANCE_ID = "dev.mobilehorizon.app.EXTRA_INSTANCE_ID";
    // The upstream Java Bridge resolves options.txt and sibling mods-java from process cwd.
    // Restore the launcher's original cwd when NativeActivity is destroyed.
    private String previousWorkingDirectory;
    private NativeTouchControlsView touchControls;
    private PopupWindow touchControlsPopup;
    private PopupWindow keyboardInputPopup;
    private EditText keyboardInput;
    private boolean suppressKeyboardInputWatcher;
    private String lastKeyboardInput = "";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Resolve the instance before choosing the engine. Vanilla keeps the regular
        // independently updated runtime; Java Bridge By:iaxivers uses its own verified channel.
        try {
            android.content.Intent launchIntent = getIntent();
            android.system.Os.unsetenv("HORIZON_NATIVE_START_BLOCKED");

            java.io.File gameRoot = getApplicationContext().getFilesDir();
            if (!gameRoot.isDirectory() && !gameRoot.mkdirs()) {
                throw new java.io.IOException("Não foi possível criar o diretório privado do jogo.");
            }

            String instanceId = launchIntent == null ? GameInstanceManager.DEFAULT_ID
                    : launchIntent.getStringExtra(EXTRA_INSTANCE_ID);
            java.io.File home = GameInstanceManager.getInstanceHome(this, instanceId);
            if (!home.isDirectory() && !home.mkdirs()) {
                throw new java.io.IOException("Não foi possível criar o diretório da instância Horizon.");
            }
            home = home.getCanonicalFile();
            previousWorkingDirectory = new java.io.File(".").getCanonicalPath();
            // Match iaxivers/horizon/tools/java_bridge/prism.sh: cd into HOME_DIR before
            // starting Horizon. The Rust bridge uses current_dir()/options.txt and looks for
            // mods-java beside it; HORIZON_HOME alone does not change that lookup path.
            int chdirError = nativeChangeWorkingDirectory(home.getAbsolutePath());
            if (chdirError != 0) {
                throw new java.io.IOException("Não foi possível entrar na pasta da instância (errno "
                        + chdirError + "): " + home.getAbsolutePath());
            }
            if (!home.equals(new java.io.File(".").getCanonicalFile())) {
                throw new java.io.IOException("O diretório ativo do processo não corresponde à instância selecionada.");
            }
            android.util.Log.i("MobileHorizon",
                    "Diretório de trabalho do Horizon: " + home.getAbsolutePath());
            GameProfileManager.LaunchProfile profile =
                    GameProfileManager.prepareLaunch(this, instanceId);
            boolean fabricJavaBridge = profile.profile.isFabricJavaBridge();

            java.io.File runtimeRoot;
            java.io.File runtimeLibrary;
            java.io.File runtimeFallback = null;
            java.io.File runtimeStatus;
            String runtimeVersion;
            if (fabricJavaBridge) {
                FabricBridgeRuntimeManager.PreparedRuntime runtime =
                        FabricBridgeRuntimeManager.prepare(this);
                runtimeRoot = runtime.root;
                runtimeLibrary = runtime.activeLibrary;
                runtimeStatus = runtime.statusFile;
                runtimeVersion = runtime.activeVersion;
            } else {
                HorizonRuntimeManager.PreparedRuntime runtime = HorizonRuntimeManager.prepare(this);
                runtimeRoot = runtime.root;
                runtimeLibrary = runtime.activeLibrary;
                runtimeFallback = runtime.fallbackLibrary;
                runtimeStatus = runtime.statusFile;
                runtimeVersion = runtime.activeVersion;
            }
            android.system.Os.setenv("HORIZON_RUNTIME_HOME", runtimeRoot.getAbsolutePath(), true);
            android.system.Os.setenv("HORIZON_RUNTIME_LIBRARY", runtimeLibrary.getAbsolutePath(), true);
            if (runtimeFallback != null) {
                android.system.Os.setenv("HORIZON_RUNTIME_FALLBACK_LIBRARY",
                        runtimeFallback.getAbsolutePath(), true);
            } else {
                android.system.Os.unsetenv("HORIZON_RUNTIME_FALLBACK_LIBRARY");
            }
            android.system.Os.setenv("HORIZON_RUNTIME_STATUS_FILE", runtimeStatus.getAbsolutePath(), true);
            android.system.Os.setenv("HORIZON_RUNTIME_VERSION", runtimeVersion, true);
            android.system.Os.unsetenv("HORIZON_RUNTIME_USING_FALLBACK");

            // NativeActivity runs in :game. Reset optional state before exporting the selected
            // profile so a recycled process cannot inherit the last instance's loader/JVM.
            android.system.Os.unsetenv("HORIZON_INSTANCE_ID");
            android.system.Os.unsetenv("HORIZON_GAME_VERSION");
            android.system.Os.setenv("HORIZON_LOADER_ID", "vanilla", true);
            android.system.Os.setenv("HORIZON_LOADER_VERSION", "", true);
            android.system.Os.unsetenv("HORIZON_INSTANCE_PROFILE");
            android.system.Os.unsetenv("HORIZON_MODS_DIR");
            android.system.Os.unsetenv("HORIZON_COMPATIBILITY_MODULE_DIR");
            android.system.Os.unsetenv("HORIZON_COMPATIBILITY_MODULE_ID");
            android.system.Os.unsetenv("HORIZON_COMPATIBILITY_MODULE_VERSION");
            android.system.Os.unsetenv("HORIZON_COMPATIBILITY_ENTRYPOINT_JAR");
            android.system.Os.unsetenv("HORIZON_COMPATIBILITY_ENTRYPOINT_CLASS");
            android.system.Os.unsetenv("HORIZON_COMPATIBILITY_API_VERSION");
            android.system.Os.unsetenv("HORIZON_APP_FILES_DIR");
            android.system.Os.unsetenv("HORIZON_JAVA_EXECUTABLE");
            android.system.Os.unsetenv("HORIZON_JAVA_HOME");
            android.system.Os.unsetenv("HORIZON_JAVA_LIBRARY_PATH");
            android.system.Os.unsetenv("HORIZON_JAVA_TMP");
            android.system.Os.unsetenv("HORIZON_COMPATIBILITY_API_JAR");
            android.system.Os.unsetenv("HORIZON_ROOT");
            android.system.Os.unsetenv("HORIZON_JAVA_SIDE");
            android.system.Os.unsetenv("JAVA_HOME");
            android.system.Os.unsetenv("LD_LIBRARY_PATH");
            android.system.Os.unsetenv("TMPDIR");

            android.system.Os.setenv("HORIZON_HOME", home.getAbsolutePath(), true);
            android.system.Os.setenv("HORIZON_INSTANCE_ID", profile.profile.instanceId, true);
            android.system.Os.setenv("HORIZON_GAME_VERSION",
                    profile.profile.minecraftVersion, true);
            android.system.Os.setenv("HORIZON_LOADER_ID", profile.profile.loaderId, true);
            android.system.Os.setenv("HORIZON_LOADER_VERSION",
                    profile.profile.loaderVersion, true);
            android.system.Os.setenv("HORIZON_INSTANCE_PROFILE",
                    profile.launchFile.getAbsolutePath(), true);
            android.system.Os.setenv("HORIZON_MODS_DIR",
                    profile.modsDirectory.getAbsolutePath(), true);

            if (fabricJavaBridge) {
                int modCount = FabricModManager.prepareInstance(this, profile.profile.instanceId);
                java.io.File bridgeModsDirectory = new java.io.File(
                        home, FabricModManager.BRIDGE_DIRECTORY).getCanonicalFile();
                java.io.File[] bridgeEntries = bridgeModsDirectory.listFiles();
                if (bridgeEntries == null || !bridgeModsDirectory.isDirectory()) {
                    throw new java.io.IOException("A pasta mods-java da instância não está acessível: "
                            + bridgeModsDirectory.getAbsolutePath());
                }
                int visibleJars = 0;
                for (java.io.File entry : bridgeEntries) {
                    if (entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                            && entry.isFile()) visibleJars++;
                }
                if (visibleJars != modCount) {
                    throw new java.io.IOException("A preparação dos mods não corresponde ao que o Java Bridge consegue ler: "
                            + modCount + " link(s) preparados, " + visibleJars + " JAR(s) acessíveis em mods-java.");
                }
                if (visibleJars == 0) {
                    android.util.Log.w("MobileHorizon",
                            "Java Bridge By:iaxivers: nenhum JAR ativo em mods-java; o Horizon upstream "
                                    + "vai pular a inicialização do JVM/Fabric até existir ao menos um mod ativo.");
                }
                JdkRuntimeInstaller.RuntimePaths javaRuntime =
                        JdkRuntimeInstaller.getInstalledIfReady(this);
                if (javaRuntime == null) {
                    throw new java.io.IOException("O Java 25 ainda não foi preparado. Conclua a preparação dos recursos oficiais antes de iniciar Java Bridge By:iaxivers.");
                }
                java.io.File workspace = new java.io.File(gameRoot,
                        ".local/horizon-prepare-workspace").getCanonicalFile();
                java.io.File expectedLock = new java.io.File(workspace, "research-lock.json");
                java.io.File expectedJar = new java.io.File(workspace,
                        ".local/minecraft/26.3/jars/client.jar");
                if (!expectedLock.isFile() || !expectedJar.isFile()) {
                    throw new java.io.IOException("Java Bridge By:iaxivers precisa do JAR cliente 26.3 original e do research-lock.json preparados pelo launcher. Reprepare os recursos oficiais.");
                }
                android.system.Os.setenv("HORIZON_ROOT", workspace.getAbsolutePath(), true);
                android.system.Os.setenv("HORIZON_JAVA_HOME",
                        javaRuntime.javaHome.getAbsolutePath(), true);
                android.system.Os.setenv("JAVA_HOME",
                        javaRuntime.javaHome.getAbsolutePath(), true);
                android.system.Os.setenv("HORIZON_JAVA_SIDE", "client", true);
                android.system.Os.setenv("HORIZON_MODS_DIR",
                        bridgeModsDirectory.getAbsolutePath(), true);

                java.util.Map<String, String> javaEnvironment =
                        javaRuntime.processEnvironment(new java.io.File(javaRuntime.root, "tmp"));
                for (java.util.Map.Entry<String, String> entry : javaEnvironment.entrySet()) {
                    android.system.Os.setenv(entry.getKey(), entry.getValue(), true);
                }
                android.util.Log.i("MobileHorizon",
                        "Java Bridge By:iaxivers preparado: " + modCount + " JAR(s), mods-java em "
                                + bridgeModsDirectory.getAbsolutePath() + ", Java 25 em "
                                + javaRuntime.javaHome.getAbsolutePath());
            } else if (profile.moduleDirectory != null) {
                android.system.Os.setenv("HORIZON_COMPATIBILITY_MODULE_DIR",
                        profile.moduleDirectory.getAbsolutePath(), true);
                android.system.Os.setenv("HORIZON_COMPATIBILITY_MODULE_ID",
                        profile.profile.moduleId, true);
                android.system.Os.setenv("HORIZON_COMPATIBILITY_MODULE_VERSION",
                        profile.profile.moduleVersion, true);
                android.system.Os.setenv("HORIZON_COMPATIBILITY_ENTRYPOINT_JAR",
                        profile.entrypointJar, true);
                android.system.Os.setenv("HORIZON_COMPATIBILITY_ENTRYPOINT_CLASS",
                        profile.entrypointClass, true);
                android.system.Os.setenv("HORIZON_COMPATIBILITY_API_VERSION",
                        Integer.toString(CompatibilityPluginManager.PLUGIN_API_VERSION), true);

                CompatibilityApiRuntime.Prepared bridge = CompatibilityApiRuntime.prepare(this);
                java.io.File filesRoot = getFilesDir().getCanonicalFile();
                android.system.Os.setenv("HORIZON_APP_FILES_DIR", filesRoot.getAbsolutePath(), true);
                android.system.Os.setenv("HORIZON_JAVA_EXECUTABLE",
                        bridge.javaExecutable.getAbsolutePath(), true);
                android.system.Os.setenv("HORIZON_JAVA_HOME",
                        bridge.javaHome.getAbsolutePath(), true);
                android.system.Os.setenv("HORIZON_JAVA_LIBRARY_PATH",
                        bridge.libraryPath, true);
                android.system.Os.setenv("HORIZON_JAVA_TMP",
                        bridge.javaTemp.getAbsolutePath(), true);
                android.system.Os.setenv("HORIZON_COMPATIBILITY_API_JAR",
                        bridge.apiJar.getAbsolutePath(), true);
            }

            android.util.Log.i("MobileHorizon", "Instância " + profile.profile.instanceId
                    + " preparada: Minecraft " + profile.profile.minecraftVersion
                    + ", loader " + profile.profile.loaderId + " " + profile.profile.loaderVersion
                    + ", runtime " + runtimeVersion);

            java.io.File resources = new java.io.File(
                    gameRoot, ".local/horizon-client/" + profile.profile.minecraftVersion);
            android.system.Os.setenv("HORIZON_RESOURCES", resources.getAbsolutePath(), true);

            boolean smokeTest = launchIntent != null
                    && launchIntent.getBooleanExtra(EXTRA_NATIVE_SMOKE_TEST, false);
            if (smokeTest) {
                android.system.Os.setenv("HORIZON_NATIVE_SMOKE_TEST", "1", true);
            } else {
                android.system.Os.unsetenv("HORIZON_NATIVE_SMOKE_TEST");
            }
            new java.io.File(gameRoot, "horizon-native-smoke.flag").delete();
            new java.io.File(gameRoot, "horizon-native-smoke-result.txt").delete();
            new java.io.File(gameRoot, "horizon-client-result.txt").delete();

            // Player identity/session belong to the Android app process, not the NativeActivity process.
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
            try {
                android.system.Os.setenv("HORIZON_NATIVE_START_BLOCKED",
                        "ERROR: configuração da instância/compatibilidade inválida: "
                                + (error.getMessage() == null ? error.toString() : error.getMessage()), true);
            } catch (Exception envError) {
                android.util.Log.e("MobileHorizon", "Não foi possível bloquear a inicialização nativa", envError);
            }
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

    /** Show Android's IME while Horizon keeps Minecraft's actual focused text field on screen. */
    void showMinecraftKeyboard() {
        ensureKeyboardInputProxy();
        if (hasLoadedWorld() && isChatInputTarget()) {
            String draft = "";
            try {
                String current = nativeGetChatDraft();
                if (current != null) draft = current;
            } catch (UnsatisfiedLinkError ignored) { }
            setKeyboardProxyText(draft);
            try { nativeBeginChatDraft(); } catch (UnsatisfiedLinkError ignored) { }
        }
        View decor = getWindow().getDecorView();
        if (keyboardInputPopup == null || keyboardInput == null) return;
        if (!keyboardInputPopup.isShowing()) {
            if (decor.getWindowToken() == null) {
                decor.post(this::showMinecraftKeyboard);
                return;
            }
            try {
                keyboardInputPopup.showAtLocation(decor, Gravity.TOP | Gravity.START, 0, 0);
            } catch (WindowManager.BadTokenException error) {
                android.util.Log.w("MobileHorizon", "Não foi possível abrir o teclado Android", error);
                return;
            }
        }
        keyboardInput.requestFocus();
        decor.postDelayed(() -> {
            InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null && keyboardInput != null) {
                ime.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 100);
    }

    private boolean isChatInputTarget() {
        try { return nativeIsChatTarget(); }
        catch (UnsatisfiedLinkError error) { return false; }
    }

    private void ensureKeyboardInputProxy() {
        if (keyboardInputPopup != null && keyboardInput != null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setTextColor(Color.TRANSPARENT);
        input.setHintTextColor(Color.TRANSPARENT);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setTextSize(1f);
        input.setPadding(0, 0, 0, 0);
        input.setAlpha(0.01f);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterKey = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || enterKey) {
                submitKeyboardInput();
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) {
                String value = editable == null ? "" : editable.toString();
                if (suppressKeyboardInputWatcher) {
                    lastKeyboardInput = value;
                    return;
                }
                ImeTextDelta delta = diffKeyboardText(lastKeyboardInput, value);
                lastKeyboardInput = value;
                try {
                    nativeInjectImeText(value, delta.inserted, delta.backspaces, false);
                } catch (UnsatisfiedLinkError error) {
                    android.util.Log.w("MobileHorizon", "A ponte do teclado ainda não está disponível", error);
                }
            }
        });
        keyboardInput = input;
        keyboardInputPopup = new PopupWindow(input, dpForKeyboard(2), dpForKeyboard(2), true);
        keyboardInputPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        keyboardInputPopup.setFocusable(true);
        keyboardInputPopup.setTouchable(true);
        keyboardInputPopup.setOutsideTouchable(false);
        keyboardInputPopup.setClippingEnabled(false);
        keyboardInputPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        keyboardInputPopup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        keyboardInputPopup.setOnDismissListener(() -> {
            // Back can hide the IME without clearing the draft held by this proxy.
        });
    }

    private int dpForKeyboard(float value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }

    private void setKeyboardProxyText(String value) {
        if (keyboardInput == null) return;
        String safe = value == null ? "" : value;
        suppressKeyboardInputWatcher = true;
        keyboardInput.setText(safe);
        keyboardInput.setSelection(keyboardInput.length());
        lastKeyboardInput = safe;
        suppressKeyboardInputWatcher = false;
    }

    private static final class ImeTextDelta {
        final String inserted;
        final int backspaces;
        ImeTextDelta(String inserted, int backspaces) {
            this.inserted = inserted;
            this.backspaces = backspaces;
        }
    }

    /** Diff full IME strings but deliver only edits to non-chat Minecraft fields. */
    private static ImeTextDelta diffKeyboardText(String oldValue, String newValue) {
        int oldPos = 0, newPos = 0;
        while (oldPos < oldValue.length() && newPos < newValue.length()) {
            int oldCp = oldValue.codePointAt(oldPos);
            int newCp = newValue.codePointAt(newPos);
            if (oldCp != newCp) break;
            oldPos += Character.charCount(oldCp);
            newPos += Character.charCount(newCp);
        }
        int oldEnd = oldValue.length(), newEnd = newValue.length();
        while (oldEnd > oldPos && newEnd > newPos) {
            int oldCp = oldValue.codePointBefore(oldEnd);
            int newCp = newValue.codePointBefore(newEnd);
            if (oldCp != newCp) break;
            oldEnd -= Character.charCount(oldCp);
            newEnd -= Character.charCount(newCp);
        }
        return new ImeTextDelta(newValue.substring(newPos, newEnd),
                oldValue.codePointCount(oldPos, oldEnd));
    }

    private void submitKeyboardInput() {
        if (keyboardInput == null) return;
        String value = keyboardInput.getText() == null ? "" : keyboardInput.getText().toString();
        try {
            nativeInjectImeText(value, "", 0, true);
        } catch (UnsatisfiedLinkError error) {
            android.util.Log.w("MobileHorizon", "Não foi possível enviar o texto pelo teclado Android", error);
        }
        setKeyboardProxyText("");
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(keyboardInput.getWindowToken(), 0);
        if (keyboardInputPopup != null && keyboardInputPopup.isShowing()) {
            try { keyboardInputPopup.dismiss(); } catch (IllegalArgumentException ignored) { }
        }
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
        if (keyboardInputPopup != null) {
            try { keyboardInputPopup.dismiss(); } catch (IllegalArgumentException ignored) { }
            keyboardInputPopup = null;
            keyboardInput = null;
        }
        if (touchControlsPopup != null) {
            try { touchControlsPopup.dismiss(); } catch (IllegalArgumentException ignored) {}
            touchControlsPopup = null;
            touchControls = null;
        }
        super.onDestroy();
        if (previousWorkingDirectory != null) {
            try {
                int restoreError = nativeChangeWorkingDirectory(previousWorkingDirectory);
                if (restoreError != 0) {
                    android.util.Log.w("MobileHorizon",
                            "Não foi possível restaurar o diretório de trabalho do launcher (errno "
                                    + restoreError + ")");
                }
            } catch (UnsatisfiedLinkError error) {
                android.util.Log.w("MobileHorizon",
                        "JNI de diretório de trabalho indisponível ao encerrar a instância", error);
            }
            previousWorkingDirectory = null;
        }
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
