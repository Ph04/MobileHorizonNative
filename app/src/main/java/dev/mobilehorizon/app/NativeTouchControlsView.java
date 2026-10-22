package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    static final String KEY_SNAP_GAP = "snap_gap_dp";

    private static final int FUNCTION_DEFAULT = 0;
    private static final int FUNCTION_KEY = 1;
    private static final int FUNCTION_MOUSE_LEFT = 2;
    private static final int FUNCTION_MOUSE_RIGHT = 3;
    private static final int FUNCTION_SCROLL_UP = 4;
    private static final int FUNCTION_SCROLL_DOWN = 5;

    private static final int NONE = 0, JOYSTICK = 1, TOGGLE = 2, JUMP = 3, SNEAK = 4,
            SPRINT = 5, INVENTORY = 6, ATTACK = 7, USE = 8, ESCAPE = 9,
            DROP = 10, SWAP = 11, PICK = 12, PERSPECTIVE = 13, CHAT = 14,
            HOTBAR_PREV = 15, HOTBAR_NEXT = 16, TAB = 17, ENTER = 18, IGNORED = 19,
            MOUSE_LEFT = 20, MOUSE_RIGHT = 21, KEYBOARD = 22, EDITOR = 23,
            ADD_BUTTON = 24, DONE_EDITING = 25, CUSTOM_BUTTON_START = 1000;
    private final Activity activity;
    private final NativeGameActivity gameActivity;
    private final boolean previewOnly;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snapMeasurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, PointerTrack> pointers = new HashMap<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Set<Integer> pressedControls = new HashSet<>();
    private final Set<Integer> joystickKeys = new HashSet<>();
    private final Map<Integer, Integer> controlPointerCounts = new HashMap<>();
    private final Map<Integer, ButtonLayout> buttonLayouts = new HashMap<>();
    private final List<CustomButton> customButtons = new ArrayList<>();
    private final Handler modeHandler = new Handler(Looper.getMainLooper());
    private boolean editingControls;
    private boolean gameplayMode;
    private float snapGapDp = 12f;
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
            modeHandler.postDelayed(this, 220);
        }
    };

    private final float density;
    private float scale;
    private float opacity;
    private float sensitivity;
    private boolean leftHanded;
    private boolean controlsVisible = true;
    private int joystickPointer = -1;
    private float joystickX, joystickY, virtualMouseX, virtualMouseY;
    private int mouseButtons;
    private boolean cursorInitialized;

    private static final class PointerTrack {
        final float startX, startY;
        float lastX, lastY;
        final int control;
        boolean moved;
        boolean editorDrag;
        PointerTrack(float x, float y, int control) {
            startX = lastX = x; startY = lastY = y; this.control = control;
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
        snapGapDp = clamp(prefs.getFloat(KEY_SNAP_GAP, 12f), 0f, 32f);
        loadButtonLayouts(prefs.getString(KEY_LAYOUT, ""));
        loadCustomButtons(prefs.getString(KEY_CUSTOM_BUTTONS, ""));
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
            for (ButtonSpec spec : buttonSpecs()) drawButton(canvas, spec);
        }
        if (!previewOnly) drawToolbar(canvas);
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

    /** Draw an on-screen pointer so menus can be targeted without a hardware mouse. */
    private void drawMouseCursor(Canvas canvas) {
        if (previewOnly || getWidth() <= 0 || getHeight() <= 0 || (gameplayMode && !editingControls)) return;
        float x = clamp(virtualMouseX, 0, getWidth() - 1f);
        float y = clamp(virtualMouseY, 0, getHeight() - 1f);
        android.graphics.Path cursor = new android.graphics.Path();
        cursor.moveTo(x, y);
        cursor.lineTo(x + dp(1), y + dp(22));
        cursor.lineTo(x + dp(7), y + dp(16));
        cursor.lineTo(x + dp(13), y + dp(27));
        cursor.lineTo(x + dp(18), y + dp(24));
        cursor.lineTo(x + dp(12), y + dp(14));
        cursor.lineTo(x + dp(21), y + dp(14));
        cursor.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(Color.BLACK);
        canvas.drawPath(cursor, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawPath(cursor, paint);
    }

    private void drawJoystick(Canvas canvas) {
        float cx = joystickPointer == -1 ? joystickHomeX() : joystickX;
        float cy = joystickPointer == -1 ? joystickHomeY() : joystickY;
        float radius = dp(67) * scale;
        circle(canvas, cx, cy, radius, false, 0.60f);
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
        circle(canvas, knobX, knobY, radius * 0.37f, false, 0.94f);
        circle(canvas, cx, cy, radius, true, 0.85f);
        circle(canvas, knobX, knobY, radius * 0.37f, true, 1f);
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
        boolean pressed = pressedControls.contains(spec.id);
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
    private static int clampFunction(int function) { return Math.max(FUNCTION_DEFAULT, Math.min(FUNCTION_SCROLL_DOWN, function)); }

    private float toolbarX(float offset) { return mirrorControlX(dp(offset)); }
    private float toolbarY() { return dp(31); }

    private void drawToolbar(Canvas canvas) {
        if (controlsVisible) {
            drawToolbarButton(canvas, KEYBOARD, "⌨", toolbarX(34), toolbarY());
            drawToolbarButton(canvas, EDITOR, editingControls ? "✎" : "⚙", toolbarX(78), toolbarY());
        }
        drawToolbarButton(canvas, TOGGLE, controlsVisible ? "×" : "+", toolbarX(122), toolbarY());
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

    private void addButtonSpec(List<ButtonSpec> specs, int id, String label, float x, float y, float radius) {
        ButtonLayout layout = buttonLayouts.get(id);
        ButtonSpec spec = new ButtonSpec(id, label, x, y,
                radius * (layout == null ? 1f : layout.scale));
        if (layout != null) {
            if (layout.x >= 0f) spec.x = layout.x * getWidth();
            if (layout.y >= 0f) spec.y = layout.y * getHeight();
            spec.opacity = layout.opacity;
            spec.shape = layout.shape;
            spec.function = layout.function;
            spec.keyCode = layout.keyCode;
        }
        specs.add(spec);
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
                items.put(item);
            }
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LAYOUT, items.toString()).apply();
        } catch (Exception error) {
            android.util.Log.w("MobileHorizon", "Não foi possível salvar o layout dos controles.", error);
        }
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

            float[][] candidates = {
                    {leftX, other.y, other.x, other.y},
                    {rightX, other.y, other.x, other.y},
                    {other.x, aboveY, other.x, other.y},
                    {other.x, belowY, other.x, other.y}
            };
            for (float[] candidate : candidates) {
                float dx = Math.abs(x - candidate[0]);
                float dy = Math.abs(y - candidate[1]);
                if (dx > threshold || dy > threshold) continue;
                float score = dx * dx + dy * dy;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = candidate[0];
                    bestY = candidate[1];
                    if (Math.abs(candidate[0] - other.x) > 0.5f) {
                        snapGuideY = other.y;
                        snapGuideX = Float.NaN;
                    } else {
                        snapGuideX = other.x;
                        snapGuideY = Float.NaN;
                    }
                }
            }
        }

        float nx = clamp(bestX / width, 0.035f, 0.965f);
        float ny = clamp(bestY / height, 0.055f, 0.945f);
        CustomButton custom = findCustomButton(id);
        if (custom != null) {
            custom.x = nx;
            custom.y = ny;
        } else {
            ButtonLayout layout = buttonLayouts.computeIfAbsent(id, ignored -> new ButtonLayout());
            layout.x = nx;
            layout.y = ny;
        }
        invalidate();
    }

    private void showKeyboardDialog() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("Texto para o campo selecionado ou mensagem");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) dp(20), (int) dp(8), (int) dp(20), (int) dp(4));
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Teclado do Minecraft")
                .setView(panel)
                .setNegativeButton("Cancelar", (d, which) -> { })
                .setPositiveButton("Aplicar / enviar", (d, which) -> {
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
        label.setHint("Texto do botão (ex.: MAP)");
        label.setText("G");
        EditText key = new EditText(activity);
        key.setSingleLine(true);
        key.setHint("Tecla (ex.: G, SPACE, F6)");
        key.setText("G");
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) dp(20), (int) dp(8), (int) dp(20), (int) dp(4));
        panel.addView(label, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(key, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Adicionar botão")
                .setMessage("O botão mantém a tecla pressionada enquanto você segura.")
                .setView(panel)
                .setNegativeButton("Cancelar", (d, which) -> { })
                .setPositiveButton("Adicionar", null)
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
            customButtons.add(new CustomButton(id, caption, keyCode,
                    clamp(x, 0.10f, 0.90f), clamp(y, 0.15f, 0.85f), 1f));
            saveCustomButtons();
            invalidate();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private TextView miniLabel(String value, int size, boolean bold) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextColor(0xffeaf0ff);
        label.setTextSize(size);
        if (bold) label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return label;
    }

    private void showButtonMiniMenu(int id) {
        ButtonSpec spec = findButton(id);
        if (spec == null || !isAttachedToWindow()) return;
        if (activeButtonMenu != null && activeButtonMenu.isShowing()) activeButtonMenu.dismiss();

        CustomButton custom = findCustomButton(id);
        ButtonLayout saved = buttonLayouts.get(id);
        float currentScale = custom != null ? custom.scale : (saved == null ? 1f : saved.scale);
        float currentOpacity = custom != null ? custom.opacity : (saved == null ? 1f : saved.opacity);
        int currentShape = custom != null ? custom.shape : (saved == null ? 0 : saved.shape);
        int currentFunction = custom != null ? custom.function : (saved == null ? 0 : saved.function);
        int currentKey = custom != null ? custom.keyCode : (saved == null ? KeyEvent.KEYCODE_UNKNOWN : saved.keyCode);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setBackgroundColor(Color.TRANSPARENT);

        TextView title = miniLabel("Botão: " + spec.label, 13, true);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(24)));

        TextView scaleLabel = miniLabel("Tamanho " + Math.round(currentScale * 100) + "%", 11, false);
        SeekBar size = new SeekBar(activity);
        size.setMax(180);
        size.setProgress(Math.round(clamp(currentScale, 0.60f, 2.40f) * 100f) - 60);
        size.setPadding(0, 0, 0, 0);
        panel.addView(scaleLabel, new LinearLayout.LayoutParams(-1, dp(18)));
        panel.addView(size, new LinearLayout.LayoutParams(-1, dp(28)));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                scaleLabel.setText("Tamanho " + (progress + 60) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        TextView shapeLabel = miniLabel("Formato", 11, false);
        Spinner shape = new Spinner(activity);
        ArrayAdapter<String> shapeAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item,
                new String[]{"Retângulo arredondado", "Círculo / oval", "Quadrado"});
        shapeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        shape.setAdapter(shapeAdapter);
        shape.setSelection(clampShape(currentShape));
        panel.addView(shapeLabel, new LinearLayout.LayoutParams(-1, dp(17)));
        panel.addView(shape, new LinearLayout.LayoutParams(-1, dp(34)));

        TextView opacityLabel = miniLabel("Opacidade " + Math.round(currentOpacity * 100) + "%", 11, false);
        SeekBar alpha = new SeekBar(activity);
        alpha.setMax(80);
        alpha.setProgress(Math.round(clamp(currentOpacity, 0.20f, 1f) * 100f) - 20);
        panel.addView(opacityLabel, new LinearLayout.LayoutParams(-1, dp(18)));
        panel.addView(alpha, new LinearLayout.LayoutParams(-1, dp(28)));
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                opacityLabel.setText("Opacidade " + (progress + 20) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        TextView functionLabel = miniLabel("Função do botão", 11, false);
        Spinner function = new Spinner(activity);
        ArrayAdapter<String> functionAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item,
                new String[]{"Padrão", "Tecla personalizada", "Clique esquerdo", "Clique direito", "Scroll para cima", "Scroll para baixo"});
        functionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        function.setAdapter(functionAdapter);
        function.setSelection(clampFunction(currentFunction));
        panel.addView(functionLabel, new LinearLayout.LayoutParams(-1, dp(17)));
        panel.addView(function, new LinearLayout.LayoutParams(-1, dp(34)));

        EditText key = new EditText(activity);
        key.setSingleLine(true);
        key.setTextSize(12);
        key.setTextColor(Color.WHITE);
        key.setHintTextColor(0xffaeb9cb);
        key.setHint("Tecla, ex.: G, SPACE, F6");
        key.setPadding(dp(7), 0, dp(7), 0);
        key.setText(currentKey == KeyEvent.KEYCODE_UNKNOWN ? "" : KeyEvent.keyCodeToString(currentKey).replace("KEYCODE_", ""));
        key.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff7e91ad));
        panel.addView(key, new LinearLayout.LayoutParams(-1, dp(36)));

        EditText label = null;
        if (custom != null) {
            label = new EditText(activity);
            label.setSingleLine(true);
            label.setTextSize(12);
            label.setTextColor(Color.WHITE);
            label.setHintTextColor(0xffaeb9cb);
            label.setHint("Texto do botão");
            label.setPadding(dp(7), 0, dp(7), 0);
            label.setText(custom.label);
            label.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff7e91ad));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, dp(36));
            labelParams.topMargin = dp(3);
            panel.addView(label, labelParams);
        }
        final EditText customLabel = label;

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button reset = new Button(activity);
        reset.setText(custom != null ? "Excluir" : "Padrão");
        reset.setTextSize(10);
        Button cancel = new Button(activity);
        cancel.setText("Fechar");
        cancel.setTextSize(10);
        Button save = new Button(activity);
        save.setText("Salvar");
        save.setTextSize(10);
        actions.addView(reset, new LinearLayout.LayoutParams(0, dp(38), 1f));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(38), 1f));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(38), 1f));
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(40)));

        ScrollView scroller = new ScrollView(activity);
        scroller.setFillViewport(false);
        scroller.setClipToPadding(false);
        scroller.addView(panel, new ScrollView.LayoutParams(-1, -2));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF21C2638);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0xff7188a7);

        int[] screen = new int[2];
        getLocationOnScreen(screen);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int menuWidth = Math.min(dp(224), Math.max(dp(176), screenWidth - dp(16)));
        int menuHeight = Math.min(dp(290), Math.max(dp(180), screenHeight - dp(16)));
        scroller.measure(MeasureSpec.makeMeasureSpec(menuWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(menuHeight, MeasureSpec.AT_MOST));
        PopupWindow popup = new PopupWindow(scroller, menuWidth, menuHeight, true);
        popup.setBackgroundDrawable(background);
        popup.setOutsideTouchable(true);
        popup.setClippingEnabled(true);
        popup.setElevation(dp(8));
        activeButtonMenu = popup;
        popup.setOnDismissListener(() -> {
            if (activeButtonMenu == popup) activeButtonMenu = null;
        });

        int buttonX = screen[0] + Math.round(spec.x);
        int buttonY = screen[1] + Math.round(spec.y);
        int menuX = buttonX > screenWidth / 2 ? buttonX - menuWidth - dp(12) : buttonX + dp(12);
        int menuY = buttonY > screenHeight / 2 ? buttonY - menuHeight - dp(8) : buttonY + dp(12);
        menuX = Math.max(dp(8), Math.min(screenWidth - menuWidth - dp(8), menuX));
        menuY = Math.max(dp(8), Math.min(screenHeight - menuHeight - dp(8), menuY));
        popup.showAtLocation(this, Gravity.TOP | Gravity.LEFT, menuX, menuY);

        reset.setOnClickListener(v -> {
            if (custom != null) {
                customButtons.remove(custom);
                saveCustomButtons();
            } else {
                buttonLayouts.remove(id);
                saveButtonLayouts();
            }
            invalidate();
            popup.dismiss();
        });
        cancel.setOnClickListener(v -> popup.dismiss());
        save.setOnClickListener(v -> {
            int selectedFunction = function.getSelectedItemPosition();
            int selectedKey = parseKeyCode(key.getText().toString());
            if (selectedFunction == FUNCTION_KEY && selectedKey == KeyEvent.KEYCODE_UNKNOWN) {
                Toast.makeText(activity, "Informe uma tecla válida para essa função.", Toast.LENGTH_SHORT).show();
                return;
            }
            float newScale = (size.getProgress() + 60) / 100f;
            float newOpacity = (alpha.getProgress() + 20) / 100f;
            int newShape = clampShape(shape.getSelectedItemPosition());
            if (custom != null) {
                String caption = customLabel == null ? custom.label : customLabel.getText().toString().trim();
                if (caption.isEmpty() || caption.length() > 7) {
                    Toast.makeText(activity, "Use um texto de até 7 caracteres.", Toast.LENGTH_SHORT).show();
                    return;
                }
                custom.label = caption;
                custom.keyCode = selectedKey == KeyEvent.KEYCODE_UNKNOWN ? custom.keyCode : selectedKey;
                custom.scale = newScale;
                custom.opacity = newOpacity;
                custom.shape = newShape;
                custom.function = selectedFunction;
                saveCustomButtons();
            } else {
                ButtonLayout updated = buttonLayouts.computeIfAbsent(id, ignored -> new ButtonLayout());
                updated.scale = newScale;
                updated.opacity = newOpacity;
                updated.shape = newShape;
                updated.function = selectedFunction;
                updated.keyCode = selectedFunction == FUNCTION_KEY ? selectedKey : KeyEvent.KEYCODE_UNKNOWN;
                saveButtonLayouts();
            }
            invalidate();
            popup.dismiss();
        });
    }

    void showGlobalSettingsDialog() {
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(4), dp(14), dp(4));

        android.widget.CheckBox enabled = new android.widget.CheckBox(activity);
        enabled.setText("Ativar controles na tela do jogo");
        enabled.setTextColor(0xffeaf0ff);
        enabled.setChecked(prefs.getBoolean(KEY_ENABLED, true));
        panel.addView(enabled, new LinearLayout.LayoutParams(-1, -2));

        android.widget.CheckBox handed = new android.widget.CheckBox(activity);
        handed.setText("Layout canhoto (joystick à direita)");
        handed.setTextColor(0xffeaf0ff);
        handed.setChecked(leftHanded);
        panel.addView(handed, new LinearLayout.LayoutParams(-1, -2));

        TextView sizeLabel = textSetting("Tamanho geral " + Math.round(scale * 100) + "%");
        SeekBar size = new SeekBar(activity);
        size.setMax(60);
        size.setProgress(Math.round((scale - 0.70f) * 100f));
        panel.addView(sizeLabel, new LinearLayout.LayoutParams(-1, dp(19)));
        panel.addView(size, new LinearLayout.LayoutParams(-1, dp(30)));

        TextView alphaLabel = textSetting("Opacidade geral " + Math.round(opacity * 100) + "%");
        SeekBar alpha = new SeekBar(activity);
        alpha.setMax(65);
        alpha.setProgress(Math.round(opacity * 100f - 35f));
        panel.addView(alphaLabel, new LinearLayout.LayoutParams(-1, dp(19)));
        panel.addView(alpha, new LinearLayout.LayoutParams(-1, dp(30)));

        TextView sensitivityLabel = textSetting("Sensibilidade da câmera " + Math.round(sensitivity * 100) + "%");
        SeekBar sensitivityBar = new SeekBar(activity);
        sensitivityBar.setMax(150);
        sensitivityBar.setProgress(Math.round((sensitivity - 0.50f) * 100f));
        panel.addView(sensitivityLabel, new LinearLayout.LayoutParams(-1, dp(19)));
        panel.addView(sensitivityBar, new LinearLayout.LayoutParams(-1, dp(30)));

        TextView gapLabel = textSetting("Distância de encaixe " + Math.round(snapGapDp) + " dp");
        SeekBar gapBar = new SeekBar(activity);
        gapBar.setMax(32);
        gapBar.setProgress(Math.round(snapGapDp));
        panel.addView(gapLabel, new LinearLayout.LayoutParams(-1, dp(19)));
        panel.addView(gapBar, new LinearLayout.LayoutParams(-1, dp(30)));

        size.setOnSeekBarChangeListener(simpleSeek(progress -> sizeLabel.setText("Tamanho geral " + (70 + progress) + "%")));
        alpha.setOnSeekBarChangeListener(simpleSeek(progress -> alphaLabel.setText("Opacidade geral " + (35 + progress) + "%")));
        sensitivityBar.setOnSeekBarChangeListener(simpleSeek(progress -> sensitivityLabel.setText("Sensibilidade da câmera " + (50 + progress) + "%")));
        gapBar.setOnSeekBarChangeListener(simpleSeek(progress -> gapLabel.setText("Distância de encaixe " + progress + " dp")));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Ajustes dos controles")
                .setView(panel)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            scale = (size.getProgress() + 70) / 100f;
            opacity = (alpha.getProgress() + 35) / 100f;
            sensitivity = (sensitivityBar.getProgress() + 50) / 100f;
            snapGapDp = gapBar.getProgress();
            leftHanded = handed.isChecked();
            prefs.edit()
                    .putBoolean(KEY_ENABLED, enabled.isChecked())
                    .putBoolean(KEY_LEFT_HANDED, leftHanded)
                    .putFloat(KEY_SCALE, scale)
                    .putFloat(KEY_OPACITY, opacity)
                    .putFloat(KEY_SENSITIVITY, sensitivity)
                    .putFloat(KEY_SNAP_GAP, snapGapDp)
                    .apply();
            invalidate();
            Toast.makeText(activity, "Ajustes dos controles salvos.", Toast.LENGTH_SHORT).show();
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
        text.setText(value);
        text.setTextColor(0xffeaf0ff);
        text.setTextSize(12);
        return text;
    }

    private void showButtonEditor(int id) {
        if (previewOnly) {
            showButtonMiniMenu(id);
            return;
        }
        ButtonSpec spec = findButton(id);
        if (spec == null) return;
        CustomButton custom = findCustomButton(id);
        ButtonLayout layout = buttonLayouts.get(id);
        float currentScale = custom != null ? custom.scale : (layout == null ? 1f : layout.scale);
        TextView sizeText = new TextView(activity);
        SeekBar size = new SeekBar(activity);
        size.setMax(180);
        size.setProgress(Math.round(clamp(currentScale, 0.60f, 2.40f) * 100f) - 60);
        sizeText.setText("Escala individual: " + Math.round(currentScale * 100) + "%");
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                sizeText.setText("Escala individual: " + (progress + 60) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) dp(20), (int) dp(10), (int) dp(20), (int) dp(4));
        panel.addView(sizeText, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(size, new LinearLayout.LayoutParams(-1, -2));
        EditText label = null, key = null;
        if (custom != null) {
            label = new EditText(activity);
            label.setSingleLine(true);
            label.setHint("Texto do botão");
            label.setText(custom.label);
            key = new EditText(activity);
            key.setSingleLine(true);
            key.setHint("Tecla (ex.: G, SPACE, F6)");
            key.setText(KeyEvent.keyCodeToString(custom.keyCode).replace("KEYCODE_", ""));
            panel.addView(label, new LinearLayout.LayoutParams(-1, -2));
            panel.addView(key, new LinearLayout.LayoutParams(-1, -2));
        }
        final EditText customLabel = label, customKey = key;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Personalizar " + spec.label)
                .setView(panel)
                .setNegativeButton("Cancelar", (d, which) -> { })
                .setNeutralButton(custom != null ? "Excluir" : "Restaurar", null)
                .setPositiveButton("Salvar", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int progress = size.getProgress();
                float newScale = (progress + 60) / 100f;
                if (custom != null) {
                    String caption = customLabel.getText().toString().trim();
                    int newKey = parseKeyCode(customKey.getText().toString());
                    if (caption.isEmpty() || caption.length() > 7 || newKey == KeyEvent.KEYCODE_UNKNOWN) {
                        Toast.makeText(activity, "Informe um nome curto e uma tecla válida.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    custom.label = caption;
                    custom.keyCode = newKey;
                    custom.scale = newScale;
                    saveCustomButtons();
                } else {
                    ButtonLayout updated = buttonLayouts.computeIfAbsent(id, ignoredId -> new ButtonLayout());
                    updated.scale = newScale;
                    saveButtonLayouts();
                }
                invalidate();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (custom != null) {
                    customButtons.remove(custom);
                    saveCustomButtons();
                } else {
                    buttonLayouts.remove(id);
                    saveButtonLayouts();
                }
                invalidate();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private int parseKeyCode(String value) {
        String key = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (key.startsWith("KEYCODE_")) key = key.substring("KEYCODE_".length());
        if (key.startsWith("KEY_")) key = key.substring("KEY_".length());
        int result = KeyEvent.keyCodeFromString("KEYCODE_" + key);
        return result == KeyEvent.KEYCODE_UNKNOWN ? KeyEvent.KEYCODE_UNKNOWN : result;
    }

    private void circle(Canvas canvas, float x, float y, float radius, boolean stroke, float alpha) {
        paint.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        paint.setStrokeWidth(dp(1.4f));
        int a = Math.round(255 * opacity * alpha);
        paint.setColor(stroke ? Color.argb(a, 228, 236, 255) : Color.argb(a, 18, 26, 44));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
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
        int control = hitControl(x, y);
        if (control == TOGGLE) {
            controlsVisible = !controlsVisible;
            if (!controlsVisible) {
                editingControls = false;
                releaseHeldControls();
            }
        } else if (control == KEYBOARD) {
            showKeyboardDialog();
            control = IGNORED;
        } else if (control == EDITOR) {
            editingControls = !editingControls;
            snapGuideX = Float.NaN;
            snapGuideY = Float.NaN;
            Toast.makeText(activity, editingControls
                    ? "Modo de edição: arraste os botões; as guias azuis ajudam a alinhar."
                    : "Layout de controles salvo.", Toast.LENGTH_SHORT).show();
            if (!editingControls) {
                saveButtonLayouts();
                saveCustomButtons();
            }
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
        } else if (control == JOYSTICK && joystickPointer == -1) {
            joystickPointer = id; joystickX = x; joystickY = y;
        } else if (control == JOYSTICK) {
            // Never let a second finger on the joystick trigger mouse input.
            control = IGNORED;
        }
        PointerTrack track = new PointerTrack(x, y, control);
        track.editorDrag = editingControls && isEditableButton(control);
        pointers.put(id, track);
        if (track.editorDrag) {
            // Wait for a drag before changing the position. A simple tap opens the editor.
        } else if (control == JOYSTICK) updateJoystick(x, y);
        else if (control != NONE && control != TOGGLE && control != IGNORED) activateControl(control, x, y);
        else if (control == NONE) {
            virtualMouseX = x;
            virtualMouseY = y;
            sendMouseMotion(virtualMouseX, virtualMouseY);
        }
        invalidate();
    }

    private void movePointers(MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            PointerTrack track = pointers.get(event.getPointerId(i));
            if (track == null) continue;
            float x = event.getX(i), y = event.getY(i);
            float dx = x - track.lastX, dy = y - track.lastY;
            if (Math.abs(x - track.startX) > dp(7) || Math.abs(y - track.startY) > dp(7)) track.moved = true;
            if (track.editorDrag) {
                if (Math.abs(x - track.startX) > dp(7) || Math.abs(y - track.startY) > dp(7)) track.moved = true;
                if (track.moved) setButtonPosition(track.control, x, y);
            } else if (track.control == JOYSTICK) updateJoystick(x, y);
            else if (track.control == NONE && track.moved
                    && (Math.abs(dx) >= 0.75f || Math.abs(dy) >= 0.75f)) {
                virtualMouseX = clamp(virtualMouseX + dx * sensitivity, 0, Math.max(1, getWidth()));
                virtualMouseY = clamp(virtualMouseY + dy * sensitivity, 0, Math.max(1, getHeight()));
                sendMouseMotion(virtualMouseX, virtualMouseY);
                if (gameActivity != null) gameActivity.injectMouseMotion(dx * sensitivity, dy * sensitivity);
            }
            track.lastX = x; track.lastY = y;
        }
    }

    private void removePointer(MotionEvent event, int index) {
        int id = event.getPointerId(index);
        PointerTrack track = pointers.remove(id);
        if (track == null) return;
        if (track.control == IGNORED) {
            invalidate();
            return;
        }
        if (track.editorDrag) {
            snapGuideX = Float.NaN;
            snapGuideY = Float.NaN;
            if (track.moved) {
                CustomButton custom = findCustomButton(track.control);
                if (custom != null) saveCustomButtons();
                else saveButtonLayouts();
            } else {
                showButtonEditor(track.control);
            }
        } else if (track.control == JOYSTICK) {
            joystickPointer = -1;
            setJoystickDirections(false, false, false, false);
        } else if (track.control == NONE) {
            if (!track.moved) sendMouseClick(virtualMouseX, virtualMouseY, MotionEvent.BUTTON_PRIMARY);
        } else if (track.control != TOGGLE) {
            deactivateControl(track.control, track.lastX, track.lastY);
        }
        invalidate();
    }

    private int hitControl(float x, float y) {
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
        float toggleX = toolbarX(122), toggleY = toolbarY();
        if (distanceSquared(x, y, toggleX, toggleY) <= dp(25) * dp(25)) return TOGGLE;
        if (!controlsVisible) return NONE;
        if (distanceSquared(x, y, toolbarX(34), toolbarY()) <= dp(24) * dp(24)) return KEYBOARD;
        if (distanceSquared(x, y, toolbarX(78), toolbarY()) <= dp(24) * dp(24)) return EDITOR;
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

    private void updateJoystick(float x, float y) {
        PointerTrack track = pointers.get(joystickPointer);
        if (track == null) return;
        float dx = x - joystickX, dy = y - joystickY, radius = dp(67) * scale;
        float max = radius * 0.85f, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > max && len > 0) { dx *= max / len; dy *= max / len; }
        setJoystickDirections(dy / radius < -0.25f, dy / radius > 0.25f,
                dx / radius < -0.25f, dx / radius > 0.25f);
    }

    private void setJoystickDirections(boolean up, boolean down, boolean left, boolean right) {
        setJoystickKey(KeyEvent.KEYCODE_W, up);
        setJoystickKey(KeyEvent.KEYCODE_S, down);
        setJoystickKey(KeyEvent.KEYCODE_A, left);
        setJoystickKey(KeyEvent.KEYCODE_D, right);
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
            case SPRINT: setKeyHeld(KeyEvent.KEYCODE_CTRL_LEFT, true); break;
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
            case SPRINT: setKeyHeld(KeyEvent.KEYCODE_CTRL_LEFT, false); break;
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
        sendMouseMotion(x, y);
        setMouseButton(button, true, x, y);
        setMouseButton(button, false, x, y);
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
        pressedControls.clear();
        controlPointerCounts.clear();
        // Avoid generating accidental taps while the activity pauses or is destroyed.
        mouseButtons = 0;
        joystickPointer = -1;
    }

    private float joystickHomeX() {
        return leftHanded ? getWidth() - dp(94) * scale : dp(94) * scale;
    }

    private float joystickHomeY() {
        return getHeight() - dp(99) * scale;
    }

    private float mirrorControlX(float x) {
        return leftHanded ? getWidth() - x : x;
    }

    private float dp(float value) { return value * density; }
    private static float distanceSquared(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2; return dx * dx + dy * dy;
    }
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override protected void onDetachedFromWindow() {
        modeHandler.removeCallbacks(modePoll);
        releaseAllInputs();
        super.onDetachedFromWindow();
    }
}
