package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Multi-touch overlay: joystick, mouse look, and compact action buttons. */
final class NativeTouchControlsView extends View {
    static final String PREFERENCES = "mobilehorizon_touch_controls";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_SCALE = "scale";
    static final String KEY_OPACITY = "opacity";
    static final String KEY_SENSITIVITY = "sensitivity";
    static final String KEY_LEFT_HANDED = "left_handed";
    static final String KEY_LAYOUT = "button_layout_v2";
    static final String KEY_CUSTOM_BUTTONS = "custom_buttons_v1";
    static final String KEY_HIDDEN_BUTTONS = "hidden_buttons_v1";
    static final String KEY_AUTO_SPRINT = "auto_sprint";
    static final String KEY_SPRINT_THRESHOLD = "auto_sprint_threshold";
    static final String KEY_SNAP_GAP = "snap_gap_dp";
    private static final String KEY_UNIFORM_SIZE_VERSION = "uniform_button_size_version";
    private static final String KEY_FLOAT_MENU_X = "floating_menu_x";
    private static final String KEY_FLOAT_MENU_Y = "floating_menu_y";

    private static final long LONG_PRESS_TIMEOUT_MS = 430L;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 300L;

    private static final int FUNCTION_DEFAULT = 0;
    private static final int FUNCTION_KEY = 1;
    private static final int FUNCTION_MOUSE_LEFT = 2;
    private static final int FUNCTION_MOUSE_RIGHT = 3;
    private static final int FUNCTION_SCROLL_UP = 4;
    private static final int FUNCTION_SCROLL_DOWN = 5;
    private static final int FUNCTION_KEY_TOGGLE = 6;
    private static final int FUNCTION_DEFAULT_TOGGLE = 7;

    private static final int NONE = 0, JOYSTICK = 1, TOGGLE = 2, JUMP = 3, SNEAK = 4,
            SPRINT = 5, INVENTORY = 6, ATTACK = 7, USE = 8, ESCAPE = 9,
            DROP = 10, SWAP = 11, PICK = 12, PERSPECTIVE = 13, CHAT = 14,
            HOTBAR_PREV = 15, HOTBAR_NEXT = 16, TAB = 17, ENTER = 18, IGNORED = 19,
            MOUSE_LEFT = 20, MOUSE_RIGHT = 21, KEYBOARD = 22, EDITOR = 23,
            ADD_BUTTON = 24, DONE_EDITING = 25, FLOATING_MENU = 26,
            HOTBAR_SLOT_START = 100, CUSTOM_BUTTON_START = 1000;
    private final Activity activity;
    private final NativeGameActivity gameActivity;
    private final boolean previewOnly;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snapMeasurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();
    private final Map<Integer, PointerTrack> pointers = new HashMap<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Set<Integer> pressedControls = new HashSet<>();
    private final Set<Integer> joystickKeys = new HashSet<>();
    private final Set<Integer> hiddenButtons = new HashSet<>();
    private final Set<Integer> toggleActiveControls = new HashSet<>();
    private final Map<Integer, Integer> controlPointerCounts = new HashMap<>();
    private final Map<Integer, ButtonLayout> buttonLayouts = new HashMap<>();
    private final List<CustomButton> customButtons = new ArrayList<>();
    private final Handler modeHandler = new Handler(Looper.getMainLooper());
    private boolean editingControls;
    private boolean gameplayMode;
    private float snapGapDp = 12f;
    private float floatingMenuX = 0.52f;
    private float floatingMenuY = 0.14f;
    private PopupWindow activeButtonMenu;
    /** Alignment guide coordinates in view pixels; NaN means no active snap. */
    private float snapGuideX = Float.NaN;
    private float snapGuideY = Float.NaN;
    private final Runnable modePoll = new Runnable() {
        @Override public void run() {
            boolean active = gameActivity != null && gameActivity.isGameplayMode();
            if (active != gameplayMode) {
                gameplayMode = active;
                invalidate();
            }
            modeHandler.postDelayed(this, 80);
        }
    };

    private final float density;
    private float scale;
    private float opacity;
    private float sensitivity;
    private boolean leftHanded;
    private boolean autoSprint;
    private float sprintThreshold = 0.88f;
    private float joystickForwardStrength;
    private boolean controlsVisible = true;
    private long lastHotbarTapTime;
    private int lastHotbarTapSlot = -1;
    private float guiScrollAccumulator;
    private int joystickPointer = -1;
    private float joystickX, joystickY, virtualMouseX, virtualMouseY;
    private int mouseButtons;
    private boolean cursorInitialized;

    private static final class PointerTrack {
        final float startX, startY;
        float lastX, lastY;
        final int control;
        final boolean startGameplay;
        boolean moved;
        boolean editorDrag;
        boolean menuDrag;
        boolean longPressFired;
        boolean suppressTap;
        boolean rightClickGesture;
        boolean guiScrollGesture;
        int longPressButton;
        Runnable longPressAction;
        PointerTrack(float x, float y, int control, boolean startGameplay) {
            startX = lastX = x; startY = lastY = y;
            this.control = control; this.startGameplay = startGameplay;
        }
    }

    private static final class ButtonSpec {
        final int id;
        String label;
        float x, y, radius;
        final boolean custom;
        int keyCode;
        float opacity = 1f;
        int shape = 0;
        int function = FUNCTION_DEFAULT;
        ButtonSpec(int id, String label, float x, float y, float radius) {
            this(id, label, x, y, radius, false, 0);
        }
        ButtonSpec(int id, String label, float x, float y, float radius, boolean custom, int keyCode) {
            this.id = id; this.label = label; this.x = x; this.y = y; this.radius = radius;
            this.custom = custom; this.keyCode = keyCode;
        }
    }

    private static final class ButtonLayout {
        float x = -1f;
        float y = -1f;
        float scale = 1f;
        float opacity = 1f;
        int shape = 0; // 0 rounded rectangle, 1 circle/oval, 2 square
        int function = FUNCTION_DEFAULT;
        int keyCode = KeyEvent.KEYCODE_UNKNOWN;
        String label;
    }

    private static final class CustomButton {
        final int id;
        String label;
        int keyCode;
        float x, y, scale;
        float opacity = 1f;
        int shape = 0;
        int function = FUNCTION_KEY;
        CustomButton(int id, String label, int keyCode, float x, float y, float scale) {
            this.id = id; this.label = label; this.keyCode = keyCode;
            this.x = x; this.y = y; this.scale = scale;
        }
    }

    NativeTouchControlsView(NativeGameActivity activity) {
        this(activity, false);
    }

    /** Preview mode reuses the exact same persisted layout without injecting input into a game. */
    NativeTouchControlsView(Activity activity, boolean previewOnly) {
        super(activity);
        this.activity = activity;
        this.gameActivity = activity instanceof NativeGameActivity ? (NativeGameActivity) activity : null;
        this.previewOnly = previewOnly;
        density = getResources().getDisplayMetrics().density;
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        scale = clamp(prefs.getFloat(KEY_SCALE, 1f), 0.70f, 1.30f);
        opacity = clamp(prefs.getFloat(KEY_OPACITY, 0.82f), 0.35f, 1f);
        sensitivity = clamp(prefs.getFloat(KEY_SENSITIVITY, 1f), 0.50f, 2f);
        leftHanded = prefs.getBoolean(KEY_LEFT_HANDED, false);
        snapGapDp = clamp(prefs.getFloat(KEY_SNAP_GAP, 12f), 4f, 32f);
        loadButtonLayouts(prefs.getString(KEY_LAYOUT, ""));
        loadCustomButtons(prefs.getString(KEY_CUSTOM_BUTTONS, ""));
        autoSprint = prefs.getBoolean(KEY_AUTO_SPRINT, false);
        sprintThreshold = clamp(prefs.getFloat(KEY_SPRINT_THRESHOLD, 0.88f), 0.60f, 0.99f);
        String hiddenButtonIds = prefs.getString(KEY_HIDDEN_BUTTONS, "");
        for (String value : hiddenButtonIds.split(",")) {
            try { if (!value.trim().isEmpty()) hiddenButtons.add(Integer.parseInt(value.trim())); }
            catch (NumberFormatException ignored) { }
        }
        if (prefs.getInt(KEY_UNIFORM_SIZE_VERSION, 0) < 1) {
            for (Map.Entry<Integer, ButtonLayout> entry : buttonLayouts.entrySet()) {
                int id = entry.getKey();
                if (id != JOYSTICK && id < CUSTOM_BUTTON_START) {
                    entry.getValue().scale = clamp(entry.getValue().scale * defaultButtonScale(id), 0.60f, 2.40f);
                }
            }
            if (!buttonLayouts.isEmpty()) saveButtonLayouts();
            prefs.edit().putInt(KEY_UNIFORM_SIZE_VERSION, 1).apply();
        }
        floatingMenuX = clamp(prefs.getFloat(KEY_FLOAT_MENU_X, 0.52f), 0.04f, 0.96f);
        floatingMenuY = clamp(prefs.getFloat(KEY_FLOAT_MENU_Y, 0.14f), 0.06f, 0.94f);
        gameplayMode = gameActivity != null && gameActivity.isGameplayMode();
        if (gameActivity != null) modeHandler.post(modePoll);
        if (previewOnly) {
            controlsVisible = true;
            editingControls = true;
        }
        setBackgroundColor(Color.TRANSPARENT);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(true);
        virtualMouseX = getResources().getDisplayMetrics().widthPixels / 2f;
        virtualMouseY = getResources().getDisplayMetrics().heightPixels / 2f;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (controlsVisible) {
            drawSnapGuides(canvas);
            drawJoystick(canvas);
            for (ButtonSpec spec : buttonSpecs()) {
                if (spec.id != JOYSTICK) drawButton(canvas, spec);
            }
        }
        if (!previewOnly) {
            drawToolbar(canvas);
            drawFloatingMenuButton(canvas);
        }
        if (editingControls) drawEditorToolbar(canvas);
        drawMouseCursor(canvas);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            if (!cursorInitialized) {
                virtualMouseX = w / 2f;
                virtualMouseY = h / 2f;
                cursorInitialized = true;
            } else {
                virtualMouseX = clamp(virtualMouseX, 0, w - 1f);
                virtualMouseY = clamp(virtualMouseY, 0, h - 1f);
            }
        }
        invalidate();
    }

    /** High-contrast virtual cursor, hidden whenever Minecraft currently captures the native cursor. */
    private void drawMouseCursor(Canvas canvas) {
        if (previewOnly || getWidth() <= 0 || getHeight() <= 0 || !cursorInitialized) return;
        if (gameActivity != null) gameplayMode = gameActivity.isGameplayMode();
        if (gameplayMode && !editingControls) return;
        float size = dp(24);
        float x = clamp(virtualMouseX, dp(3), Math.max(dp(3), getWidth() - size - dp(2)));
        float y = clamp(virtualMouseY, dp(3), Math.max(dp(3), getHeight() - size - dp(2)));
        cursorPath.reset();
        cursorPath.moveTo(x, y);
        cursorPath.lineTo(x + dp(2), y + dp(22));
        cursorPath.lineTo(x + dp(7), y + dp(16));
        cursorPath.lineTo(x + dp(13), y + dp(27));
        cursorPath.lineTo(x + dp(18), y + dp(24));
        cursorPath.lineTo(x + dp(12), y + dp(14));
        cursorPath.lineTo(x + dp(21), y + dp(13));
        cursorPath.close();
        canvas.save();
        canvas.translate(dp(1.2f), dp(1.2f));
        paint.setColor(0xCC000000);
        canvas.drawPath(cursorPath, paint);
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF9FCFF);
        canvas.drawPath(cursorPath, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.6f));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xFF17243A);
        canvas.drawPath(cursorPath, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawJoystick(Canvas canvas) {
        float cx = joystickPointer == -1 ? joystickHomeX() : joystickX;
        float cy = joystickPointer == -1 ? joystickHomeY() : joystickY;
        ButtonSpec spec = findButton(JOYSTICK);
        float radius = spec == null ? dp(67) * scale : spec.radius;
        float effectiveOpacity = opacity * (spec == null ? 1f : clamp(spec.opacity, 0.20f, 1f));
        int shape = spec == null ? 0 : spec.shape;
        drawJoystickBase(canvas, cx, cy, radius, shape, effectiveOpacity);
        float knobX = cx, knobY = cy;
        if (joystickPointer != -1) {
            PointerTrack track = pointers.get(joystickPointer);
            if (track != null) {
                float dx = track.lastX - cx, dy = track.lastY - cy;
                float len = (float) Math.sqrt(dx * dx + dy * dy), max = radius * 0.66f;
                if (len > max && len > 0) { dx *= max / len; dy *= max / len; }
                knobX += dx; knobY += dy;
            }
        }
        circle(canvas, knobX, knobY, radius * 0.37f, false, 0.94f, effectiveOpacity);
        circle(canvas, knobX, knobY, radius * 0.37f, true, 1f, effectiveOpacity);
    }

    private void drawJoystickBase(Canvas canvas, float cx, float cy, float radius, int shape, float effectiveOpacity) {
        RectF bounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.round(255 * effectiveOpacity * 0.60f), 18, 26, 44));
        drawJoystickShape(canvas, bounds, radius, shape);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(Color.argb(Math.round(255 * effectiveOpacity * 0.85f), 228, 236, 255));
        drawJoystickShape(canvas, bounds, radius, shape);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawJoystickShape(Canvas canvas, RectF bounds, float radius, int shape) {
        if (shape == 1) canvas.drawOval(bounds, paint);
        else if (shape == 2) canvas.drawRect(bounds, paint);
        else canvas.drawRoundRect(bounds, radius * 0.88f, radius * 0.88f, paint);
    }

    /** Cyan guides show the axes/edges to which the dragged control is snapping. */
    private void drawSnapGuides(Canvas canvas) {
        if (!editingControls || (Float.isNaN(snapGuideX) && Float.isNaN(snapGuideY))) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp(1.25f)));
        paint.setColor(0xCC45E4F5);
        if (!Float.isNaN(snapGuideX)) canvas.drawLine(snapGuideX, 0, snapGuideX, getHeight(), paint);
        if (!Float.isNaN(snapGuideY)) canvas.drawLine(0, snapGuideY, getWidth(), snapGuideY, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawButton(Canvas canvas, ButtonSpec spec) {
        boolean pressed = pressedControls.contains(spec.id) || toggleActiveControls.contains(spec.id);
        float r = spec.radius;
        float buttonOpacity = opacity * clamp(spec.opacity, 0.20f, 1f);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(dp(8), r * 0.48f));
        paint.setFakeBoldText(true);
        float halfWidth = Math.max(r * 0.86f, paint.measureText(spec.label) / 2f + dp(7));
        float halfHeight = r * 0.76f;
        RectF bounds = new RectF(spec.x - halfWidth, spec.y - halfHeight,
                spec.x + halfWidth, spec.y + halfHeight);
        float corner = Math.min(dp(10), halfHeight * 0.55f);
        paint.setColor(Color.argb(Math.round(255 * buttonOpacity * (pressed ? 0.94f : 0.72f)),
                pressed ? 54 : 25, pressed ? 91 : 31, pressed ? 116 : 39));
        drawButtonShape(canvas, bounds, corner, spec.shape);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(editingControls ? 2.2f : 1.5f));
        paint.setColor(Color.argb(Math.round(255 * buttonOpacity * (pressed ? 1f : 0.86f)),
                pressed ? 157 : 155, pressed ? 211 : 166, pressed ? 245 : 187));
        drawButtonShape(canvas, bounds, corner, spec.shape);
        if (editingControls) {
            RectF selection = new RectF(bounds.left - dp(3), bounds.top - dp(3),
                    bounds.right + dp(3), bounds.bottom + dp(3));
            paint.setStrokeWidth(dp(1f));
            paint.setColor(Color.argb(210, 115, 205, 255));
            drawButtonShape(canvas, selection, corner + dp(2), spec.shape);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(spec.label, spec.x, spec.y - (metrics.ascent + metrics.descent) / 2f, paint);
        paint.setFakeBoldText(false);
    }

    private void drawButtonShape(Canvas canvas, RectF bounds, float corner, int shape) {
        if (shape == 1) canvas.drawOval(bounds, paint);
        else if (shape == 2) canvas.drawRect(bounds, paint);
        else canvas.drawRoundRect(bounds, corner, corner, paint);
    }

    private static int clampShape(int shape) { return Math.max(0, Math.min(2, shape)); }
    private static int clampFunction(int function) { return Math.max(FUNCTION_DEFAULT, Math.min(FUNCTION_DEFAULT_TOGGLE, function)); }

    private float toolbarX(float offset) { return mirrorControlX(dp(offset)); }
    private float toolbarY() { return dp(31); }

    private void drawToolbar(Canvas canvas) {
        if (controlsVisible) drawToolbarButton(canvas, KEYBOARD, "⌨", toolbarX(34), toolbarY());
        drawToolbarButton(canvas, TOGGLE, controlsVisible ? "×" : "+", toolbarX(78), toolbarY());
    }

    private float floatingMenuPixelX() {
        return clamp(floatingMenuX * getWidth(), dp(24), Math.max(dp(24), getWidth() - dp(24)));
    }

    private float floatingMenuPixelY() {
        return clamp(floatingMenuY * getHeight(), dp(24), Math.max(dp(24), getHeight() - dp(24)));
    }

    private void drawFloatingMenuButton(Canvas canvas) {
        if (previewOnly || getWidth() <= 0 || getHeight() <= 0) return;
        float x = floatingMenuPixelX(), y = floatingMenuPixelY(), radius = dp(18);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE22A4261);
        canvas.drawRoundRect(x - radius, y - radius, x + radius, y + radius, dp(11), dp(11), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(0xFFE4F4FF);
        canvas.drawRoundRect(x - radius, y - radius, x + radius, y + radius, dp(11), dp(11), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.WHITE);
        for (int i = -1; i <= 1; i++) {
            float yy = y + i * dp(5);
            canvas.drawLine(x - dp(7), yy, x + dp(7), yy, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawEditorToolbar(Canvas canvas) {
        drawToolbarButton(canvas, ADD_BUTTON, "+", toolbarX(166), toolbarY());
        drawToolbarButton(canvas, DONE_EDITING, "✓", toolbarX(210), toolbarY());
    }

    private void drawToolbarButton(Canvas canvas, int id, String label, float x, float y) {
        float radius = dp(id == TOGGLE ? 18 : 17);
        boolean active = id == EDITOR && editingControls;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.round(238 * opacity), active ? 43 : 25,
                active ? 83 : 30, active ? 111 : 38));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(Color.argb(224, active ? 157 : 162, active ? 211 : 174, active ? 245 : 193));
        canvas.drawCircle(x, y, radius - dp(1), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(label.length() > 2 ? 10 : 15));
        paint.setFakeBoldText(true);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(label, x, y - (metrics.ascent + metrics.descent) / 2f, paint);
        paint.setFakeBoldText(false);
    }

    private ButtonSpec[] buttonSpecs() {
        float s = scale, w = getWidth(), h = getHeight();
        List<ButtonSpec> specs = new ArrayList<>();
        addButtonSpec(specs, JOYSTICK, "JOY", joystickHomeX(), joystickHomeY(), dp(67) * s);
        addButtonSpec(specs, MOUSE_LEFT, "LMB", mirrorControlX(w - dp(164) * s), dp(31) * s, dp(20) * s);
        addButtonSpec(specs, MOUSE_RIGHT, "RMB", mirrorControlX(w - dp(216) * s), dp(31) * s, dp(20) * s);
        addButtonSpec(specs, JUMP, "↑", mirrorControlX(w - dp(72) * s), h - dp(109) * s, dp(32) * s);
        addButtonSpec(specs, SNEAK, "SNEAK", mirrorControlX(w - dp(141) * s), h - dp(55) * s, dp(25) * s);
        addButtonSpec(specs, SPRINT, "RUN", mirrorControlX(w - dp(72) * s), h - dp(55) * s, dp(25) * s);
        addButtonSpec(specs, INVENTORY, "INV", mirrorControlX(w - dp(210) * s), h - dp(55) * s, dp(24) * s);
        addButtonSpec(specs, DROP, "Q", mirrorControlX(w - dp(210) * s), h - dp(113) * s, dp(20) * s);
        addButtonSpec(specs, SWAP, "F", mirrorControlX(w - dp(210) * s), h - dp(166) * s, dp(20) * s);
        addButtonSpec(specs, ATTACK, "HIT", mirrorControlX(w - dp(137) * s), h - dp(196) * s, dp(34) * s);
        addButtonSpec(specs, USE, "USE", mirrorControlX(w - dp(61) * s), h - dp(222) * s, dp(28) * s);
        addButtonSpec(specs, PICK, "MID", mirrorControlX(w - dp(145) * s), h - dp(265) * s, dp(22) * s);
        addButtonSpec(specs, PERSPECTIVE, "F5", mirrorControlX(w - dp(210) * s), h - dp(218) * s, dp(21) * s);
        addButtonSpec(specs, CHAT, "T", mirrorControlX(w - dp(285) * s), h - dp(55) * s, dp(21) * s);
        addButtonSpec(specs, HOTBAR_PREV, "‹", mirrorControlX(w - dp(285) * s), h - dp(112) * s, dp(20) * s);
        addButtonSpec(specs, HOTBAR_NEXT, "›", mirrorControlX(w - dp(285) * s), h - dp(166) * s, dp(20) * s);
        addButtonSpec(specs, TAB, "TAB", mirrorControlX(w - dp(285) * s), h - dp(218) * s, dp(22) * s);
        addButtonSpec(specs, ENTER, "ENT", mirrorControlX(w - dp(285) * s), h - dp(265) * s, dp(22) * s);
        addButtonSpec(specs, ESCAPE, "ESC", mirrorControlX(w - dp(36)), dp(31), dp(21));
        for (CustomButton custom : customButtons) {
            float radius = dp(24) * s * custom.scale;
            ButtonSpec spec = new ButtonSpec(custom.id, custom.label,
                    clamp(custom.x * w, radius, Math.max(radius, w - radius)),
                    clamp(custom.y * h, radius, Math.max(radius, h - radius)),
                    radius, true, custom.keyCode);
            spec.opacity = custom.opacity;
            spec.shape = custom.shape;
            spec.function = custom.function;
            specs.add(spec);
        }
        return specs.toArray(new ButtonSpec[0]);
    }

    private void addButtonSpec(List<ButtonSpec> specs, int id, String label, float x, float y, float legacyRadius) {
        if (id != JOYSTICK && hiddenButtons.contains(id)) return;
        ButtonLayout layout = buttonLayouts.get(id);
        float baseRadius = id == JOYSTICK ? dp(67) * scale : dp(24) * scale;
        float defaultScale = id == JOYSTICK ? 1f
                : clamp(legacyRadius / Math.max(1f, dp(24) * scale), 0.60f, 2.40f);
        float itemScale = layout == null ? defaultScale : layout.scale;
        ButtonSpec spec = new ButtonSpec(id, label, x, y, baseRadius * itemScale);
        if (layout != null) {
            if (layout.label != null && !layout.label.trim().isEmpty()) spec.label = layout.label;
            if (layout.x >= 0f) spec.x = layout.x * getWidth();
            if (layout.y >= 0f) spec.y = layout.y * getHeight();
            spec.opacity = layout.opacity;
            spec.shape = layout.shape;
            spec.function = layout.function;
            spec.keyCode = layout.keyCode;
        }
        specs.add(spec);
    }

    private float defaultButtonScale(int id) {
        return id == JOYSTICK ? 1f : legacyButtonRadiusDp(id) / 24f;
    }

    private float legacyButtonRadiusDp(int id) {
        switch (id) {
            case MOUSE_LEFT: case MOUSE_RIGHT: case DROP: case SWAP:
            case HOTBAR_PREV: case HOTBAR_NEXT: return 20f;
            case JUMP: return 32f;
            case SNEAK: case SPRINT: return 25f;
            case INVENTORY: return 24f;
            case ATTACK: return 34f;
            case USE: return 28f;
            case PICK: return 22f;
            case PERSPECTIVE: case CHAT: case ESCAPE: return 21f;
            case TAB: case ENTER: return 22f;
            default: return 24f;
        }
    }

    private ButtonLayout newDefaultButtonLayout(int id) {
        ButtonLayout layout = new ButtonLayout();
        layout.scale = defaultButtonScale(id);
        return layout;
    }

    private void loadButtonLayouts(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray items = new JSONArray(json);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                ButtonLayout layout = new ButtonLayout();
                layout.x = (float) item.optDouble("x", -1);
                layout.y = (float) item.optDouble("y", -1);
                layout.scale = clamp((float) item.optDouble("scale", 1), 0.60f, 2.40f);
                layout.opacity = clamp((float) item.optDouble("opacity", 1), 0.20f, 1f);
                layout.shape = clampShape(item.optInt("shape", 0));
                layout.function = clampFunction(item.optInt("function", FUNCTION_DEFAULT));
                layout.keyCode = item.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN);
                String savedLabel = item.optString("label", "").trim();
                layout.label = savedLabel.isEmpty() ? null : savedLabel;
                buttonLayouts.put(item.optInt("id", -1), layout);
            }
        } catch (Exception error) {
            android.util.Log.w("MobileHorizon", "Layout de controles inválido; usando posições padrão.", error);
        }
    }

    private void loadCustomButtons(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray items = new JSONArray(json);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                int id = item.optInt("id", -1);
                int keyCode = item.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN);
                String label = item.optString("label", "").trim();
                if (id < CUSTOM_BUTTON_START || keyCode == KeyEvent.KEYCODE_UNKNOWN || label.isEmpty()) continue;
                CustomButton custom = new CustomButton(id, label, keyCode,
                        clamp((float) item.optDouble("x", 0.62), 0.03f, 0.97f),
                        clamp((float) item.optDouble("y", 0.55), 0.05f, 0.95f),
                        clamp((float) item.optDouble("scale", 1), 0.60f, 2.40f));
                custom.opacity = clamp((float) item.optDouble("opacity", 1), 0.20f, 1f);
                custom.shape = clampShape(item.optInt("shape", 0));
                custom.function = clampFunction(item.optInt("function", FUNCTION_KEY));
                customButtons.add(custom);
            }
        } catch (Exception error) {
            android.util.Log.w("MobileHorizon", "Botões personalizados inválidos; ignorando o perfil.", error);
        }
    }

    private void saveButtonLayouts() {
        JSONArray items = new JSONArray();
        try {
            for (Map.Entry<Integer, ButtonLayout> entry : buttonLayouts.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("id", entry.getKey());
                item.put("x", entry.getValue().x);
                item.put("y", entry.getValue().y);
                item.put("scale", entry.getValue().scale);
                item.put("opacity", entry.getValue().opacity);
                item.put("shape", entry.getValue().shape);
                item.put("function", entry.getValue().function);
                item.put("keyCode", entry.getValue().keyCode);
                if (entry.getValue().label != null && !entry.getValue().label.trim().isEmpty()) {
                    item.put("label", entry.getValue().label);
                }
                items.put(item);
            }
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LAYOUT, items.toString()).apply();
        } catch (Exception error) {
            android.util.Log.w("MobileHorizon", "Não foi possível salvar o layout dos controles.", error);
        }
    }

    private void saveHiddenButtons() {
        List<Integer> ordered = new ArrayList<>(hiddenButtons);
        java.util.Collections.sort(ordered);
        StringBuilder value = new StringBuilder();
        for (Integer id : ordered) {
            if (value.length() > 0) value.append(',');
            value.append(id);
        }
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(KEY_HIDDEN_BUTTONS, value.toString()).apply();
    }

    private void saveCustomButtons() {
        JSONArray items = new JSONArray();
        try {
            for (CustomButton custom : customButtons) {
                JSONObject item = new JSONObject();
                item.put("id", custom.id);
                item.put("label", custom.label);
                item.put("keyCode", custom.keyCode);
                item.put("x", custom.x);
                item.put("y", custom.y);
                item.put("scale", custom.scale);
                item.put("opacity", custom.opacity);
                item.put("shape", custom.shape);
                item.put("function", custom.function);
                items.put(item);
            }
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CUSTOM_BUTTONS, items.toString()).apply();
        } catch (Exception error) {
            android.util.Log.w("MobileHorizon", "Não foi possível salvar os botões personalizados.", error);
        }
    }

    private ButtonSpec findButton(int id) {
        for (ButtonSpec spec : buttonSpecs()) if (spec.id == id) return spec;
        return null;
    }

    private CustomButton findCustomButton(int id) {
        for (CustomButton item : customButtons) if (item.id == id) return item;
        return null;
    }

    private boolean isEditableButton(int id) {
        return findButton(id) != null;
    }

    private float buttonHalfWidth(ButtonSpec spec) {
        snapMeasurePaint.setTextSize(Math.max(dp(8), spec.radius * 0.48f));
        snapMeasurePaint.setFakeBoldText(true);
        return Math.max(spec.radius * 0.86f,
                snapMeasurePaint.measureText(spec.label) / 2f + dp(7));
    }

    private float buttonHalfHeight(ButtonSpec spec) { return spec.radius * 0.76f; }

    /**
     * Magnetic alignment deliberately chooses whole neighbour positions instead of
     * coincident edges/centres: it aligns rows/columns and preserves snapGapDp between buttons.
     */
    private void setButtonPosition(int id, float x, float y) {
        float width = Math.max(1, getWidth()), height = Math.max(1, getHeight());
        ButtonSpec[] specs = buttonSpecs();
        ButtonSpec moving = null;
        for (ButtonSpec spec : specs) if (spec.id == id) { moving = spec; break; }
        if (moving == null) return;

        float halfW = buttonHalfWidth(moving);
        float halfH = buttonHalfHeight(moving);
        float gap = dp(snapGapDp);
        float threshold = dp(15f);
        float bestX = x, bestY = y;
        float bestScore = Float.MAX_VALUE;
        snapGuideX = Float.NaN;
        snapGuideY = Float.NaN;

        for (ButtonSpec other : specs) {
            if (other.id == moving.id) continue;
            float otherHalfW = buttonHalfWidth(other);
            float otherHalfH = buttonHalfHeight(other);
            float leftX = other.x - otherHalfW - halfW - gap;
            float rightX = other.x + otherHalfW + halfW + gap;
            float aboveY = other.y - otherHalfH - halfH - gap;
            float belowY = other.y + otherHalfH + halfH + gap;

            float dx = Math.abs(x - leftX), dy = Math.abs(y - other.y);
            float score = dx * dx + dy * dy;
            if (dx <= threshold && dy <= threshold && score < bestScore) {
                bestScore = score; bestX = leftX; bestY = other.y;
                snapGuideY = other.y; snapGuideX = Float.NaN;
            }

            dx = Math.abs(x - rightX); dy = Math.abs(y - other.y);
            score = dx * dx + dy * dy;
            if (dx <= threshold && dy <= threshold && score < bestScore) {
                bestScore = score; bestX = rightX; bestY = other.y;
                snapGuideY = other.y; snapGuideX = Float.NaN;
            }

            dx = Math.abs(x - other.x); dy = Math.abs(y - aboveY);
            score = dx * dx + dy * dy;
            if (dx <= threshold && dy <= threshold && score < bestScore) {
                bestScore = score; bestX = other.x; bestY = aboveY;
                snapGuideX = other.x; snapGuideY = Float.NaN;
            }

            dx = Math.abs(x - other.x); dy = Math.abs(y - belowY);
            score = dx * dx + dy * dy;
            if (dx <= threshold && dy <= threshold && score < bestScore) {
                bestScore = score; bestX = other.x; bestY = belowY;
                snapGuideX = other.x; snapGuideY = Float.NaN;
            }
        }

        float nx = clamp(bestX / width, 0.035f, 0.965f);
        float ny = clamp(bestY / height, 0.055f, 0.945f);
        CustomButton custom = findCustomButton(id);
        if (custom != null) {
            custom.x = nx;
            custom.y = ny;
        } else {
            ButtonLayout layout = buttonLayouts.computeIfAbsent(id, ignored -> newDefaultButtonLayout(id));
            layout.x = nx;
            layout.y = ny;
        }
        invalidate();
    }

    private void showKeyboardDialog() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(AppLanguage.translate(activity, "Texto para o campo selecionado ou mensagem"));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) dp(20), (int) dp(8), (int) dp(20), (int) dp(4));
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(AppLanguage.translate(activity, "Teclado do Minecraft"))
                .setView(panel)
                .setNegativeButton(AppLanguage.translate(activity, "Cancelar"), (d, which) -> { })
                .setPositiveButton(AppLanguage.translate(activity, "Aplicar / enviar"), (d, which) -> {
                    if (gameActivity != null) gameActivity.injectKeyboardText(input.getText().toString());
                })
                .create();

        input.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterKey = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || enterKey) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });

        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager manager = (InputMethodManager)
                        activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        });
        dialog.show();
    }

    private void showAddButtonDialog() {
        EditText label = new EditText(activity);
        label.setSingleLine(true);
        label.setHint(AppLanguage.translate(activity, "Texto do botão (ex.: MAP)"));
        label.setText("G");
        EditText key = new EditText(activity);
        key.setSingleLine(true);
        key.setHint(AppLanguage.translate(activity, "Tecla (ex.: G, SPACE, F6)"));
        key.setText("G");
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) dp(20), (int) dp(8), (int) dp(20), (int) dp(4));
        panel.addView(label, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(key, new LinearLayout.LayoutParams(-1, -2));
        android.widget.CheckBox toggle = new android.widget.CheckBox(activity);
        toggle.setText(AppLanguage.translate(activity, "Alternar pressão: toque para ligar/desligar"));
        panel.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(AppLanguage.translate(activity, "Adicionar botão"))
                .setMessage(AppLanguage.translate(activity, "O botão mantém a tecla pressionada enquanto você segura, ou pode alternar a pressão a cada toque."))
                .setView(panel)
                .setNegativeButton(AppLanguage.translate(activity, "Cancelar"), (d, which) -> { })
                .setPositiveButton(AppLanguage.translate(activity, "Adicionar"), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String caption = label.getText().toString().trim();
            int keyCode = parseKeyCode(key.getText().toString());
            if (caption.isEmpty() || caption.length() > 7 || keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                Toast.makeText(activity, "Informe um nome curto e uma tecla válida.", Toast.LENGTH_LONG).show();
                return;
            }
            int id = CUSTOM_BUTTON_START;
            for (CustomButton item : customButtons) id = Math.max(id, item.id + 1);
            float x = 0.62f + (customButtons.size() % 3) * 0.055f;
            float y = 0.50f + (customButtons.size() % 4) * 0.055f;
            CustomButton created = new CustomButton(id, caption, keyCode,
                    clamp(x, 0.10f, 0.90f), clamp(y, 0.15f, 0.85f), 1f);
            created.function = toggle.isChecked() ? FUNCTION_KEY_TOGGLE : FUNCTION_KEY;
            customButtons.add(created);
            saveCustomButtons();
            invalidate();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private TextView miniLabel(String value, int size, boolean bold) {
        TextView label = new TextView(activity);
        label.setText(AppLanguage.translate(activity, value));
        label.setTextColor(0xffeaf0ff);
        label.setTextSize(size);
        if (bold) label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return label;
    }

    private void showGameControlMenu() {
        if (gameActivity == null || previewOnly) return;
        String[] items = new String[] {
                AppLanguage.translate(activity, "Personalizar layout"),
                AppLanguage.translate(activity, "Ajustes gerais dos controles"),
                AppLanguage.translate(activity, controlsVisible ? "Ocultar controles" : "Mostrar controles"),
                AppLanguage.translate(activity, "Fechar jogo")
        };
        new AlertDialog.Builder(gameActivity)
                .setTitle(AppLanguage.translate(activity, "Configurações do jogo"))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        controlsVisible = true;
                        editingControls = true;
                        snapGuideX = Float.NaN;
                        snapGuideY = Float.NaN;
                        Toast.makeText(activity, "Arraste os controles; toque em um para configurar. ✓ salva.", Toast.LENGTH_LONG).show();
                        invalidate();
                    } else if (which == 1) {
                        showGlobalSettingsDialog();
                    } else if (which == 2) {
                        controlsVisible = !controlsVisible;
                        if (!controlsVisible) {
                            editingControls = false;
                            releaseHeldControls();
                        }
                        invalidate();
                    } else if (which == 3) {
                        new AlertDialog.Builder(gameActivity)
                                .setTitle(AppLanguage.translate(activity, "Fechar o jogo?"))
                                .setMessage(AppLanguage.translate(activity, "Você voltará à tela inicial do MobileHorizon."))
                                .setNegativeButton(AppLanguage.translate(activity, "Cancelar"), (d, w) -> { })
                                .setPositiveButton(AppLanguage.translate(activity, "Fechar jogo"), (d, w) -> {
                                    releaseAllInputs();
                                    gameActivity.finish();
                                })
                                .show();
                    }
                })
                .show();
    }

    private void showButtonMiniMenu(int id) {
        ButtonSpec spec = findButton(id);
        if (spec == null || !isAttachedToWindow()) return;
        if (activeButtonMenu != null && activeButtonMenu.isShowing()) activeButtonMenu.dismiss();

        CustomButton custom = findCustomButton(id);
        ButtonLayout saved = buttonLayouts.get(id);
        float currentScale = custom != null ? custom.scale : (saved == null ? defaultButtonScale(id) : saved.scale);
        float currentOpacity = custom != null ? custom.opacity : (saved == null ? 1f : saved.opacity);
        int currentShape = custom != null ? custom.shape : (saved == null ? 0 : saved.shape);
        int savedFunction = custom != null ? custom.function : (saved == null ? FUNCTION_DEFAULT : saved.function);
        final int currentFunction = custom != null && savedFunction == FUNCTION_DEFAULT
                ? FUNCTION_KEY : savedFunction;
        int currentKey = custom != null ? custom.keyCode : (saved == null ? KeyEvent.KEYCODE_UNKNOWN : saved.keyCode);
        boolean joystickButton = id == JOYSTICK;
        boolean inGame = gameActivity != null && !previewOnly;
        final int[] chosenShape = {clampShape(currentShape)};

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpInt(10), dpInt(8), dpInt(10), dpInt(8));
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.addView(miniLabel("Botão: " + spec.label, 13, true),
                new LinearLayout.LayoutParams(-1, dpInt(24)));

        panel.addView(miniLabel("Posição na tela", 11, true),
                new LinearLayout.LayoutParams(-1, dpInt(19)));
        float normalizedX = clamp(spec.x / Math.max(1f, getWidth()), 0.03f, 0.97f);
        float normalizedY = clamp(spec.y / Math.max(1f, getHeight()), 0.05f, 0.95f);

        TextView xLabel = miniLabel("Posição X " + Math.round(normalizedX * 100) + "%", 11, false);
        SeekBar xPosition = new SeekBar(activity);
        xPosition.setMax(94);
        xPosition.setProgress(Math.round(normalizedX * 100f) - 3);
        panel.addView(xLabel, new LinearLayout.LayoutParams(-1, dpInt(17)));
        panel.addView(xPosition, new LinearLayout.LayoutParams(-1, dpInt(25)));
        xPosition.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                xLabel.setText("Posição X " + (progress + 3) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        TextView yLabel = miniLabel("Posição Y " + Math.round(normalizedY * 100) + "%", 11, false);
        SeekBar yPosition = new SeekBar(activity);
        yPosition.setMax(90);
        yPosition.setProgress(Math.round(normalizedY * 100f) - 5);
        panel.addView(yLabel, new LinearLayout.LayoutParams(-1, dpInt(17)));
        panel.addView(yPosition, new LinearLayout.LayoutParams(-1, dpInt(25)));
        yPosition.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                yLabel.setText("Posição Y " + (progress + 5) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        TextView scaleLabel = miniLabel("Tamanho " + Math.round(currentScale * 100) + "%", 11, false);
        SeekBar size = new SeekBar(activity);
        size.setMax(180);
        size.setProgress(Math.round(clamp(currentScale, 0.60f, 2.40f) * 100f) - 60);
        panel.addView(scaleLabel, new LinearLayout.LayoutParams(-1, dpInt(17)));
        panel.addView(size, new LinearLayout.LayoutParams(-1, dpInt(25)));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                scaleLabel.setText("Tamanho " + (progress + 60) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        TextView shapeLabel = miniLabel("Formato", 11, false);
        String[] shapeNames = new String[]{AppLanguage.translate(activity, "Retângulo arredondado"),
                AppLanguage.translate(activity, "Círculo / oval"), AppLanguage.translate(activity, "Quadrado")};
        Button shapeButton = new Button(activity);
        shapeButton.setTextSize(11);
        shapeButton.setMinHeight(dpInt(32));
        shapeButton.setText(AppLanguage.translate(activity, "Formato") + ": " + shapeNames[chosenShape[0]]);
        shapeButton.setOnClickListener(v -> {
            chosenShape[0] = (chosenShape[0] + 1) % shapeNames.length;
            shapeButton.setText(AppLanguage.translate(activity, "Formato") + ": " + shapeNames[chosenShape[0]]);
        });
        panel.addView(shapeLabel, new LinearLayout.LayoutParams(-1, dpInt(17)));
        panel.addView(shapeButton, new LinearLayout.LayoutParams(-1, dpInt(32)));

        TextView opacityLabel = miniLabel("Opacidade " + Math.round(currentOpacity * 100) + "%", 11, false);
        SeekBar alpha = new SeekBar(activity);
        alpha.setMax(80);
        alpha.setProgress(Math.round(clamp(currentOpacity, 0.20f, 1f) * 100f) - 20);
        panel.addView(opacityLabel, new LinearLayout.LayoutParams(-1, dpInt(17)));
        panel.addView(alpha, new LinearLayout.LayoutParams(-1, dpInt(25)));
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                opacityLabel.setText("Opacidade " + (progress + 20) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        Spinner function = new Spinner(activity);
        ArrayAdapter<String> functionAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item,
                new String[]{AppLanguage.translate(activity, "Padrão"),
                        AppLanguage.translate(activity, "Tecla personalizada"),
                        AppLanguage.translate(activity, "Clique esquerdo"),
                        AppLanguage.translate(activity, "Clique direito"),
                        AppLanguage.translate(activity, "Scroll para cima"),
                        AppLanguage.translate(activity, "Scroll para baixo")});
        functionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        function.setAdapter(functionAdapter);
        int spinnerFunction = currentFunction == FUNCTION_KEY_TOGGLE ? FUNCTION_KEY
                : currentFunction == FUNCTION_DEFAULT_TOGGLE ? FUNCTION_DEFAULT : currentFunction;
        function.setSelection(Math.max(0, Math.min(5, spinnerFunction)));

        EditText key = new EditText(activity);
        key.setSingleLine(true);
        key.setTextSize(12);
        key.setTextColor(Color.WHITE);
        key.setHintTextColor(0xffaeb9cb);
        key.setHint(AppLanguage.translate(activity, "Tecla, ex.: G, SPACE, F6"));
        key.setPadding(dpInt(7), 0, dpInt(7), 0);
        key.setText(currentKey == KeyEvent.KEYCODE_UNKNOWN ? "" : KeyEvent.keyCodeToString(currentKey).replace("KEYCODE_", ""));
        key.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff7e91ad));

        CheckBox toggleMode = new CheckBox(activity);
        toggleMode.setText(AppLanguage.translate(activity, "Alternar ação por toque (liga/desliga)"));
        toggleMode.setTextColor(0xffeaf0ff);
        toggleMode.setChecked(currentFunction == FUNCTION_KEY_TOGGLE
                || currentFunction == FUNCTION_DEFAULT_TOGGLE);
        if (!joystickButton && !inGame) {
            panel.addView(miniLabel("Ação do botão", 11, true), new LinearLayout.LayoutParams(-1, dpInt(17)));
            panel.addView(function, new LinearLayout.LayoutParams(-1, dpInt(32)));
            panel.addView(toggleMode, new LinearLayout.LayoutParams(-1, dpInt(34)));
            panel.addView(key, new LinearLayout.LayoutParams(-1, dpInt(34)));
            key.setVisibility(spinnerFunction == FUNCTION_KEY ? View.VISIBLE : View.GONE);
            Runnable updateToggleAvailability = () -> {
                int position = function.getSelectedItemPosition();
                boolean supported = position == FUNCTION_KEY
                        || (position == FUNCTION_DEFAULT && supportsDefaultToggle(id));
                toggleMode.setEnabled(supported);
                if (!supported) toggleMode.setChecked(false);
                toggleMode.setText(AppLanguage.translate(activity,
                        position == FUNCTION_KEY
                                ? "Alternar tecla por toque (liga/desliga)"
                                : "Alternar ação por toque (liga/desliga)"));
            };
            function.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long itemId) {
                    key.setVisibility(position == FUNCTION_KEY ? View.VISIBLE : View.GONE);
                    updateToggleAvailability.run();
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            updateToggleAvailability.run();
        }

        EditText label = null;
        if (!joystickButton && !inGame) {
            label = new EditText(activity);
            label.setSingleLine(true);
            label.setTextSize(12);
            label.setTextColor(Color.WHITE);
            label.setHintTextColor(0xffaeb9cb);
            label.setHint(AppLanguage.translate(activity, "Rótulo do botão (até 7 caracteres)"));
            label.setPadding(dpInt(7), 0, dpInt(7), 0);
            label.setText(spec.label);
            label.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff7e91ad));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, dpInt(34));
            labelParams.topMargin = dpInt(3);
            panel.addView(miniLabel("Texto exibido", 11, false), new LinearLayout.LayoutParams(-1, dpInt(17)));
            panel.addView(label, labelParams);
        }
        final EditText customLabel = label;

        CheckBox sprintEnabled = null;
        TextView sprintLabel = null;
        SeekBar sprintLimit = null;
        if (joystickButton) {
            sprintEnabled = new CheckBox(activity);
            sprintEnabled.setText(AppLanguage.translate(activity, "Corrida automática ao empurrar o joystick para frente"));
            sprintEnabled.setTextColor(0xffeaf0ff);
            sprintEnabled.setChecked(autoSprint);
            panel.addView(sprintEnabled, new LinearLayout.LayoutParams(-1, dpInt(36)));

            sprintLabel = miniLabel("Limiar de corrida " + Math.round(sprintThreshold * 100) + "%", 11, false);
            sprintLimit = new SeekBar(activity);
            sprintLimit.setMax(39);
            sprintLimit.setProgress(Math.round(sprintThreshold * 100f) - 60);
            panel.addView(sprintLabel, new LinearLayout.LayoutParams(-1, dpInt(17)));
            panel.addView(sprintLimit, new LinearLayout.LayoutParams(-1, dpInt(25)));
        }
        final CheckBox autoSprintInput = sprintEnabled;
        final TextView sprintThresholdLabel = sprintLabel;
        final SeekBar sprintThresholdInput = sprintLimit;
        if (sprintThresholdInput != null) {
            sprintThresholdInput.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    sprintThresholdLabel.setText(AppLanguage.translate(activity, "Limiar de corrida ") + (progress + 60) + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button reset = new Button(activity);
        reset.setText(AppLanguage.translate(activity, custom != null ? "Excluir" : joystickButton ? "Padrão" : "Ocultar botão"));
        reset.setTextSize(10);
        Button cancel = new Button(activity);
        cancel.setText(AppLanguage.translate(activity, "Fechar"));
        cancel.setTextSize(10);
        Button save = new Button(activity);
        save.setText(AppLanguage.translate(activity, "Salvar"));
        save.setTextSize(10);
        actions.addView(reset, new LinearLayout.LayoutParams(0, dpInt(38), 1f));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dpInt(38), 1f));
        actions.addView(save, new LinearLayout.LayoutParams(0, dpInt(38), 1f));
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dpInt(40)));

        ScrollView scroller = new ScrollView(activity);
        scroller.setFillViewport(false);
        scroller.setClipToPadding(false);
        scroller.addView(panel, new ScrollView.LayoutParams(-1, -2));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF21C2638);
        background.setCornerRadius(dpInt(12));
        background.setStroke(dpInt(1), 0xff7188a7);

        int[] screen = new int[2];
        getLocationOnScreen(screen);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int menuWidth = Math.min(dpInt(254), Math.max(dpInt(184), screenWidth - dpInt(16)));
        int menuHeight = Math.min(dpInt(350), Math.max(dpInt(220), screenHeight - dpInt(16)));
        scroller.measure(MeasureSpec.makeMeasureSpec(menuWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(menuHeight, MeasureSpec.AT_MOST));
        // A focusable PopupWindow steals focus from NativeActivity and may make
        // the native surface exit on some devices. Keep the in-game panel touchable
        // but non-focusable, and avoid nested focusable spinners/editors while playing.
        PopupWindow popup = new PopupWindow(scroller, menuWidth, menuHeight, !inGame);
        popup.setFocusable(!inGame);
        popup.setTouchable(true);
        popup.setBackgroundDrawable(background);
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(inGame
                ? PopupWindow.INPUT_METHOD_NOT_NEEDED : PopupWindow.INPUT_METHOD_NEEDED);
        popup.setClippingEnabled(true);
        popup.setElevation(dpInt(8));
        activeButtonMenu = popup;
        popup.setOnDismissListener(() -> {
            if (activeButtonMenu == popup) activeButtonMenu = null;
        });

        int buttonX = screen[0] + Math.round(spec.x);
        int buttonY = screen[1] + Math.round(spec.y);
        int menuX = buttonX > screenWidth / 2 ? buttonX - menuWidth - dpInt(12) : buttonX + dpInt(12);
        int menuY = buttonY > screenHeight / 2 ? buttonY - menuHeight - dpInt(8) : buttonY + dpInt(12);
        menuX = Math.max(dpInt(8), Math.min(screenWidth - menuWidth - dpInt(8), menuX));
        menuY = Math.max(dpInt(8), Math.min(screenHeight - menuHeight - dpInt(8), menuY));
        try {
            popup.showAtLocation(this, Gravity.TOP | Gravity.LEFT, menuX, menuY);
        } catch (RuntimeException error) {
            if (activeButtonMenu == popup) activeButtonMenu = null;
            android.util.Log.e("MobileHorizonTouch", "Falha ao abrir personalização de botão", error);
            Toast.makeText(activity, AppLanguage.translate(activity, "Não foi possível abrir a personalização sem interromper o jogo."), Toast.LENGTH_SHORT).show();
            return;
        }

        reset.setOnClickListener(v -> {
            if (custom != null) {
                customButtons.remove(custom);
                toggleActiveControls.remove(id);
                setKeyHeld(currentKey, false);
                saveCustomButtons();
            } else if (joystickButton) {
                buttonLayouts.remove(id);
                saveButtonLayouts();
            } else {
                hiddenButtons.add(id);
                saveHiddenButtons();
            }
            invalidate();
            popup.dismiss();
        });
        cancel.setOnClickListener(v -> popup.dismiss());
        save.setOnClickListener(v -> {
            int selectedFunction = currentFunction;
            int selectedKey = currentKey;
            String caption = customLabel == null ? spec.label : customLabel.getText().toString().trim();
            if (!joystickButton && !inGame) {
                selectedFunction = function.getSelectedItemPosition();
                selectedKey = parseKeyCode(key.getText().toString());
                if (toggleMode.isChecked()) {
                    if (selectedFunction == FUNCTION_KEY) selectedFunction = FUNCTION_KEY_TOGGLE;
                    else if (selectedFunction == FUNCTION_DEFAULT && supportsDefaultToggle(id)) selectedFunction = FUNCTION_DEFAULT_TOGGLE;
                }
            }
            if (!joystickButton && !inGame && isKeyFunction(selectedFunction)
                    && selectedKey == KeyEvent.KEYCODE_UNKNOWN) {
                Toast.makeText(activity, AppLanguage.translate(activity, "Informe uma tecla válida para essa função."), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!joystickButton && !inGame && (caption.trim().isEmpty() || caption.length() > 7)) {
                Toast.makeText(activity, AppLanguage.translate(activity, "Use um texto de até 7 caracteres."), Toast.LENGTH_SHORT).show();
                return;
            }

            float newScale = (size.getProgress() + 60) / 100f;
            float newOpacity = (alpha.getProgress() + 20) / 100f;
            int newShape = clampShape(chosenShape[0]);
            float newX = (xPosition.getProgress() + 3) / 100f;
            float newY = (yPosition.getProgress() + 5) / 100f;

            if (joystickButton && autoSprintInput != null && sprintThresholdInput != null) {
                autoSprint = autoSprintInput.isChecked();
                sprintThreshold = (sprintThresholdInput.getProgress() + 60) / 100f;
                activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_AUTO_SPRINT, autoSprint)
                        .putFloat(KEY_SPRINT_THRESHOLD, sprintThreshold)
                        .apply();
            }
            if (custom != null) {
                custom.label = caption;
                if (!inGame) {
                    custom.keyCode = selectedKey == KeyEvent.KEYCODE_UNKNOWN ? custom.keyCode : selectedKey;
                    custom.function = selectedFunction;
                    custom.x = newX;
                    custom.y = newY;
                }
                custom.scale = newScale;
                custom.opacity = newOpacity;
                custom.shape = newShape;
                saveCustomButtons();
            } else {
                ButtonLayout updated = buttonLayouts.computeIfAbsent(id, ignored -> newDefaultButtonLayout(id));
                updated.scale = newScale;
                updated.opacity = newOpacity;
                updated.shape = newShape;
                updated.x = newX;
                updated.y = newY;
                if (!joystickButton) {
                    if (!inGame) {
                        updated.function = selectedFunction;
                        updated.keyCode = isKeyFunction(selectedFunction) ? selectedKey : KeyEvent.KEYCODE_UNKNOWN;
                        updated.label = caption;
                    }
                }
                saveButtonLayouts();
            }
            updateSprintState();
            invalidate();
            popup.dismiss();
        });
    }

    void showGlobalSettingsDialog() {
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpInt(14), dpInt(4), dpInt(14), dpInt(4));

        android.widget.CheckBox enabled = new android.widget.CheckBox(activity);
        enabled.setText("Ativar controles na tela do jogo");
        enabled.setTextColor(0xff18213a);
        enabled.setChecked(prefs.getBoolean(KEY_ENABLED, true));
        panel.addView(enabled, new LinearLayout.LayoutParams(-1, -2));

        android.widget.CheckBox handed = new android.widget.CheckBox(activity);
        handed.setText(AppLanguage.translate(activity, "Layout canhoto (joystick à direita)"));
        handed.setTextColor(0xff18213a);
        handed.setChecked(leftHanded);
        panel.addView(handed, new LinearLayout.LayoutParams(-1, -2));

        Button restoreButtons = new Button(activity);
        restoreButtons.setText(AppLanguage.translate(activity, "Restaurar botões padrão removidos"));
        restoreButtons.setAllCaps(false);
        restoreButtons.setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setTitle(AppLanguage.translate(activity, "Restaurar botões"))
                .setMessage(AppLanguage.translate(activity, "Mostrar novamente todos os botões padrão removidos? As posições e tamanhos salvos serão preservados."))
                .setNegativeButton(AppLanguage.translate(activity, "Cancelar"), null)
                .setPositiveButton(AppLanguage.translate(activity, "Restaurar"), (d, w) -> {
                    hiddenButtons.clear();
                    saveHiddenButtons();
                    invalidate();
                    Toast.makeText(activity, AppLanguage.translate(activity, "Botões padrão restaurados."), Toast.LENGTH_SHORT).show();
                }).show());
        panel.addView(restoreButtons, new LinearLayout.LayoutParams(-1, dpInt(38)));

        TextView sizeLabel = textSetting("Tamanho geral " + Math.round(scale * 100) + "%");
        SeekBar size = new SeekBar(activity);
        size.setMax(60);
        size.setProgress(Math.round((scale - 0.70f) * 100f));
        panel.addView(sizeLabel, new LinearLayout.LayoutParams(-1, dpInt(19)));
        panel.addView(size, new LinearLayout.LayoutParams(-1, dpInt(30)));

        TextView alphaLabel = textSetting("Opacidade geral " + Math.round(opacity * 100) + "%");
        SeekBar alpha = new SeekBar(activity);
        alpha.setMax(65);
        alpha.setProgress(Math.round(opacity * 100f - 35f));
        panel.addView(alphaLabel, new LinearLayout.LayoutParams(-1, dpInt(19)));
        panel.addView(alpha, new LinearLayout.LayoutParams(-1, dpInt(30)));

        TextView sensitivityLabel = textSetting("Sensibilidade da câmera " + Math.round(sensitivity * 100) + "%");
        SeekBar sensitivityBar = new SeekBar(activity);
        sensitivityBar.setMax(150);
        sensitivityBar.setProgress(Math.round((sensitivity - 0.50f) * 100f));
        panel.addView(sensitivityLabel, new LinearLayout.LayoutParams(-1, dpInt(19)));
        panel.addView(sensitivityBar, new LinearLayout.LayoutParams(-1, dpInt(30)));

        TextView gapLabel = textSetting("Distância de encaixe " + Math.round(snapGapDp) + " dp");
        SeekBar gapBar = new SeekBar(activity);
        gapBar.setMax(28);
        gapBar.setProgress(Math.round(snapGapDp) - 4);
        panel.addView(gapLabel, new LinearLayout.LayoutParams(-1, dpInt(19)));
        panel.addView(gapBar, new LinearLayout.LayoutParams(-1, dpInt(30)));

        size.setOnSeekBarChangeListener(simpleSeek(progress -> sizeLabel.setText("Tamanho geral " + (70 + progress) + "%")));
        alpha.setOnSeekBarChangeListener(simpleSeek(progress -> alphaLabel.setText("Opacidade geral " + (35 + progress) + "%")));
        sensitivityBar.setOnSeekBarChangeListener(simpleSeek(progress -> sensitivityLabel.setText("Sensibilidade da câmera " + (50 + progress) + "%")));
        gapBar.setOnSeekBarChangeListener(simpleSeek(progress -> gapLabel.setText("Distância de encaixe " + (progress + 4) + " dp")));
        ScrollView settingsScroll = new ScrollView(activity);
        settingsScroll.setFillViewport(false);
        settingsScroll.addView(panel, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(AppLanguage.translate(activity, "Ajustes dos controles"))
                .setView(settingsScroll)
                .setNegativeButton(AppLanguage.translate(activity, "Cancelar"), null)
                .setPositiveButton(AppLanguage.translate(activity, "Salvar"), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            scale = (size.getProgress() + 70) / 100f;
            opacity = (alpha.getProgress() + 35) / 100f;
            sensitivity = (sensitivityBar.getProgress() + 50) / 100f;
            snapGapDp = gapBar.getProgress() + 4;
            leftHanded = handed.isChecked();
            prefs.edit()
                    .putBoolean(KEY_ENABLED, enabled.isChecked())
                    .putBoolean(KEY_LEFT_HANDED, leftHanded)
                    .putFloat(KEY_SCALE, scale)
                    .putFloat(KEY_OPACITY, opacity)
                    .putFloat(KEY_SENSITIVITY, sensitivity)
                    .putFloat(KEY_SNAP_GAP, snapGapDp)
                    .apply();
            updateSprintState();
            invalidate();
            Toast.makeText(activity, AppLanguage.translate(activity, "Ajustes dos controles salvos."), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private interface SeekValueChanged { void onChanged(int progress); }

    private SeekBar.OnSeekBarChangeListener simpleSeek(SeekValueChanged callback) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                callback.onChanged(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
    }

    private TextView textSetting(String value) {
        TextView text = new TextView(activity);
        text.setText(AppLanguage.translate(activity, value));
        text.setTextColor(0xff18213a);
        text.setTextSize(12);
        return text;
    }

    private void showButtonEditor(int id) {
        showButtonMiniMenu(id);
    }

    private boolean isKeyFunction(int function) {
        return function == FUNCTION_KEY || function == FUNCTION_KEY_TOGGLE;
    }

    private int parseKeyCode(String value) {
        String key = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (key.startsWith("KEYCODE_")) key = key.substring("KEYCODE_".length());
        if (key.startsWith("KEY_")) key = key.substring("KEY_".length());
        int result = KeyEvent.keyCodeFromString("KEYCODE_" + key);
        return result == KeyEvent.KEYCODE_UNKNOWN ? KeyEvent.KEYCODE_UNKNOWN : result;
    }

    private void circle(Canvas canvas, float x, float y, float radius, boolean stroke, float alpha, float effectiveOpacity) {
        paint.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        paint.setStrokeWidth(dp(1.4f));
        int a = Math.round(255 * effectiveOpacity * alpha);
        paint.setColor(stroke ? Color.argb(a, 228, 236, 255) : Color.argb(a, 18, 26, 44));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (gameActivity != null) gameplayMode = gameActivity.isGameplayMode();
        int action = event.getActionMasked(), index = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                addPointer(event, index); return true;
            case MotionEvent.ACTION_MOVE:
                movePointers(event); invalidate(); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                removePointer(event, index); invalidate(); return true;
            case MotionEvent.ACTION_CANCEL:
                releaseAllInputs(); return true;
            default: return true;
        }
    }

    private void addPointer(MotionEvent event, int index) {
        int id = event.getPointerId(index);
        float x = event.getX(index), y = event.getY(index);
        // The invisible hotbar hit zone wins at the bottom-center HUD area, so
        // taps on slots 1 and 9 are not swallowed by the joystick's larger hit radius.
        int slot = hitHotbarSlot(x, y);
        int control = slot >= 0 ? HOTBAR_SLOT_START + slot : hitControl(x, y);
        if (control == TOGGLE) {
            controlsVisible = !controlsVisible;
            if (!controlsVisible) {
                editingControls = false;
                releaseHeldControls();
            }
        } else if (control == KEYBOARD) {
            showKeyboardDialog();
            control = IGNORED;
        } else if (control == ADD_BUTTON && editingControls) {
            showAddButtonDialog();
            control = IGNORED;
        } else if (control == DONE_EDITING && editingControls) {
            editingControls = false;
            snapGuideX = Float.NaN;
            snapGuideY = Float.NaN;
            saveButtonLayouts();
            saveCustomButtons();
            if (previewOnly) {
                Toast.makeText(activity, "Layout dos botões salvo.", Toast.LENGTH_SHORT).show();
                activity.setResult(Activity.RESULT_OK);
                activity.finish();
            }
            control = IGNORED;
        }
        boolean editorDrag = editingControls && isEditableButton(control);
        if (control == JOYSTICK && !editorDrag) {
            if (joystickPointer == -1) {
                joystickPointer = id;
                joystickX = x;
                joystickY = y;
            } else {
                control = IGNORED;
            }
        }
        PointerTrack track = new PointerTrack(x, y, control, gameplayMode);
        track.editorDrag = editorDrag;
        track.menuDrag = control == FLOATING_MENU && !previewOnly;
        pointers.put(id, track);
        if (control == NONE || isHotbarSlotControl(control)) schedulePointerLongPress(id, track);
        if (control == NONE) tryStartTouchGesture(track);

        if (track.editorDrag || track.menuDrag) {
            // A tap opens a settings panel; dragging moves an editable control or menu button.
        } else if (control == JOYSTICK) updateJoystick(x, y);
        else if (control != NONE && control != TOGGLE && control != IGNORED
                && !isHotbarSlotControl(control)) activateControl(control, x, y);
        else if (control == NONE) {
            virtualMouseX = x;
            virtualMouseY = y;
            // In a captured Minecraft world the touch is a relative camera gesture,
            // not an absolute cursor warp. Menus need an absolute virtual cursor.
            if (!track.startGameplay) sendMouseMotion(virtualMouseX, virtualMouseY);
        }
        invalidate();
    }

    private void movePointers(MotionEvent event) {
        float menuScrollDelta = 0f;
        int menuScrollPointers = 0;
        float rightGestureDx = 0f, rightGestureDy = 0f, rightGestureDistance = 0f;
        boolean hasRightGestureMovement = false;

        for (int i = 0; i < event.getPointerCount(); i++) {
            PointerTrack track = pointers.get(event.getPointerId(i));
            if (track == null) continue;
            float x = event.getX(i), y = event.getY(i);
            float dx = x - track.lastX, dy = y - track.lastY;
            if (Math.abs(x - track.startX) > dp(7) || Math.abs(y - track.startY) > dp(7)) track.moved = true;

            if (track.menuDrag) {
                if (track.moved) {
                    floatingMenuX = clamp(x / Math.max(1f, getWidth()), 0.04f, 0.96f);
                    floatingMenuY = clamp(y / Math.max(1f, getHeight()), 0.06f, 0.94f);
                    invalidate();
                }
            } else if (track.editorDrag) {
                if (track.moved) setButtonPosition(track.control, x, y);
            } else if (track.control == JOYSTICK) {
                updateJoystick(x, y);
            } else if (isHotbarSlotControl(track.control)) {
                // The invisible hotbar hit zones act only on tap/long-press, never as camera input.
            } else if (track.control == NONE && track.guiScrollGesture) {
                menuScrollDelta += dy;
                menuScrollPointers++;
            } else if (track.control == NONE && track.startGameplay) {
                if (track.rightClickGesture) {
                    float distance = dx * dx + dy * dy;
                    if (track.moved && distance > rightGestureDistance) {
                        rightGestureDistance = distance;
                        rightGestureDx = dx;
                        rightGestureDy = dy;
                        hasRightGestureMovement = true;
                    }
                } else if (track.moved
                        && (Math.abs(dx) >= 0.75f || Math.abs(dy) >= 0.75f)
                        && gameActivity != null) {
                    // In-game touchpad: relative movement rotates the camera; no visible cursor is needed.
                    gameActivity.injectMouseMotion(dx * sensitivity, dy * sensitivity);
                }
            } else if (track.control == NONE) {
                // In menus/inventory, the touch position maps directly to the virtual pointer.
                virtualMouseX = clamp(x, 0, Math.max(1, getWidth()));
                virtualMouseY = clamp(y, 0, Math.max(1, getHeight()));
                sendMouseMotion(virtualMouseX, virtualMouseY);
            }
            track.lastX = x;
            track.lastY = y;
        }

        if (menuScrollPointers > 0) sendTouchScroll(menuScrollDelta / menuScrollPointers);
        if (hasRightGestureMovement && gameActivity != null) {
            // Use the largest finger delta so two fingers moving together do not double camera speed.
            gameActivity.injectMouseMotion(rightGestureDx * sensitivity, rightGestureDy * sensitivity);
        }
    }

    private void removePointer(MotionEvent event, int index) {
        int id = event.getPointerId(index);
        PointerTrack track = pointers.remove(id);
        if (track == null) return;
        cancelPointerLongPress(track);
        if (track.control == IGNORED) {
            invalidate();
            return;
        }
        if (track.menuDrag) {
            if (track.moved) {
                activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putFloat(KEY_FLOAT_MENU_X, floatingMenuX)
                        .putFloat(KEY_FLOAT_MENU_Y, floatingMenuY)
                        .apply();
            } else {
                showGameControlMenu();
            }
        } else if (track.editorDrag) {
            snapGuideX = Float.NaN;
            snapGuideY = Float.NaN;
            if (track.moved) {
                CustomButton custom = findCustomButton(track.control);
                if (custom != null) saveCustomButtons();
                else saveButtonLayouts();
            } else {
                showButtonEditor(track.control);
            }
        } else if (isHotbarSlotControl(track.control)) {
            if (!track.longPressFired && !track.moved && !track.suppressTap) {
                handleHotbarTap(track.control - HOTBAR_SLOT_START, event.getEventTime());
            }
        } else if (track.rightClickGesture) {
            releaseRightGestureIfFinished();
        } else if (track.guiScrollGesture) {
            // Two-finger swipe is a scroll gesture in menus; it must not click the GUI.
        } else if (track.control == JOYSTICK) {
            joystickPointer = -1;
            setJoystickDirections(false, false, false, false, 0f);
        } else if (track.control == NONE) {
            if (track.longPressFired) {
                if (track.longPressButton != 0) {
                    setMouseButton(track.longPressButton, false, virtualMouseX, virtualMouseY);
                }
            } else if (!track.suppressTap && !track.moved) {
                // Single tap is the primary click; long-press is reserved for held mouse actions.
                sendMouseClick(virtualMouseX, virtualMouseY, MotionEvent.BUTTON_PRIMARY, !track.startGameplay);
            }
        } else if (track.control != TOGGLE) {
            deactivateControl(track.control, track.lastX, track.lastY);
        }
        invalidate();
    }

    private int hitControl(float x, float y) {
        if (!previewOnly && distanceSquared(x, y, floatingMenuPixelX(), floatingMenuPixelY()) <= dp(25) * dp(25)) return FLOATING_MENU;
        if (previewOnly) {
            if (!controlsVisible) return NONE;
            if (editingControls) {
                if (distanceSquared(x, y, toolbarX(166), toolbarY()) <= dp(24) * dp(24)) return ADD_BUTTON;
                if (distanceSquared(x, y, toolbarX(210), toolbarY()) <= dp(24) * dp(24)) return DONE_EDITING;
            }
            ButtonSpec best = null;
            float bestDistance = Float.MAX_VALUE;
            for (ButtonSpec spec : buttonSpecs()) {
                float d = distanceSquared(x, y, spec.x, spec.y), hit = spec.radius + dp(13);
                if (d <= hit * hit && d < bestDistance) { best = spec; bestDistance = d; }
            }
            return best == null ? NONE : best.id;
        }
        float toggleX = toolbarX(78), toggleY = toolbarY();
        if (distanceSquared(x, y, toggleX, toggleY) <= dp(25) * dp(25)) return TOGGLE;
        if (!controlsVisible) return NONE;
        if (distanceSquared(x, y, toolbarX(34), toolbarY()) <= dp(24) * dp(24)) return KEYBOARD;
        if (editingControls) {
            if (distanceSquared(x, y, toolbarX(166), toolbarY()) <= dp(24) * dp(24)) return ADD_BUTTON;
            if (distanceSquared(x, y, toolbarX(210), toolbarY()) <= dp(24) * dp(24)) return DONE_EDITING;
        }
        ButtonSpec best = null; float bestDistance = Float.MAX_VALUE;
        for (ButtonSpec spec : buttonSpecs()) {
            float d = distanceSquared(x, y, spec.x, spec.y), hit = spec.radius + dp(13);
            if (d <= hit * hit && d < bestDistance) { best = spec; bestDistance = d; }
        }
        // While editing, prefer the visible button over the joystick hit region so
        // custom buttons can be dragged anywhere on screen.
        if (editingControls && best != null) return best.id;
        float jx = joystickHomeX(), jy = joystickHomeY(), jr = dp(82) * scale;
        if (distanceSquared(x, y, jx, jy) <= jr * jr) return JOYSTICK;
        return best == null ? NONE : best.id;
    }

    /**
     * Hit-test the nine hotbar slots in the exact logical GUI space used by Horizon.
     * Metrics are published by HudBatch::new, so this follows GUI Scale (including Auto),
     * renderer resize and the game's own ceil-divided GUI width/height.
     */
    private int hitHotbarSlot(float x, float y) {
        if (previewOnly || editingControls || !gameplayMode || gameActivity == null
                || getWidth() <= 0 || getHeight() <= 0) return -1;
        int[] metrics = gameActivity.getGuiMetrics();
        if (metrics == null || metrics.length < 5) return -1;
        int guiScale = metrics[0], guiWidth = metrics[1], guiHeight = metrics[2];
        int framebufferWidth = metrics[3], framebufferHeight = metrics[4];
        if (guiScale < 1 || guiWidth <= 0 || guiHeight <= 0
                || framebufferWidth <= 0 || framebufferHeight <= 0) return -1;

        // Map overlay coordinates to the native renderer's framebuffer, then into GUI pixels.
        float frameX = x * framebufferWidth / (float) getWidth();
        float frameY = y * framebufferHeight / (float) getHeight();
        float guiX = frameX / guiScale;
        float guiY = frameY / guiScale;

        int center = guiWidth / 2;
        float slotsLeft = center - 90f; // Nine 20-GUI-pixel slots; the frame has a 1-pixel border.
        float slotsRight = center + 90f;
        float hotbarTop = guiHeight - 22f;
        if (guiX < slotsLeft || guiX >= slotsRight
                || guiY < hotbarTop || guiY >= guiHeight) return -1;
        int slot = (int) ((guiX - slotsLeft) / 20f);
        return slot >= 0 && slot < 9 ? slot : -1;
    }

    private boolean isHotbarSlotControl(int control) {
        return control >= HOTBAR_SLOT_START && control < HOTBAR_SLOT_START + 9;
    }

    private void schedulePointerLongPress(int pointerId, PointerTrack track) {
        track.longPressAction = () -> {
            if (pointers.get(pointerId) != track || track.moved || track.suppressTap
                    || track.rightClickGesture || track.guiScrollGesture) return;
            track.longPressFired = true;
            if (isHotbarSlotControl(track.control)) {
                // A long press on a hotbar slot drops the selected item, like common Java launchers.
                pulseKey(KeyEvent.KEYCODE_Q);
            } else {
                // Hold attack/break in-world; in a GUI, hold right-click for item dragging/stack splitting.
                track.longPressButton = track.startGameplay
                        ? MotionEvent.BUTTON_PRIMARY : MotionEvent.BUTTON_SECONDARY;
                setMouseButton(track.longPressButton, true, virtualMouseX, virtualMouseY);
            }
            invalidate();
        };
        modeHandler.postDelayed(track.longPressAction, LONG_PRESS_TIMEOUT_MS);
    }

    private void cancelPointerLongPress(PointerTrack track) {
        if (track.longPressAction != null) {
            modeHandler.removeCallbacks(track.longPressAction);
            track.longPressAction = null;
        }
    }

    private void tryStartTouchGesture(PointerTrack newTrack) {
        if (newTrack.control != NONE) return;
        for (PointerTrack other : pointers.values()) {
            if (other == newTrack || other.control != NONE
                    || other.startGameplay != newTrack.startGameplay
                    || other.moved || other.longPressFired || other.suppressTap) continue;
            cancelPointerLongPress(other);
            cancelPointerLongPress(newTrack);
            other.suppressTap = true;
            newTrack.suppressTap = true;
            other.moved = true;
            newTrack.moved = true;
            if (newTrack.startGameplay) {
                // Two-finger touch holds right mouse so blocks can be placed while a finger moves the camera.
                other.rightClickGesture = true;
                newTrack.rightClickGesture = true;
                setMouseButton(MotionEvent.BUTTON_SECONDARY, true, virtualMouseX, virtualMouseY);
            } else {
                // In GUIs, a two-finger swipe becomes the mouse wheel instead of two accidental clicks.
                other.guiScrollGesture = true;
                newTrack.guiScrollGesture = true;
                guiScrollAccumulator = 0f;
            }
            return;
        }
    }

    private void releaseRightGestureIfFinished() {
        for (PointerTrack active : pointers.values()) {
            if (active.rightClickGesture) return;
        }
        setMouseButton(MotionEvent.BUTTON_SECONDARY, false, virtualMouseX, virtualMouseY);
    }

    private void sendTouchScroll(float deltaY) {
        guiScrollAccumulator += deltaY;
        float threshold = dp(24f);
        while (guiScrollAccumulator <= -threshold) {
            // Finger moving up scrolls the GUI downward.
            sendMouseScroll(-1f);
            guiScrollAccumulator += threshold;
        }
        while (guiScrollAccumulator >= threshold) {
            sendMouseScroll(1f);
            guiScrollAccumulator -= threshold;
        }
    }

    private void handleHotbarTap(int slot, long eventTime) {
        if (lastHotbarTapSlot == slot && eventTime - lastHotbarTapTime <= DOUBLE_TAP_TIMEOUT_MS) {
            pulseKey(KeyEvent.KEYCODE_F);
            lastHotbarTapSlot = -1;
            lastHotbarTapTime = 0L;
        } else {
            pulseKey(KeyEvent.KEYCODE_1 + slot);
            lastHotbarTapSlot = slot;
            lastHotbarTapTime = eventTime;
        }
    }

    private void updateJoystick(float x, float y) {
        PointerTrack track = pointers.get(joystickPointer);
        if (track == null) return;
        ButtonSpec joystickSpec = findButton(JOYSTICK);
        float dx = x - joystickX, dy = y - joystickY, radius = joystickSpec == null ? dp(67) * scale : joystickSpec.radius;
        float max = radius * 0.85f, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > max && len > 0) { dx *= max / len; dy *= max / len; }
        // Normalize against the joystick's actual travel limit. This makes 100%
        // attainable even though the knob is clamped before reaching the outer ring.
        float forwardStrength = clamp(-dy / Math.max(1f, max), 0f, 1f);
        setJoystickDirections(dy / radius < -0.25f, dy / radius > 0.25f,
                dx / radius < -0.25f, dx / radius > 0.25f, forwardStrength);
    }

    private void setJoystickDirections(boolean up, boolean down, boolean left, boolean right,
                                       float forwardStrength) {
        setJoystickKey(KeyEvent.KEYCODE_W, up);
        setJoystickKey(KeyEvent.KEYCODE_S, down);
        setJoystickKey(KeyEvent.KEYCODE_A, left);
        setJoystickKey(KeyEvent.KEYCODE_D, right);
        joystickForwardStrength = clamp(forwardStrength, 0f, 1f);
        updateSprintState();
    }

    private void updateSprintState() {
        ButtonSpec sprint = findButton(SPRINT);
        boolean defaultSprintButton = sprint == null || resolvedFunction(sprint) == FUNCTION_DEFAULT;
        boolean manualSprint = pressedControls.contains(SPRINT) && defaultSprintButton;
        boolean toggledSprint = toggleActiveControls.contains(SPRINT)
                && resolvedFunction(sprint) == FUNCTION_DEFAULT_TOGGLE;
        boolean fullForwardSprint = autoSprint
                && joystickKeys.contains(KeyEvent.KEYCODE_W)
                && joystickForwardStrength >= sprintThreshold;
        setKeyHeld(KeyEvent.KEYCODE_CTRL_LEFT,
                manualSprint || toggledSprint || fullForwardSprint);
    }

    private void setJoystickKey(int keyCode, boolean held) {
        boolean had = joystickKeys.contains(keyCode);
        if (had == held) return;
        if (held) joystickKeys.add(keyCode); else joystickKeys.remove(keyCode);
        setKeyHeld(keyCode, held);
    }

    private int resolvedFunction(ButtonSpec spec) {
        if (spec == null) return FUNCTION_DEFAULT;
        if (spec.custom && spec.function == FUNCTION_DEFAULT) return FUNCTION_KEY;
        return spec.function;
    }

    private boolean hasConfiguredFunction(ButtonSpec spec) {
        return spec != null && (spec.custom || resolvedFunction(spec) != FUNCTION_DEFAULT);
    }

    private void applyConfiguredFunction(ButtonSpec spec, boolean pressed) {
        int function = resolvedFunction(spec);
        switch (function) {
            case FUNCTION_KEY:
                if (spec.keyCode != KeyEvent.KEYCODE_UNKNOWN) setKeyHeld(spec.keyCode, pressed);
                break;
            case FUNCTION_KEY_TOGGLE:
                if (pressed && spec.keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    boolean next = !toggleActiveControls.contains(spec.id);
                    if (next) toggleActiveControls.add(spec.id); else toggleActiveControls.remove(spec.id);
                    setKeyHeld(spec.keyCode, next);
                }
                break;
            case FUNCTION_DEFAULT_TOGGLE:
                if (pressed) {
                    boolean next = !toggleActiveControls.contains(spec.id);
                    if (next) toggleActiveControls.add(spec.id); else toggleActiveControls.remove(spec.id);
                    applyDefaultActionHeld(spec.id, spec.keyCode, next);
                }
                break;
            case FUNCTION_MOUSE_LEFT:
                setMouseButton(MotionEvent.BUTTON_PRIMARY, pressed, virtualMouseX, virtualMouseY);
                break;
            case FUNCTION_MOUSE_RIGHT:
                setMouseButton(MotionEvent.BUTTON_SECONDARY, pressed, virtualMouseX, virtualMouseY);
                break;
            case FUNCTION_SCROLL_UP:
                if (pressed) sendMouseScroll(1f);
                break;
            case FUNCTION_SCROLL_DOWN:
                if (pressed) sendMouseScroll(-1f);
                break;
            default:
                break;
        }
    }

    private boolean supportsDefaultToggle(int control) {
        return control < CUSTOM_BUTTON_START
                && control != JOYSTICK && control != HOTBAR_PREV && control != HOTBAR_NEXT;
    }

    /** Applies a built-in control action with an explicit pressed/released state. */
    private void applyDefaultActionHeld(int control, int keyCode, boolean held) {
        switch (control) {
            case JUMP: setKeyHeld(KeyEvent.KEYCODE_SPACE, held); break;
            case SNEAK: setKeyHeld(KeyEvent.KEYCODE_SHIFT_LEFT, held); break;
            case SPRINT: updateSprintState(); break;
            case INVENTORY: setKeyHeld(KeyEvent.KEYCODE_E, held); break;
            case ATTACK: case MOUSE_LEFT:
                setMouseButton(MotionEvent.BUTTON_PRIMARY, held, virtualMouseX, virtualMouseY); break;
            case USE: case MOUSE_RIGHT:
                setMouseButton(MotionEvent.BUTTON_SECONDARY, held, virtualMouseX, virtualMouseY); break;
            case ESCAPE: setKeyHeld(KeyEvent.KEYCODE_ESCAPE, held); break;
            case DROP: setKeyHeld(KeyEvent.KEYCODE_Q, held); break;
            case SWAP: setKeyHeld(KeyEvent.KEYCODE_F, held); break;
            case PICK: setMouseButton(MotionEvent.BUTTON_TERTIARY, held, virtualMouseX, virtualMouseY); break;
            case PERSPECTIVE: setKeyHeld(KeyEvent.KEYCODE_F5, held); break;
            case CHAT: setKeyHeld(KeyEvent.KEYCODE_T, held); break;
            case TAB: setKeyHeld(KeyEvent.KEYCODE_TAB, held); break;
            case ENTER: setKeyHeld(KeyEvent.KEYCODE_ENTER, held); break;
            default:
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) setKeyHeld(keyCode, held);
                break;
        }
    }

    private void activateControl(int control, float x, float y) {
        int activePointers = controlPointerCounts.getOrDefault(control, 0);
        controlPointerCounts.put(control, activePointers + 1);
        pressedControls.add(control);
        if (activePointers > 0) {
            invalidate();
            return;
        }
        ButtonSpec configured = findButton(control);
        if (hasConfiguredFunction(configured)) {
            applyConfiguredFunction(configured, true);
            invalidate();
            return;
        }
        switch (control) {
            case JUMP: setKeyHeld(KeyEvent.KEYCODE_SPACE, true); break;
            case SNEAK: setKeyHeld(KeyEvent.KEYCODE_SHIFT_LEFT, true); break;
            case SPRINT: updateSprintState(); break;
            case INVENTORY: setKeyHeld(KeyEvent.KEYCODE_E, true); break;
            case ATTACK: setMouseButton(MotionEvent.BUTTON_PRIMARY, true, virtualMouseX, virtualMouseY); break;
            case USE: setMouseButton(MotionEvent.BUTTON_SECONDARY, true, virtualMouseX, virtualMouseY); break;
            case ESCAPE: setKeyHeld(KeyEvent.KEYCODE_ESCAPE, true); break;
            case DROP: setKeyHeld(KeyEvent.KEYCODE_Q, true); break;
            case SWAP: setKeyHeld(KeyEvent.KEYCODE_F, true); break;
            case PICK: setMouseButton(MotionEvent.BUTTON_TERTIARY, true, virtualMouseX, virtualMouseY); break;
            case MOUSE_LEFT:
                sendMouseMotion(virtualMouseX, virtualMouseY);
                setMouseButton(MotionEvent.BUTTON_PRIMARY, true, virtualMouseX, virtualMouseY);
                break;
            case MOUSE_RIGHT: setMouseButton(MotionEvent.BUTTON_SECONDARY, true, virtualMouseX, virtualMouseY); break;
            case PERSPECTIVE: setKeyHeld(KeyEvent.KEYCODE_F5, true); break;
            case CHAT: setKeyHeld(KeyEvent.KEYCODE_T, true); break;
            case TAB: setKeyHeld(KeyEvent.KEYCODE_TAB, true); break;
            case ENTER: setKeyHeld(KeyEvent.KEYCODE_ENTER, true); break;
            case HOTBAR_PREV: sendMouseScroll(1f); break;
            case HOTBAR_NEXT: sendMouseScroll(-1f); break;
            default:
                ButtonSpec custom = findButton(control);
                if (custom != null && custom.custom) setKeyHeld(custom.keyCode, true);
                break;
        }
        invalidate();
    }

    private void deactivateControl(int control, float x, float y) {
        int activePointers = controlPointerCounts.getOrDefault(control, 0);
        if (activePointers > 1) {
            controlPointerCounts.put(control, activePointers - 1);
            return;
        }
        controlPointerCounts.remove(control);
        pressedControls.remove(control);
        ButtonSpec configured = findButton(control);
        if (hasConfiguredFunction(configured)) {
            applyConfiguredFunction(configured, false);
            invalidate();
            return;
        }
        switch (control) {
            case JUMP: setKeyHeld(KeyEvent.KEYCODE_SPACE, false); break;
            case SNEAK: setKeyHeld(KeyEvent.KEYCODE_SHIFT_LEFT, false); break;
            case SPRINT: updateSprintState(); break;
            case INVENTORY: setKeyHeld(KeyEvent.KEYCODE_E, false); break;
            case ATTACK: setMouseButton(MotionEvent.BUTTON_PRIMARY, false, virtualMouseX, virtualMouseY); break;
            case USE: setMouseButton(MotionEvent.BUTTON_SECONDARY, false, virtualMouseX, virtualMouseY); break;
            case ESCAPE: setKeyHeld(KeyEvent.KEYCODE_ESCAPE, false); break;
            case DROP: setKeyHeld(KeyEvent.KEYCODE_Q, false); break;
            case SWAP: setKeyHeld(KeyEvent.KEYCODE_F, false); break;
            case PICK: setMouseButton(MotionEvent.BUTTON_TERTIARY, false, virtualMouseX, virtualMouseY); break;
            case MOUSE_LEFT: setMouseButton(MotionEvent.BUTTON_PRIMARY, false, virtualMouseX, virtualMouseY); break;
            case MOUSE_RIGHT: setMouseButton(MotionEvent.BUTTON_SECONDARY, false, virtualMouseX, virtualMouseY); break;
            case PERSPECTIVE: setKeyHeld(KeyEvent.KEYCODE_F5, false); break;
            case CHAT: setKeyHeld(KeyEvent.KEYCODE_T, false); break;
            case TAB: setKeyHeld(KeyEvent.KEYCODE_TAB, false); break;
            case ENTER: setKeyHeld(KeyEvent.KEYCODE_ENTER, false); break;
            case HOTBAR_PREV:
            case HOTBAR_NEXT:
                break;
            default:
                ButtonSpec custom = findButton(control);
                if (custom != null && custom.custom) setKeyHeld(custom.keyCode, false);
                break;
        }
        invalidate();
    }

    private void setKeyHeld(int keyCode, boolean held) {
        boolean previous = heldKeys.contains(keyCode);
        if (previous == held) return;
        if (held) heldKeys.add(keyCode); else heldKeys.remove(keyCode);
        if (gameActivity != null) gameActivity.injectKeyCode(keyCode, held);
    }

    private void setMouseButton(int button, boolean held, float x, float y) {
        int previous = mouseButtons;
        if (held) mouseButtons |= button; else mouseButtons &= ~button;
        if (previous == mouseButtons) return;
        if (gameActivity != null) gameActivity.injectMouseButton(button, held, x, y);
    }

    private void sendMouseMotion(float x, float y) {
        if (gameActivity != null) gameActivity.injectCursorMove(x, y);
    }

    private void sendMouseScroll(float amount) {
        if (gameActivity != null) gameActivity.injectMouseScroll(amount, virtualMouseX, virtualMouseY);
    }

    /** Creates a mouse event with its button state set at construction time (MotionEvent has no public setter). */
    private MotionEvent obtainMouseEvent(long time, int action, float x, float y) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = x;
        coordinates.y = y;
        coordinates.pressure = 1f;
        coordinates.size = 1f;
        return MotionEvent.obtain(time, time, action, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coordinates},
                0, mouseButtons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    private void sendMouseClick(float x, float y, int button) {
        sendMouseClick(x, y, button, !gameplayMode);
    }

    private void sendMouseClick(float x, float y, int button, boolean moveCursor) {
        if (moveCursor) sendMouseMotion(x, y);
        setMouseButton(button, true, x, y);
        setMouseButton(button, false, x, y);
    }

    private void pulseKey(int keyCode) {
        setKeyHeld(keyCode, true);
        setKeyHeld(keyCode, false);
    }

    void releaseAllInputs() {
        releaseHeldControls();
        snapGuideX = Float.NaN;
        snapGuideY = Float.NaN;
        pointers.clear();
        joystickPointer = -1;
        invalidate();
    }

    private void releaseHeldControls() {
        for (Integer key : new HashSet<>(heldKeys)) setKeyHeld(key, false);
        joystickKeys.clear();
        toggleActiveControls.clear();
        pressedControls.clear();
        controlPointerCounts.clear();
        // Always send button-up events too, otherwise a long press can remain held after a pause.
        if ((mouseButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
            setMouseButton(MotionEvent.BUTTON_PRIMARY, false, virtualMouseX, virtualMouseY);
        }
        if ((mouseButtons & MotionEvent.BUTTON_SECONDARY) != 0) {
            setMouseButton(MotionEvent.BUTTON_SECONDARY, false, virtualMouseX, virtualMouseY);
        }
        if ((mouseButtons & MotionEvent.BUTTON_TERTIARY) != 0) {
            setMouseButton(MotionEvent.BUTTON_TERTIARY, false, virtualMouseX, virtualMouseY);
        }
        joystickPointer = -1;
    }

    private float joystickHomeX() {
        ButtonLayout layout = buttonLayouts.get(JOYSTICK);
        if (layout != null && layout.x >= 0f) return layout.x * getWidth();
        return leftHanded ? getWidth() - dp(94) * scale : dp(94) * scale;
    }

    private float joystickHomeY() {
        ButtonLayout layout = buttonLayouts.get(JOYSTICK);
        if (layout != null && layout.y >= 0f) return layout.y * getHeight();
        return getHeight() - dp(99) * scale;
    }

    private float mirrorControlX(float x) {
        return leftHanded ? getWidth() - x : x;
    }

    private float dp(float value) { return value * density; }
    private int dpInt(float value) { return Math.round(value * density); }
    private static float distanceSquared(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2; return dx * dx + dy * dy;
    }
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override protected void onDetachedFromWindow() {
        modeHandler.removeCallbacks(modePoll);
        releaseAllInputs();
        if (activeButtonMenu != null) {
            try { if (activeButtonMenu.isShowing()) activeButtonMenu.dismiss(); }
            catch (IllegalArgumentException ignored) { }
            activeButtonMenu = null;
        }
        super.onDetachedFromWindow();
    }
}
