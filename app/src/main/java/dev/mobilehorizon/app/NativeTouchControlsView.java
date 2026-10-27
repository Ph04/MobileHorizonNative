package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
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
import android.widget.FrameLayout;
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
    static final String KEY_AUTO_SPRINT = "auto_sprint_from_joystick";
    private static final String KEY_UNIFORM_SIZE_VERSION = "uniform_button_size_version";
    private static final String KEY_FLOAT_MENU_X = "floating_menu_x";
    private static final String KEY_FLOAT_MENU_Y = "floating_menu_y";
    private static final String KEY_VISUAL_DEFAULTS_VERSION = "visual_defaults_version";
    private static final String KEY_CLASSIC_BUTTONS = "classic_buttons";
    private static final String KEY_AUXILIARY_CONTROL_COLOR = "auxiliary_control_color";
    private static final float SPRINT_TRIGGER_THRESHOLD = 0.84f;
    private static final float JOYSTICK_MAX_REACH = 0.85f;
    private static final PorterDuffColorFilter CLASSIC_PRESSED_FILTER =
            new PorterDuffColorFilter(0xff989898, PorterDuff.Mode.MULTIPLY);
    private static final PorterDuffColorFilter CLASSIC_TEXT_FILTER =
            new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);

    private static final long LONG_PRESS_TIMEOUT_MS = 430L;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 300L;

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
            ADD_BUTTON = 24, DONE_EDITING = 25, FLOATING_MENU = 26,
            HOTBAR_SLOT_START = 100, CUSTOM_BUTTON_START = 1000;
    private final Activity activity;
    private final NativeGameActivity gameActivity;
    private final boolean previewOnly;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint();
    private final Paint classicEditPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, Bitmap> classicBitmapCache = new HashMap<>();
    private Bitmap classicJoystickBase;
    private Bitmap classicJoystickKnob;
    private Bitmap minecraftFontAtlas;
    private final Rect[] minecraftGlyphRects = new Rect[256];
    private final float[] minecraftGlyphAdvances = new float[256];
    private Typeface fallbackMinecraftTypeface;
    private boolean minecraftFontAttempted;
    private final Paint snapMeasurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();
    private final Map<Integer, PointerTrack> pointers = new HashMap<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Set<Integer> pressedControls = new HashSet<>();
    private final Set<Integer> joystickKeys = new HashSet<>();
    private final Set<Integer> latchedControls = new HashSet<>();
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
    private AlertDialog activeGameMenu;
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
            modeHandler.postDelayed(this, 50);
        }
    };

    private final float density;
    private float scale;
    private float opacity;
    private float sensitivity;
    private boolean leftHanded;
    private boolean autoSprintEnabled;
    private boolean classicButtonsEnabled;
    /** Accent shared by utility controls, following the most recently customized button color. */
    private int auxiliaryControlColor = 7;
    private boolean joystickSprintActive;
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
        boolean toggle;
        int color = 7;
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
        boolean visible = true;
        boolean toggle;
        int color = 7;
    }

    private static final class CustomButton {
        final int id;
        String label;
        int keyCode;
        float x, y, scale;
        float opacity = 1f;
        int shape = 0;
        int function = FUNCTION_KEY;
        boolean toggle;
        int color = 7;
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
        opacity = clamp(prefs.getFloat(KEY_OPACITY, 0.72f), 0.35f, 1f);
        if (prefs.getInt(KEY_VISUAL_DEFAULTS_VERSION, 0) < 1) {
            // Migrate only the old default, preserving any intentionally chosen opacity.
            if (Math.abs(opacity - 0.82f) < 0.005f) opacity = 0.72f;
            prefs.edit().putFloat(KEY_OPACITY, opacity)
                    .putInt(KEY_VISUAL_DEFAULTS_VERSION, 1).apply();
        }
        sensitivity = clamp(prefs.getFloat(KEY_SENSITIVITY, 1f), 0.50f, 2f);
        leftHanded = prefs.getBoolean(KEY_LEFT_HANDED, false);
        autoSprintEnabled = prefs.getBoolean(KEY_AUTO_SPRINT, false);
        classicButtonsEnabled = prefs.getBoolean(KEY_CLASSIC_BUTTONS, false);
        bitmapPaint.setFilterBitmap(false);
        bitmapPaint.setDither(false);
        snapGapDp = clamp(prefs.getFloat(KEY_SNAP_GAP, 12f), 4f, 32f);
        loadButtonLayouts(prefs.getString(KEY_LAYOUT, ""));
        loadCustomButtons(prefs.getString(KEY_CUSTOM_BUTTONS, ""));
        ButtonLayout chatButtonLayout = buttonLayouts.get(CHAT);
        auxiliaryControlColor = clampButtonColor(prefs.getInt(
                KEY_AUXILIARY_CONTROL_COLOR, chatButtonLayout == null ? 7 : chatButtonLayout.color));
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
            if (findButton(JOYSTICK) != null) drawJoystick(canvas);
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
        ButtonSpec spec = findButton(JOYSTICK);
        if (spec == null) return;
        float cx = joystickPointer == -1 ? joystickHomeX() : joystickX;
        float cy = joystickPointer == -1 ? joystickHomeY() : joystickY;
        float radius = spec.radius;
        float effectiveOpacity = opacity * clamp(spec.opacity, 0.20f, 1f);
        float knobX = cx, knobY = cy;
        if (joystickPointer != -1) {
            PointerTrack track = pointers.get(joystickPointer);
            if (track != null) {
                float dx = track.lastX - cx, dy = track.lastY - cy;
                // In classic mode the extracted square knob occupies a little over half
                // the pad, so limit its travel to keep its edges inside the bitmap base.
                float max = radius * (classicButtonsEnabled ? 0.46f : 0.66f);
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > max && len > 0) { dx *= max / len; dy *= max / len; }
                knobX += dx;
                knobY += dy;
            }
        }

        if (classicButtonsEnabled && drawClassicJoystick(canvas, cx, cy, radius,
                knobX, knobY, effectiveOpacity, spec.color, joystickPointer != -1)) {
            return;
        }

        drawJoystickBase(canvas, cx, cy, radius, spec.shape, effectiveOpacity, spec.color);
        if (autoSprintEnabled) {
            drawJoystickSprintZone(canvas, cx, cy, radius, effectiveOpacity, spec.color, spec.shape);
        }
        circle(canvas, knobX, knobY, radius * 0.37f, false, 0.94f, effectiveOpacity, spec.color);
        circle(canvas, knobX, knobY, radius * 0.37f, true, 1f, effectiveOpacity, spec.color);
    }

    private void drawJoystickBase(Canvas canvas, float cx, float cy, float radius, int shape,
                                  float effectiveOpacity, int color) {
        RectF bounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(buttonPaletteColor(color, false), Math.round(255 * effectiveOpacity * 0.60f)));
        drawJoystickShape(canvas, bounds, radius, shape);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(withAlpha(buttonBorderColor(color), Math.round(255 * effectiveOpacity * 0.90f)));
        drawJoystickShape(canvas, bounds, radius, shape);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawJoystickSprintZone(Canvas canvas, float cx, float cy, float radius,
                                        float effectiveOpacity, int color, int shape) {
        // The activation region is a narrow top strip. Shape-aware hit-testing below
        // uses the same threshold/side boundary, so color and input never disagree.
        float threshold = radius * SPRINT_TRIGGER_THRESHOLD;
        Path sprintZone = new Path();
        if (classicButtonsEnabled || shape == 2) {
            sprintZone.addRect(cx - radius, cy - radius, cx + radius, cy - threshold,
                    Path.Direction.CW);
        } else {
            float halfWidth = (float) Math.sqrt(radius * radius - threshold * threshold);
            RectF circleBounds = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            float startAngle = (float) Math.toDegrees(Math.atan2(-threshold, -halfWidth));
            float endAngle = (float) Math.toDegrees(Math.atan2(-threshold, halfWidth));
            if (startAngle < 0) startAngle += 360f;
            if (endAngle < 0) endAngle += 360f;
            sprintZone.moveTo(cx - halfWidth, cy - threshold);
            sprintZone.arcTo(circleBounds, startAngle, endAngle - startAngle);
            sprintZone.close();
        }

        int selectedColor = buttonPaletteColor(color, false);
        int darkerColor = blendColor(selectedColor, Color.BLACK, 0.38f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(darkerColor,
                Math.round(255 * clamp(effectiveOpacity * 0.90f, 0f, 1f))));
        canvas.drawPath(sprintZone, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawJoystickShape(Canvas canvas, RectF bounds, float radius, int shape) {
        if (shape == 1) canvas.drawOval(bounds, paint);
        else if (shape == 2) canvas.drawRect(bounds, paint);
        else canvas.drawRoundRect(bounds, radius * 0.88f, radius * 0.88f, paint);
    }

    private boolean drawClassicJoystick(Canvas canvas, float cx, float cy, float radius,
                                        float knobX, float knobY, float alpha, int color,
                                        boolean pressed) {
        ensureClassicJoystickBitmaps();
        if (classicJoystickBase == null || classicJoystickKnob == null) return false;
        RectF base = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        bitmapPaint.setAlpha(Math.round(255 * clamp(alpha, 0f, 1f)));
        bitmapPaint.setColorFilter(classicButtonFilter(color, false));
        canvas.drawBitmap(classicJoystickBase, null, base, bitmapPaint);
        bitmapPaint.setColorFilter(null);

        // Preserve the PE sprite while exposing the sprint activation strip.
        // The strip uses a darker shade of the existing gray button, with no
        // separate RUN label or green accent; the movable knob is drawn above it.
        if (autoSprintEnabled) drawClassicSprintZone(canvas, cx, cy, radius, alpha);

        float knobSize = radius * 1.0625f; // 68 of 128 source pixels, scaled with the base.
        RectF knob = new RectF(knobX - knobSize / 2f, knobY - knobSize / 2f,
                knobX + knobSize / 2f, knobY + knobSize / 2f);
        canvas.save();
        if (pressed) canvas.translate(0, dp(1.2f));
        bitmapPaint.setColorFilter(classicButtonFilter(color, pressed));
        canvas.drawBitmap(classicJoystickKnob, null, knob, bitmapPaint);
        canvas.restore();
        bitmapPaint.setColorFilter(null);
        return true;
    }

    private void drawClassicSprintZone(Canvas canvas, float cx, float cy,
                                         float radius, float alpha) {
        float thresholdY = cy - radius * SPRINT_TRIGGER_THRESHOLD;
        RectF base = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        Path rounded = new Path();
        rounded.addRoundRect(base, radius * 0.10f, radius * 0.10f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(rounded);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.BLACK,
                Math.round(255f * clamp(alpha * 0.24f, 0f, 1f))));
        canvas.drawRect(base.left, base.top, base.right, thresholdY, paint);
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
    }

    private void ensureClassicJoystickBitmaps() {
        if (classicJoystickBase != null && classicJoystickKnob != null) return;
        Bitmap original = classicBitmap("joystick.png");
        if (original == null || original.getWidth() < 100 || original.getHeight() < 100) return;
        int left = Math.round(original.getWidth() * 30f / 128f);
        int top = Math.round(original.getHeight() * 30f / 128f);
        int right = Math.round(original.getWidth() * 98f / 128f);
        int bottom = Math.round(original.getHeight() * 98f / 128f);
        try {
            classicJoystickKnob = Bitmap.createBitmap(original, left, top,
                    right - left, bottom - top);
            classicJoystickBase = original.copy(Bitmap.Config.ARGB_8888, true);
            if (classicJoystickBase == null) {
                classicJoystickKnob.recycle();
                classicJoystickKnob = null;
                return;
            }
            Canvas baseCanvas = new Canvas(classicJoystickBase);
            Paint cleanPaint = new Paint();
            cleanPaint.setAntiAlias(false);
            cleanPaint.setFilterBitmap(false);
            // Reconstruct the translucent floor where the supplied sprite had its
            // built-in stationary knob. The moving knob is drawn separately above it.
            cleanPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            baseCanvas.drawRect(left, top, right, bottom, cleanPaint);
            cleanPaint.setXfermode(null);
            cleanPaint.setColor(Color.argb(64, 208, 209, 212));
            baseCanvas.drawRect(left, top, right, bottom, cleanPaint);
        } catch (RuntimeException error) {
            android.util.Log.w("MobileHorizonTouch", "Não foi possível separar a alavanca clássica.", error);
            if (classicJoystickBase != null) classicJoystickBase.recycle();
            if (classicJoystickKnob != null) classicJoystickKnob.recycle();
            classicJoystickBase = null;
            classicJoystickKnob = null;
        }
    }

    private Bitmap classicBitmap(String fileName) {
        Bitmap cached = classicBitmapCache.get(fileName);
        if (cached != null && !cached.isRecycled()) return cached;
        try (java.io.InputStream input = activity.getAssets().open(
                "touch-controls/classic/" + fileName)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeStream(input, null, options);
            if (decoded != null) classicBitmapCache.put(fileName, decoded);
            return decoded;
        } catch (Exception error) {
            android.util.Log.w("MobileHorizonTouch", "Asset clássico indisponível: " + fileName, error);
            return null;
        }
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
        if (classicButtonsEnabled && drawClassicButton(canvas, spec, pressed, buttonOpacity)) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(dp(8), r * 0.48f));
        paint.setFakeBoldText(true);
        float halfWidth = Math.max(r * 0.86f, paint.measureText(spec.label) / 2f + dp(7));
        float halfHeight = r * 0.76f;
        RectF bounds = new RectF(spec.x - halfWidth, spec.y - halfHeight,
                spec.x + halfWidth, spec.y + halfHeight);
        float corner = Math.min(dp(10), halfHeight * 0.55f);
        paint.setColor(withAlpha(buttonPaletteColor(spec.color, pressed),
                Math.round(255 * buttonOpacity * (pressed ? 0.96f : 0.76f))));
        drawButtonShape(canvas, bounds, corner, spec.shape);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(editingControls ? 2.2f : 1.5f));
        paint.setColor(withAlpha(buttonBorderColor(spec.color),
                Math.round(255 * buttonOpacity * (pressed ? 1f : 0.88f))));
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
        canvas.drawText(spec.custom ? spec.label : UiLanguage.translate(activity, spec.label), spec.x, spec.y - (metrics.ascent + metrics.descent) / 2f, paint);
        paint.setFakeBoldText(false);
    }

    private boolean drawClassicButton(Canvas canvas, ButtonSpec spec, boolean pressed, float alpha) {
        String assetName = classicButtonAsset(spec);
        Bitmap bitmap = classicBitmap(assetName);
        if (bitmap == null) return false;
        float r = spec.radius;
        RectF bounds = new RectF(spec.x - r, spec.y - r, spec.x + r, spec.y + r);
        canvas.save();
        if (pressed) canvas.translate(0, dp(1.2f));
        bitmapPaint.setAlpha(Math.round(255 * clamp(alpha, 0f, 1f)));
        bitmapPaint.setColorFilter(classicButtonFilter(spec.color, pressed));
        canvas.drawBitmap(bitmap, null, bounds, bitmapPaint);
        bitmapPaint.setColorFilter(null);

        if (classicButtonNeedsLabel(spec)) {
            drawMinecraftLabel(canvas, spec.label, spec.x, spec.y,
                    r * 1.55f, r * 0.78f, alpha);
        }
        canvas.restore();

        if (editingControls) {
            classicEditPaint.setStyle(Paint.Style.STROKE);
            classicEditPaint.setStrokeWidth(dp(1.5f));
            classicEditPaint.setColor(Color.argb(215, 115, 205, 255));
            canvas.drawRect(bounds.left - dp(3), bounds.top - dp(3),
                    bounds.right + dp(3), bounds.bottom + dp(3), classicEditPaint);
            classicEditPaint.setStyle(Paint.Style.FILL);
        }
        return true;
    }

    private String classicButtonAsset(ButtonSpec spec) {
        if (spec.custom) return "blank.png";
        switch (spec.id) {
            case JUMP: return "jump.png";
            case SNEAK: return "sneak.png";
            case SPRINT: return "sprint.png";
            case ATTACK: return "attack.png";
            case USE: return "interact.png";
            default: return "blank.png";
        }
    }

    private boolean classicButtonNeedsLabel(ButtonSpec spec) {
        if (spec.custom) return true;
        switch (spec.id) {
            case JOYSTICK:
            case JUMP:
            case SNEAK:
            case SPRINT:
            case ATTACK:
            case USE:
                return false;
            default:
                return true;
        }
    }

    private void ensureMinecraftFont() {
        if (minecraftFontAttempted) return;
        minecraftFontAttempted = true;
        java.io.File resourceRoot = new java.io.File(activity.getFilesDir(),
                ".local/horizon-client/" + OfficialMinecraftInstaller.VERSION);
        java.io.File[] candidates = {
                new java.io.File(resourceRoot, "assets/minecraft/textures/font/ascii.png"),
                new java.io.File(new java.io.File(activity.getFilesDir(), "horizon"),
                        "assets/minecraft/textures/font/ascii.png")
        };
        for (java.io.File candidate : candidates) {
            if (!candidate.isFile()) continue;
            Bitmap decoded = BitmapFactory.decodeFile(candidate.getAbsolutePath());
            if (decoded == null || decoded.getWidth() < 128 || decoded.getHeight() < 128) {
                if (decoded != null) decoded.recycle();
                continue;
            }
            minecraftFontAtlas = decoded;
            buildMinecraftGlyphTable();
            break;
        }
        if (minecraftFontAtlas == null) {
            try {
                fallbackMinecraftTypeface = Typeface.createFromAsset(
                        activity.getAssets(), "horizon-ui/PixelifySans-Medium.ttf");
            } catch (Exception error) {
                fallbackMinecraftTypeface = Typeface.create("sans-serif", Typeface.BOLD);
                android.util.Log.w("MobileHorizonTouch",
                        "Fonte Minecraft ainda não preparada; usando fonte pixelada alternativa.", error);
            }
        }
    }

    private void buildMinecraftGlyphTable() {
        for (int code = 0; code < minecraftGlyphRects.length; code++) {
            int cellX = (code & 15) * 8;
            int cellY = (code >>> 4) * 8;
            int minX = 8, maxX = -1;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (Color.alpha(minecraftFontAtlas.getPixel(cellX + x, cellY + y)) > 24) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                    }
                }
            }
            if (maxX >= minX) {
                minecraftGlyphRects[code] = new Rect(cellX + minX, cellY,
                        cellX + maxX + 1, cellY + 8);
                minecraftGlyphAdvances[code] = Math.max(1, maxX - minX + 1) + 1f;
            } else {
                minecraftGlyphRects[code] = null;
                minecraftGlyphAdvances[code] = code == ' ' ? 4f : 0f;
            }
        }
    }

    private void drawMinecraftLabel(Canvas canvas, String value, float centerX, float centerY,
                                    float maxWidth, float maxHeight, float alpha) {
        String label = value == null ? "" : value;
        if (label.isEmpty()) return;
        ensureMinecraftFont();
        boolean ascii = minecraftFontAtlas != null;
        for (int i = 0; i < label.length(); i++) {
            if (label.charAt(i) >= 256) { ascii = false; break; }
        }
        if (!ascii) {
            drawFallbackMinecraftLabel(canvas, label, centerX, centerY, maxWidth, maxHeight, alpha);
            return;
        }
        float units = 0f;
        for (int i = 0; i < label.length(); i++) {
            float advance = minecraftGlyphAdvances[label.charAt(i)];
            units += advance > 0f ? advance : 4f;
        }
        if (units <= 0f) return;
        float glyphScale = Math.min(maxHeight / 8f, maxWidth / units);
        if (glyphScale <= 0f) return;
        float totalWidth = units * glyphScale;
        float penX = centerX - totalWidth / 2f;
        float top = centerY - 4f * glyphScale;
        bitmapPaint.setAlpha(Math.round(255 * clamp(alpha, 0f, 1f)));
        bitmapPaint.setColorFilter(CLASSIC_TEXT_FILTER);
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            Rect src = minecraftGlyphRects[c];
            float advance = minecraftGlyphAdvances[c];
            if (advance <= 0f) advance = 4f;
            if (src != null) {
                RectF dst = new RectF(penX, top, penX + src.width() * glyphScale,
                        top + 8f * glyphScale);
                canvas.drawBitmap(minecraftFontAtlas, src, dst, bitmapPaint);
            }
            penX += advance * glyphScale;
        }
        bitmapPaint.setColorFilter(null);
    }

    private void drawFallbackMinecraftLabel(Canvas canvas, String label, float centerX, float centerY,
                                            float maxWidth, float maxHeight, float alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(fallbackMinecraftTypeface == null
                ? Typeface.create("sans-serif", Typeface.BOLD) : fallbackMinecraftTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(false);
        float textSize = maxHeight;
        paint.setTextSize(textSize);
        float measured = paint.measureText(label);
        if (measured > maxWidth && measured > 0f) {
            paint.setTextScaleX(maxWidth / measured);
        } else {
            paint.setTextScaleX(1f);
        }
        paint.setColor(Color.argb(Math.round(255 * clamp(alpha, 0f, 1f)), 24, 24, 24));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(label, centerX, centerY - (metrics.ascent + metrics.descent) / 2f, paint);
        paint.setTextScaleX(1f);
        paint.setTypeface(Typeface.DEFAULT);
    }

    private static final String[] BUTTON_COLOR_NAMES = {
            "Azul", "Roxo", "Verde", "Laranja", "Vermelho", "Rosa", "Ciano", "Cinza"
    };
    private static final int[] BUTTON_PALETTE = {
            0xff28669b, 0xff7449b8, 0xff23845a, 0xffb96724,
            0xffb43c45, 0xffb34380, 0xff168a9a, 0xff666666
    };
    // Base sprite art stays neutral by default; selected colors tint its pixel shading.
    // A second filter per palette color gives a distinct pressed-state response without extra bitmaps.
    private static final PorterDuffColorFilter[] CLASSIC_BUTTON_FILTERS = buildClassicButtonFilters();

    private static PorterDuffColorFilter[] buildClassicButtonFilters() {
        PorterDuffColorFilter[] filters = new PorterDuffColorFilter[BUTTON_PALETTE.length * 2];
        for (int i = 0; i < BUTTON_PALETTE.length; i++) {
            int tint = i == 7 ? Color.WHITE : BUTTON_PALETTE[i];
            filters[i * 2] = new PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY);
            int pressed = Color.rgb(
                    Color.red(tint) * 0x98 / 255,
                    Color.green(tint) * 0x98 / 255,
                    Color.blue(tint) * 0x98 / 255);
            filters[i * 2 + 1] = new PorterDuffColorFilter(pressed, PorterDuff.Mode.MULTIPLY);
        }
        return filters;
    }

    private static PorterDuffColorFilter classicButtonFilter(int color, boolean pressed) {
        return CLASSIC_BUTTON_FILTERS[clampButtonColor(color) * 2 + (pressed ? 1 : 0)];
    }
    private static final int[] MANAGED_BUTTON_IDS = {
            JOYSTICK, MOUSE_LEFT, MOUSE_RIGHT, JUMP, SNEAK, SPRINT, INVENTORY,
            DROP, SWAP, ATTACK, USE, PICK, PERSPECTIVE, CHAT, TAB, ENTER, ESCAPE
    };
    private static final String[] MANAGED_BUTTON_NAMES = {
            "Joystick", "Clique esquerdo", "Clique direito", "Pular", "Agachar",
            "Corrida (Ctrl)", "Inventário", "Soltar item", "Mão secundária",
            "Atacar", "Usar / colocar", "Selecionar bloco", "Perspectiva",
            "Chat", "Tab", "Enter", "Esc"
    };
    private static int clampButtonColor(int color) {
        return Math.max(0, Math.min(BUTTON_PALETTE.length - 1, color));
    }
    private int buttonPaletteColor(int color, boolean pressed) {
        int base = BUTTON_PALETTE[clampButtonColor(color)];
        return pressed ? blendColor(base, Color.WHITE, 0.25f) : base;
    }
    private int buttonBorderColor(int color) {
        return blendColor(BUTTON_PALETTE[clampButtonColor(color)], Color.WHITE, 0.55f);
    }
    private int blendColor(int from, int to, float amount) {
        float t = clamp(amount, 0f, 1f);
        return Color.rgb(
                Math.round(Color.red(from) * (1f - t) + Color.red(to) * t),
                Math.round(Color.green(from) * (1f - t) + Color.green(to) * t),
                Math.round(Color.blue(from) * (1f - t) + Color.blue(to) * t));
    }
    private int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
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
        drawStyledUtilityButton(canvas, FLOATING_MENU, "", floatingMenuPixelX(),
                floatingMenuPixelY(), dp(18));
    }

    private void drawEditorToolbar(Canvas canvas) {
        drawToolbarButton(canvas, ADD_BUTTON, "+", toolbarX(166), toolbarY());
        drawToolbarButton(canvas, DONE_EDITING, "✓", toolbarX(210), toolbarY());
    }

    private void drawToolbarButton(Canvas canvas, int id, String label, float x, float y) {
        if (id == KEYBOARD || id == TOGGLE) {
            drawStyledUtilityButton(canvas, id, label, x, y, dp(id == TOGGLE ? 18 : 17));
            return;
        }
        // The edit/add/done toolbar keeps its editor-specific appearance.
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

    /** Utility controls share the active palette and classic/standard rendering mode. */
    private void drawStyledUtilityButton(Canvas canvas, int id, String label,
                                         float x, float y, float radius) {
        ButtonSpec spec = new ButtonSpec(id, "", x, y, radius);
        spec.color = auxiliaryControlColor;
        spec.opacity = 1f;
        ButtonLayout saved = buttonLayouts.get(id);
        if (saved != null) {
            spec.color = clampButtonColor(saved.color);
            spec.shape = clampShape(saved.shape);
            spec.opacity = clamp(saved.opacity, 0.20f, 1f);
            spec.radius = radius * clamp(saved.scale, 0.60f, 2.40f);
        }
        boolean pressed = pressedControls.contains(id);
        if (!(classicButtonsEnabled && drawClassicButton(canvas, spec, pressed, opacity * spec.opacity))) {
            drawButton(canvas, spec);
        }
        drawUtilityGlyph(canvas, id, label, x, y, spec.radius, spec.color,
                classicButtonsEnabled, opacity * spec.opacity);
    }

    private void drawUtilityGlyph(Canvas canvas, int id, String label, float x, float y,
                                  float radius, int colorIndex, boolean classic, float alpha) {
        int base = BUTTON_PALETTE[clampButtonColor(colorIndex)];
        int luminance = (Color.red(base) * 299 + Color.green(base) * 587
                + Color.blue(base) * 114) / 1000;
        int ink = classic && luminance > 150 ? Color.BLACK : Color.WHITE;
        paint.setColor(withAlpha(ink, Math.round(255f * clamp(alpha, 0f, 1f))));
        paint.setStrokeWidth(Math.max(dp(1.2f), radius * 0.095f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if (id == KEYBOARD) {
            paint.setStyle(Paint.Style.STROKE);
            RectF shell = new RectF(x - radius * 0.42f, y - radius * 0.29f,
                    x + radius * 0.42f, y + radius * 0.27f);
            canvas.drawRoundRect(shell, radius * 0.08f, radius * 0.08f, paint);
            paint.setStyle(Paint.Style.FILL);
            float keyW = radius * 0.12f;
            float keyH = radius * 0.095f;
            for (int row = 0; row < 2; row++) {
                int cols = row == 0 ? 4 : 3;
                float rowY = y - radius * 0.12f + row * radius * 0.18f;
                for (int col = 0; col < cols; col++) {
                    float keyX = x - (cols - 1) * radius * 0.17f / 2f + col * radius * 0.17f;
                    canvas.drawRoundRect(keyX - keyW / 2f, rowY - keyH / 2f,
                            keyX + keyW / 2f, rowY + keyH / 2f,
                            keyW * 0.2f, keyH * 0.2f, paint);
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(x - radius * 0.18f, y + radius * 0.39f,
                    x + radius * 0.18f, y + radius * 0.39f, paint);
        } else if (id == FLOATING_MENU) {
            paint.setStyle(Paint.Style.STROKE);
            for (int row = -1; row <= 1; row++) {
                float yy = y + row * radius * 0.29f;
                canvas.drawLine(x - radius * 0.38f, yy, x + radius * 0.38f, yy, paint);
            }
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(radius * 1.25f);
            paint.setFakeBoldText(true);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText(label, x, y - (metrics.ascent + metrics.descent) / 2f, paint);
            paint.setFakeBoldText(false);
        }
        paint.setStyle(Paint.Style.FILL);
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
            spec.toggle = custom.toggle;
            spec.color = clampButtonColor(custom.color);
            specs.add(spec);
        }
        return specs.toArray(new ButtonSpec[0]);
    }

    private void addButtonSpec(List<ButtonSpec> specs, int id, String label, float x, float y, float legacyRadius) {
        ButtonLayout layout = buttonLayouts.get(id);
        if (layout != null && !layout.visible) return;
        float baseRadius = id == JOYSTICK ? dp(67) * scale : dp(24) * scale;
        float defaultScale = id == JOYSTICK ? 1f
                : clamp(legacyRadius / Math.max(1f, dp(24) * scale), 0.60f, 2.40f);
        float itemScale = layout == null ? defaultScale : layout.scale;
        ButtonSpec spec = new ButtonSpec(id, label, x, y, baseRadius * itemScale);
        if (layout != null) {
            if (layout.x >= 0f) spec.x = layout.x * getWidth();
            if (layout.y >= 0f) spec.y = layout.y * getHeight();
            spec.opacity = layout.opacity;
            spec.shape = layout.shape;
            spec.function = layout.function;
            spec.keyCode = layout.keyCode;
            spec.toggle = layout.toggle;
            spec.color = clampButtonColor(layout.color);
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
                layout.visible = item.optBoolean("visible", true);
                layout.toggle = item.optBoolean("toggle", false);
                layout.color = clampButtonColor(item.optInt("color", 7));
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
                custom.toggle = item.optBoolean("toggle", false);
                custom.color = clampButtonColor(item.optInt("color", 7));
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
                item.put("visible", entry.getValue().visible);
                item.put("toggle", entry.getValue().toggle);
                item.put("color", entry.getValue().color);
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
                item.put("toggle", custom.toggle);
                item.put("color", custom.color);
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
        if (classicButtonsEnabled) return spec.radius;
        snapMeasurePaint.setTextSize(Math.max(dp(8), spec.radius * 0.48f));
        snapMeasurePaint.setFakeBoldText(true);
        return Math.max(spec.radius * 0.86f,
                snapMeasurePaint.measureText(spec.label) / 2f + dp(7));
    }

    private float buttonHalfHeight(ButtonSpec spec) {
        return classicButtonsEnabled ? spec.radius : spec.radius * 0.76f;
    }

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

    /** Branded text panel from the earlier keyboard flow; the Android IME submits into Horizon. */
    private void showKeyboardDialog() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(UiLanguage.translate(activity, "Mensagem, comando ou texto…"));
        input.setTextSize(16);
        input.setTextColor(0xfff5f1ff);
        input.setHintTextColor(0xff918aa9);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setPadding(dpInt(14), dpInt(13), dpInt(14), dpInt(13));
        GradientDrawable inputBackground = new GradientDrawable();
        inputBackground.setColor(0xff1c192b);
        inputBackground.setCornerRadius(dp(12));
        inputBackground.setStroke(dpInt(1), 0xff51466f);
        input.setBackground(inputBackground);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpInt(18), dpInt(18), dpInt(18), dpInt(16));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(0xff151321);
        surface.setCornerRadius(dp(20));
        surface.setStroke(dpInt(1), 0xff403654);
        panel.setBackground(surface);
        panel.setElevation(dp(12));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout iconBox = new LinearLayout(activity);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable iconSurface = new GradientDrawable();
        iconSurface.setColor(0xff7557e8);
        iconSurface.setCornerRadius(dp(13));
        iconBox.setBackground(iconSurface);
        TextView icon = new TextView(activity);
        icon.setText(UiLanguage.translate(activity, "Aa"));
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(17);
        icon.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        iconBox.addView(icon, new LinearLayout.LayoutParams(dpInt(44), dpInt(44)));
        header.addView(iconBox, new LinearLayout.LayoutParams(dpInt(44), dpInt(44)));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dpInt(12), 0, 0, 0);
        TextView title = new TextView(activity);
        title.setText(UiLanguage.translate(activity, "Painel de texto"));
        title.setTextColor(0xfff4f0ff);
        title.setTextSize(19);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        heading.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = new TextView(activity);
        subtitle.setText(UiLanguage.translate(activity, "DIGITAÇÃO DO MINECRAFT"));
        subtitle.setTextColor(0xffb9a8ff);
        subtitle.setTextSize(10);
        subtitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dpInt(3);
        heading.addView(subtitle, subtitleParams);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView instructions = new TextView(activity);
        instructions.setText(UiLanguage.translate(activity, "Digite abaixo e envie para o campo ativo ou para o chat. O texto aparece no jogo depois do envio."));
        instructions.setTextColor(0xffb8b1cf);
        instructions.setTextSize(12);
        instructions.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams instructionsParams = new LinearLayout.LayoutParams(-1, -2);
        instructionsParams.topMargin = dpInt(16);
        instructionsParams.bottomMargin = dpInt(10);
        panel.addView(instructions, instructionsParams);

        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        TextView helper = new TextView(activity);
        helper.setText(UiLanguage.translate(activity, "Use o botão Enviar do teclado ou o botão abaixo."));
        helper.setTextColor(0xff8f88a9);
        helper.setTextSize(11);
        LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(-1, -2);
        helperParams.topMargin = dpInt(8);
        panel.addView(helper, helperParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dpInt(17);

        TextView cancel = new TextView(activity);
        cancel.setText(UiLanguage.translate(activity, "Cancelar"));
        cancel.setTextColor(0xffe5dfff);
        cancel.setTextSize(14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancel.setPadding(dpInt(10), dpInt(13), dpInt(10), dpInt(13));
        GradientDrawable cancelSurface = new GradientDrawable();
        cancelSurface.setColor(0xff242035);
        cancelSurface.setCornerRadius(dp(12));
        cancelSurface.setStroke(dpInt(1), 0xff51466f);
        cancel.setBackground(cancelSurface);
        cancel.setClickable(true);
        cancel.setFocusable(true);

        TextView send = new TextView(activity);
        send.setText(UiLanguage.translate(activity, "Enviar  ↑"));
        send.setTextColor(Color.WHITE);
        send.setTextSize(14);
        send.setGravity(Gravity.CENTER);
        send.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        send.setPadding(dpInt(10), dpInt(13), dpInt(10), dpInt(13));
        GradientDrawable sendSurface = new GradientDrawable();
        sendSurface.setColor(0xff7557e8);
        sendSurface.setCornerRadius(dp(12));
        sendSurface.setStroke(dpInt(1), 0xffa28cff);
        send.setBackground(sendSurface);
        send.setClickable(true);
        send.setFocusable(true);

        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dpInt(46), 1f);
        cancelParams.rightMargin = dpInt(8);
        actions.addView(cancel, cancelParams);
        actions.addView(send, new LinearLayout.LayoutParams(0, dpInt(46), 1.15f));
        panel.addView(actions, actionsParams);

        AlertDialog dialog = new AlertDialog.Builder(activity).setView(panel).create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        Runnable submit = () -> {
            String value = input.getText() == null ? "" : input.getText().toString();
            if (gameActivity != null && !value.isEmpty()) {
                gameActivity.injectKeyboardText(value);
            }
            if (dialog.isShowing()) dialog.dismiss();
        };
        send.setOnClickListener(v -> submit.run());
        input.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterKey = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || enterKey) {
                submit.run();
                return true;
            }
            return false;
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int dialogWidth = Math.max(dpInt(240), Math.min(dpInt(520), screenWidth - dpInt(32)));
            dialog.getWindow().setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager manager = (InputMethodManager)
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 140);
    }

    private void showAddButtonDialog() {
        EditText label = new EditText(activity);
        label.setSingleLine(true);
        label.setHint(UiLanguage.translate(activity, "Texto do botão (ex.: MAP)"));
        label.setText(UiLanguage.translate(activity, "G"));
        EditText key = new EditText(activity);
        key.setSingleLine(true);
        key.setHint(UiLanguage.translate(activity, "Tecla (ex.: G, SPACE, F6)"));
        key.setText(UiLanguage.translate(activity, "G"));
        android.widget.CheckBox toggle = new android.widget.CheckBox(activity);
        toggle.setText(UiLanguage.translate(activity, "Alternância: ativar/desativar"));
        toggle.setTextColor(0xffeaf0ff);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) dp(20), (int) dp(8), (int) dp(20), (int) dp(4));
        panel.addView(label, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(key, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.translate(activity, "Adicionar botão"))
                .setMessage(UiLanguage.translate(activity, "Escolha entre manter a tecla pressionada enquanto segura ou alternar entre ativar e desativar."))
                .setView(panel)
                .setNegativeButton(UiLanguage.translate(activity, "Cancelar"), (d, which) -> { })
                .setPositiveButton(UiLanguage.translate(activity, "Adicionar"), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String caption = label.getText().toString().trim();
            int keyCode = parseKeyCode(key.getText().toString());
            if (caption.isEmpty() || caption.length() > 7 || keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                Toast.makeText(activity, UiLanguage.translate(activity, "Informe um nome curto e uma tecla válida."), Toast.LENGTH_LONG).show();
                return;
            }
            int id = CUSTOM_BUTTON_START;
            for (CustomButton item : customButtons) id = Math.max(id, item.id + 1);
            float x = 0.62f + (customButtons.size() % 3) * 0.055f;
            float y = 0.50f + (customButtons.size() % 4) * 0.055f;
            CustomButton created = new CustomButton(id, caption, keyCode,
                    clamp(x, 0.10f, 0.90f), clamp(y, 0.15f, 0.85f), 1f);
            created.toggle = toggle.isChecked();
            customButtons.add(created);
            saveCustomButtons();
            invalidate();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private TextView miniLabel(String value, int size, boolean bold) {
        TextView label = new TextView(activity);
        label.setText(UiLanguage.translate(activity, value));
        label.setTextColor(0xffeaf0ff);
        label.setTextSize(size);
        if (bold) label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return label;
    }

    private void dismissGameControlMenu() {
        AlertDialog menu = activeGameMenu;
        if (menu != null && menu.isShowing()) {
            menu.dismiss();
        } else {
            activeGameMenu = null;
        }
    }

    private View createGameMenuItem(String symbol, String titleText, String subtitleText,
                                    boolean danger, Runnable action) {
        titleText = UiLanguage.translate(activity, titleText);
        subtitleText = UiLanguage.translate(activity, subtitleText);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpInt(10), dpInt(10), dpInt(10), dpInt(10));
        row.setMinimumHeight(dpInt(66));
        GradientDrawable rowBackground = new GradientDrawable();
        rowBackground.setColor(danger ? 0xff2c1d2b : 0xff1b2435);
        rowBackground.setCornerRadius(dp(14));
        if (danger) rowBackground.setStroke(dpInt(1), 0x885a344b);
        row.setBackground(rowBackground);
        row.setClickable(true);
        row.setFocusable(true);

        TextView emblem = new TextView(activity);
        emblem.setText(symbol);
        emblem.setTextColor(danger ? 0xffffaebe : 0xffc4b7ff);
        emblem.setTextSize(20);
        emblem.setGravity(Gravity.CENTER);
        GradientDrawable emblemBackground = new GradientDrawable();
        emblemBackground.setColor(danger ? 0xff492738 : 0xff30284f);
        emblemBackground.setCornerRadius(dp(12));
        emblem.setBackground(emblemBackground);
        row.addView(emblem, new LinearLayout.LayoutParams(dpInt(42), dpInt(42)));

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(danger ? 0xffffd5de : 0xfff3f5ff);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        TextView subtitle = new TextView(activity);
        subtitle.setText(subtitleText);
        subtitle.setTextColor(0xffa8b2c7);
        subtitle.setTextSize(11);
        subtitle.setMaxLines(2);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dpInt(3);
        copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
        copy.addView(subtitle, subtitleParams);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dpInt(12);
        copyParams.rightMargin = dpInt(5);
        row.addView(copy, copyParams);

        TextView arrow = new TextView(activity);
        arrow.setText(UiLanguage.translate(activity, "›"));
        arrow.setTextColor(danger ? 0xffe7a4b6 : 0xff7887a6);
        arrow.setTextSize(25);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dpInt(18), dpInt(38)));

        row.setOnClickListener(v -> {
            dismissGameControlMenu();
            action.run();
        });
        return row;
    }

    private void showGameControlMenu() {
        if (gameActivity == null || previewOnly) return;
        dismissGameControlMenu();

        // The previous working implementation used a dialog owned by NativeGameActivity.
        // Avoid a focusable full-screen PopupWindow: on some devices it can steal NativeActivity
        // focus and interrupt the game surface.
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpInt(14), dpInt(14), dpInt(14), dpInt(12));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(0xff121a29);
        panelBackground.setCornerRadius(dp(20));
        panelBackground.setStroke(dpInt(1), 0xff433969);
        panel.setBackground(panelBackground);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = new TextView(activity);
        logo.setText(UiLanguage.translate(activity, "MH"));
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(15);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable logoBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[] {0xff8b6cff, 0xff6045d8});
        logoBackground.setCornerRadius(dp(13));
        logo.setBackground(logoBackground);
        header.addView(logo, new LinearLayout.LayoutParams(dpInt(44), dpInt(44)));

        LinearLayout brand = new LinearLayout(activity);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView brandName = new TextView(activity);
        brandName.setText(UiLanguage.translate(activity, "MOBILEHORIZON"));
        brandName.setTextColor(0xffb9adff);
        brandName.setTextSize(10);
        brandName.setLetterSpacing(0.08f);
        brandName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView menuTitle = new TextView(activity);
        menuTitle.setText(UiLanguage.translate(activity, "Menu do jogo"));
        menuTitle.setTextColor(0xfff6f7ff);
        menuTitle.setTextSize(18);
        menuTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(brandName, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams menuTitleParams = new LinearLayout.LayoutParams(-1, -2);
        menuTitleParams.topMargin = dpInt(2);
        brand.addView(menuTitle, menuTitleParams);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1f);
        brandParams.leftMargin = dpInt(11);
        header.addView(brand, brandParams);

        TextView close = new TextView(activity);
        close.setText(UiLanguage.translate(activity, "×"));
        close.setTextSize(25);
        close.setTextColor(0xffd8deed);
        close.setGravity(Gravity.CENTER);
        GradientDrawable closeBackground = new GradientDrawable();
        closeBackground.setColor(0xff263148);
        closeBackground.setCornerRadius(dp(12));
        close.setBackground(closeBackground);
        close.setContentDescription(UiLanguage.translate(activity, "Fechar menu"));
        close.setOnClickListener(v -> dismissGameControlMenu());
        header.addView(close, new LinearLayout.LayoutParams(dpInt(38), dpInt(38)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dpInt(46)));

        View divider = new View(activity);
        divider.setBackgroundColor(0xff2a3449);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dpInt(1));
        dividerParams.topMargin = dpInt(13);
        dividerParams.bottomMargin = dpInt(11);
        panel.addView(divider, dividerParams);

        TextView sessionState = new TextView(activity);
        sessionState.setText(UiLanguage.translate(activity,
                controlsVisible ? "●  CONTROLES ATIVOS" : "○  CONTROLES OCULTOS"));
        sessionState.setTextColor(controlsVisible ? 0xff8be0bb : 0xffa8b2c7);
        sessionState.setTextSize(10);
        sessionState.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sessionState.setPadding(dpInt(11), dpInt(8), dpInt(11), dpInt(8));
        GradientDrawable stateBackground = new GradientDrawable();
        stateBackground.setColor(controlsVisible ? 0xff17352f : 0xff252d3e);
        stateBackground.setCornerRadius(dp(10));
        sessionState.setBackground(stateBackground);
        panel.addView(sessionState, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout menuItems = new LinearLayout(activity);
        menuItems.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroller = new ScrollView(activity);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.addView(menuItems, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(-1, dpInt(230));
        scrollerParams.topMargin = dpInt(12);
        scrollerParams.bottomMargin = dpInt(9);
        panel.addView(scroller, scrollerParams);

        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, -2);
        itemParams.bottomMargin = dpInt(8);
        menuItems.addView(createGameMenuItem("✥", "Personalizar layout",
                "Mover, redimensionar e configurar os botões", false, () -> {
                    controlsVisible = true;
                    editingControls = true;
                    snapGuideX = Float.NaN;
                    snapGuideY = Float.NaN;
                    Toast.makeText(activity,
                            UiLanguage.translate(activity, "Arraste os controles; toque em um para configurar. ✓ salva."),
                            Toast.LENGTH_LONG).show();
                    invalidate();
                }), new LinearLayout.LayoutParams(itemParams));
        menuItems.addView(createGameMenuItem("⚙", "Ajustes dos controles",
                "Sensibilidade, opacidade e preferências", false, this::showGlobalSettingsDialog),
                new LinearLayout.LayoutParams(itemParams));
        menuItems.addView(createGameMenuItem(controlsVisible ? "◉" : "○",
                controlsVisible ? "Ocultar controles" : "Mostrar controles",
                controlsVisible ? "Deixar a tela do jogo mais livre" : "Exibir os botões de toque",
                false, () -> {
                    controlsVisible = !controlsVisible;
                    if (!controlsVisible) {
                        editingControls = false;
                        releaseHeldControls();
                    }
                    invalidate();
                }), new LinearLayout.LayoutParams(itemParams));
        menuItems.addView(createGameMenuItem("↩", "Fechar jogo",
                "Encerrar a sessão e voltar à tela inicial", true, () ->
                    new AlertDialog.Builder(gameActivity)
                            .setTitle(UiLanguage.translate(activity, "Fechar o jogo?"))
                            .setMessage(UiLanguage.translate(activity, "Você voltará à tela inicial do MobileHorizon."))
                            .setNegativeButton(UiLanguage.translate(activity, "Cancelar"), (d, w) -> { })
                            .setPositiveButton(UiLanguage.translate(activity, "Fechar jogo"), (d, w) -> {
                                releaseAllInputs();
                                gameActivity.finish();
                            })
                            .show()), new LinearLayout.LayoutParams(itemParams));

        TextView footer = new TextView(activity);
        footer.setText(UiLanguage.translate(activity, "MOBILEHORIZON  •  CONTROLES NATIVOS"));
        footer.setTextColor(0xff7885a0);
        footer.setTextSize(9);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.06f);
        panel.addView(footer, new LinearLayout.LayoutParams(-1, dpInt(20)));

        AlertDialog menu = new AlertDialog.Builder(gameActivity).setView(panel).create();
        menu.setCancelable(true);
        menu.setCanceledOnTouchOutside(true);
        menu.setOnDismissListener(d -> {
            if (activeGameMenu == menu) activeGameMenu = null;
        });
        activeGameMenu = menu;
        menu.show();
        if (menu.getWindow() != null) {
            menu.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int dialogWidth = Math.max(dpInt(250),
                    Math.min(dpInt(440), screenWidth - dpInt(28)));
            dialogWidth = Math.min(screenWidth, dialogWidth);
            menu.getWindow().setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            android.view.WindowManager.LayoutParams attrs = menu.getWindow().getAttributes();
            attrs.dimAmount = 0.48f;
            menu.getWindow().setAttributes(attrs);
        }
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
        int savedFunction = custom != null ? custom.function : (saved == null ? 0 : saved.function);
        final int currentFunction = custom != null && savedFunction == FUNCTION_DEFAULT
                ? FUNCTION_KEY : savedFunction;
        int currentKey = custom != null ? custom.keyCode : (saved == null ? KeyEvent.KEYCODE_UNKNOWN : saved.keyCode);
        boolean currentToggle = custom != null ? custom.toggle : (saved != null && saved.toggle);
        int currentColor = clampButtonColor(custom != null ? custom.color : (saved == null ? 7 : saved.color));
        boolean joystickButton = id == JOYSTICK;
        boolean inGame = gameActivity != null && !previewOnly;
        final int[] chosenShape = {clampShape(currentShape)};

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpInt(10), dpInt(8), dpInt(10), dpInt(8));
        panel.setBackgroundColor(Color.TRANSPARENT);

        TextView title = miniLabel("Botão: " + spec.label, 13, true);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dpInt(24)));

        TextView scaleLabel = miniLabel("Tamanho " + Math.round(currentScale * 100) + "%", 11, false);
        SeekBar size = new SeekBar(activity);
        size.setMax(180);
        size.setProgress(Math.round(clamp(currentScale, 0.60f, 2.40f) * 100f) - 60);
        size.setPadding(0, 0, 0, 0);
        panel.addView(scaleLabel, new LinearLayout.LayoutParams(-1, dpInt(18)));
        panel.addView(size, new LinearLayout.LayoutParams(-1, dpInt(28)));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                scaleLabel.setText(UiLanguage.translate(activity, "Tamanho ") + (progress + 60) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        final int[] chosenColor = {currentColor};
        if (!classicButtonsEnabled) {
            TextView shapeLabel = miniLabel("Formato", 11, false);
            String[] shapeNames = new String[]{"Retângulo arredondado", "Círculo / oval", "Quadrado"};
            Button shapeButton = new Button(activity);
            shapeButton.setTextSize(11);
            shapeButton.setMinHeight(dpInt(34));
            shapeButton.setText(UiLanguage.translate(activity, "Formato: ") + shapeNames[chosenShape[0]]);
            shapeButton.setOnClickListener(v -> {
                chosenShape[0] = (chosenShape[0] + 1) % shapeNames.length;
                shapeButton.setText(UiLanguage.translate(activity, "Formato: ") + shapeNames[chosenShape[0]]);
            });
            panel.addView(shapeLabel, new LinearLayout.LayoutParams(-1, dpInt(17)));
            panel.addView(shapeButton, new LinearLayout.LayoutParams(-1, dpInt(34)));

            Button colorButton = new Button(activity);
            colorButton.setText(UiLanguage.translate(activity, "Cor: ") + BUTTON_COLOR_NAMES[chosenColor[0]]);
            colorButton.setTextSize(11);
            colorButton.setMinHeight(dpInt(34));
            colorButton.setOnClickListener(v -> {
                chosenColor[0] = (chosenColor[0] + 1) % BUTTON_COLOR_NAMES.length;
                colorButton.setText(UiLanguage.translate(activity, "Cor: ") + BUTTON_COLOR_NAMES[chosenColor[0]]);
            });
            TextView colorLabel = miniLabel("Cor do botão", 11, false);
            panel.addView(colorLabel, new LinearLayout.LayoutParams(-1, dpInt(17)));
            panel.addView(colorButton, new LinearLayout.LayoutParams(-1, dpInt(34)));
        }

        android.widget.CheckBox toggleMode = new android.widget.CheckBox(activity);
        toggleMode.setText(UiLanguage.translate(activity, "Alternância: ativar/desativar"));
        toggleMode.setTextColor(0xffeaf0ff);
        toggleMode.setChecked(currentToggle);
        if (!joystickButton) panel.addView(toggleMode, new LinearLayout.LayoutParams(-1, dpInt(36)));

        android.widget.CheckBox autoSprint = new android.widget.CheckBox(activity);
        autoSprint.setText(UiLanguage.translate(activity, "Corrida automática ao empurrar até a frente"));
        autoSprint.setTextColor(0xffeaf0ff);
        autoSprint.setChecked(autoSprintEnabled);
        if (joystickButton) panel.addView(autoSprint, new LinearLayout.LayoutParams(-1, dpInt(42)));

        TextView opacityLabel = miniLabel("Opacidade " + Math.round(currentOpacity * 100) + "%", 11, false);
        SeekBar alpha = new SeekBar(activity);
        alpha.setMax(80);
        alpha.setProgress(Math.round(clamp(currentOpacity, 0.20f, 1f) * 100f) - 20);
        panel.addView(opacityLabel, new LinearLayout.LayoutParams(-1, dpInt(18)));
        panel.addView(alpha, new LinearLayout.LayoutParams(-1, dpInt(28)));
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                opacityLabel.setText(UiLanguage.translate(activity, "Opacidade ") + (progress + 20) + "%");
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
        if (!joystickButton && !inGame) {
            panel.addView(functionLabel, new LinearLayout.LayoutParams(-1, dpInt(17)));
            panel.addView(function, new LinearLayout.LayoutParams(-1, dpInt(34)));
        }

        EditText key = new EditText(activity);
        key.setSingleLine(true);
        key.setTextSize(12);
        key.setTextColor(Color.WHITE);
        key.setHintTextColor(0xffaeb9cb);
        key.setHint(UiLanguage.translate(activity, "Tecla, ex.: G, SPACE, F6"));
        key.setPadding(dpInt(7), 0, dpInt(7), 0);
        key.setText(currentKey == KeyEvent.KEYCODE_UNKNOWN ? "" : KeyEvent.keyCodeToString(currentKey).replace("KEYCODE_", ""));
        key.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff7e91ad));
        if (!joystickButton && !inGame) {
            panel.addView(key, new LinearLayout.LayoutParams(-1, dpInt(36)));
            key.setVisibility(function.getSelectedItemPosition() == FUNCTION_KEY ? View.VISIBLE : View.GONE);
            function.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long itemId) {
                    key.setVisibility(position == FUNCTION_KEY ? View.VISIBLE : View.GONE);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
        }

        EditText label = null;
        if (custom != null && !inGame) {
            label = new EditText(activity);
            label.setSingleLine(true);
            label.setTextSize(12);
            label.setTextColor(Color.WHITE);
            label.setHintTextColor(0xffaeb9cb);
            label.setHint(classicButtonsEnabled ? "Texto em fonte Minecraft" : "Texto do botão");
            label.setPadding(dpInt(7), 0, dpInt(7), 0);
            label.setText(custom.label);
            label.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff7e91ad));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, dpInt(36));
            labelParams.topMargin = dpInt(3);
            panel.addView(label, labelParams);
        }
        final EditText customLabel = label;

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button reset = new Button(activity);
        reset.setText(UiLanguage.translate(activity, "Excluir"));
        reset.setTextSize(10);
        Button cancel = new Button(activity);
        cancel.setText(UiLanguage.translate(activity, "Fechar"));
        cancel.setTextSize(10);
        Button save = new Button(activity);
        save.setText(UiLanguage.translate(activity, "Salvar"));
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
        int menuWidth = Math.min(dpInt(224), Math.max(dpInt(176), screenWidth - dpInt(16)));
        int menuHeight = Math.min(dpInt(290), Math.max(dpInt(180), screenHeight - dpInt(16)));
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
            Toast.makeText(activity, UiLanguage.translate(activity, "Não foi possível abrir a personalização sem interromper o jogo."), Toast.LENGTH_SHORT).show();
            return;
        }

        reset.setOnClickListener(v -> {
            if (custom != null) {
                customButtons.remove(custom);
                saveCustomButtons();
            } else {
                ButtonLayout hidden = buttonLayouts.computeIfAbsent(id, ignored -> newDefaultButtonLayout(id));
                hidden.visible = false;
                saveButtonLayouts();
            }
            releaseHeldControls();
            invalidate();
            popup.dismiss();
        });
        cancel.setOnClickListener(v -> popup.dismiss());
        save.setOnClickListener(v -> {
            int selectedFunction = (joystickButton || inGame)
                    ? currentFunction : function.getSelectedItemPosition();
            int selectedKey = (joystickButton || inGame)
                    ? currentKey : parseKeyCode(key.getText().toString());
            if (!joystickButton && !inGame && selectedFunction == FUNCTION_KEY
                    && selectedKey == KeyEvent.KEYCODE_UNKNOWN) {
                Toast.makeText(activity, UiLanguage.translate(activity, "Informe uma tecla válida para essa função."), Toast.LENGTH_SHORT).show();
                return;
            }
            float newScale = (size.getProgress() + 60) / 100f;
            float newOpacity = (alpha.getProgress() + 20) / 100f;
            int newShape = clampShape(chosenShape[0]);
            int newColor = clampButtonColor(chosenColor[0]);
            boolean newToggle = !joystickButton && toggleMode.isChecked();
            if (joystickButton) {
                autoSprintEnabled = autoSprint.isChecked();
                activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_AUTO_SPRINT, autoSprintEnabled).apply();
                if (!autoSprintEnabled) setJoystickSprint(false);
            }
            // If a latched button is changed back to hold-to-press mode, release
            // its action immediately instead of keeping an unreachable key held.
            if (!newToggle && latchedControls.contains(id)) releaseHeldControls();
            if (custom != null) {
                String caption = customLabel == null ? custom.label : customLabel.getText().toString().trim();
                if (caption.isEmpty() || caption.length() > 7) {
                    Toast.makeText(activity, UiLanguage.translate(activity, "Use um texto de até 7 caracteres."), Toast.LENGTH_SHORT).show();
                    return;
                }
                custom.label = caption;
                if (!inGame) {
                    custom.keyCode = selectedKey == KeyEvent.KEYCODE_UNKNOWN ? custom.keyCode : selectedKey;
                    custom.function = selectedFunction;
                }
                custom.scale = newScale;
                custom.opacity = newOpacity;
                custom.shape = newShape;
                custom.color = newColor;
                custom.toggle = newToggle;
                saveCustomButtons();
            } else {
                ButtonLayout updated = buttonLayouts.computeIfAbsent(id, ignored -> newDefaultButtonLayout(id));
                updated.scale = newScale;
                updated.opacity = newOpacity;
                updated.shape = newShape;
                updated.color = newColor;
                updated.toggle = newToggle;
                updated.visible = true;
                if (!joystickButton && !inGame) {
                    updated.function = selectedFunction;
                    updated.keyCode = selectedFunction == FUNCTION_KEY ? selectedKey : KeyEvent.KEYCODE_UNKNOWN;
                }
                saveButtonLayouts();
            }
            auxiliaryControlColor = newColor;
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_AUXILIARY_CONTROL_COLOR, auxiliaryControlColor).apply();
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
        enabled.setText(UiLanguage.translate(activity, "Ativar controles na tela do jogo"));
        enabled.setTextColor(0xff18213a);
        enabled.setChecked(prefs.getBoolean(KEY_ENABLED, true));
        panel.addView(enabled, new LinearLayout.LayoutParams(-1, -2));

        android.widget.CheckBox handed = new android.widget.CheckBox(activity);
        handed.setText(UiLanguage.translate(activity, "Layout canhoto (joystick à direita)"));
        handed.setTextColor(0xff18213a);
        handed.setChecked(leftHanded);
        panel.addView(handed, new LinearLayout.LayoutParams(-1, -2));

        android.widget.CheckBox classicButtons = new android.widget.CheckBox(activity);
        classicButtons.setText(UiLanguage.translate(activity, "Botões Clássicos"));
        classicButtons.setTextColor(0xff18213a);
        classicButtons.setChecked(classicButtonsEnabled);
        panel.addView(classicButtons, new LinearLayout.LayoutParams(-1, -2));

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

        Button manageButtons = new Button(activity);
        manageButtons.setText(UiLanguage.translate(activity, "Gerenciar / restaurar botões predefinidos"));
        manageButtons.setOnClickListener(v -> showButtonVisibilityManager());
        panel.addView(manageButtons, new LinearLayout.LayoutParams(-1, dpInt(42)));

        TextView gapLabel = textSetting("Distância de encaixe " + Math.round(snapGapDp) + " dp");
        SeekBar gapBar = new SeekBar(activity);
        gapBar.setMax(28);
        gapBar.setProgress(Math.round(snapGapDp) - 4);
        panel.addView(gapLabel, new LinearLayout.LayoutParams(-1, dpInt(19)));
        panel.addView(gapBar, new LinearLayout.LayoutParams(-1, dpInt(30)));

        size.setOnSeekBarChangeListener(simpleSeek(progress -> sizeLabel.setText(UiLanguage.translate(activity, "Tamanho geral ") + (70 + progress) + "%")));
        alpha.setOnSeekBarChangeListener(simpleSeek(progress -> alphaLabel.setText(UiLanguage.translate(activity, "Opacidade geral ") + (35 + progress) + "%")));
        sensitivityBar.setOnSeekBarChangeListener(simpleSeek(progress -> sensitivityLabel.setText(UiLanguage.translate(activity, "Sensibilidade da câmera ") + (50 + progress) + "%")));
        gapBar.setOnSeekBarChangeListener(simpleSeek(progress -> gapLabel.setText(UiLanguage.translate(activity, "Distância de encaixe ") + (progress + 4) + " dp")));
        ScrollView settingsScroll = new ScrollView(activity);
        settingsScroll.setFillViewport(false);
        settingsScroll.addView(panel, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.translate(activity, "Ajustes dos controles"))
                .setView(settingsScroll)
                .setNegativeButton(UiLanguage.translate(activity, "Cancelar"), null)
                .setPositiveButton(UiLanguage.translate(activity, "Salvar"), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            scale = (size.getProgress() + 70) / 100f;
            opacity = (alpha.getProgress() + 35) / 100f;
            sensitivity = (sensitivityBar.getProgress() + 50) / 100f;
            snapGapDp = gapBar.getProgress() + 4;
            leftHanded = handed.isChecked();
            classicButtonsEnabled = classicButtons.isChecked();
            prefs.edit()
                    .putBoolean(KEY_ENABLED, enabled.isChecked())
                    .putBoolean(KEY_LEFT_HANDED, leftHanded)
                    .putBoolean(KEY_CLASSIC_BUTTONS, classicButtonsEnabled)
                    .putFloat(KEY_SCALE, scale)
                    .putFloat(KEY_OPACITY, opacity)
                    .putFloat(KEY_SENSITIVITY, sensitivity)
                    .putFloat(KEY_SNAP_GAP, snapGapDp)
                    .apply();
            invalidate();
            Toast.makeText(activity, UiLanguage.translate(activity, "Ajustes dos controles salvos."), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private String[] localizedManagedButtonNames() {
        String[] names = MANAGED_BUTTON_NAMES.clone();
        for (int i = 0; i < names.length; i++) names[i] = UiLanguage.translate(activity, names[i]);
        return names;
    }

    private void showButtonVisibilityManager() {
        boolean[] visible = new boolean[MANAGED_BUTTON_IDS.length];
        for (int i = 0; i < MANAGED_BUTTON_IDS.length; i++) {
            ButtonLayout layout = buttonLayouts.get(MANAGED_BUTTON_IDS[i]);
            visible[i] = layout == null || layout.visible;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.translate(activity, "Botões predefinidos"))
                .setMessage(UiLanguage.translate(activity, "Desmarque para excluir da tela. Marque novamente para restaurar. Posições e cores salvas são mantidas."))
                .setMultiChoiceItems(localizedManagedButtonNames(), visible, (d, which, checked) -> {
                    int id = MANAGED_BUTTON_IDS[which];
                    ButtonLayout layout = buttonLayouts.computeIfAbsent(id, ignored -> newDefaultButtonLayout(id));
                    layout.visible = checked;
                    if (!checked) releaseHeldControls();
                    saveButtonLayouts();
                    invalidate();
                })
                .setNegativeButton(UiLanguage.translate(activity, "Restaurar todos"), (d, which) -> {
                    for (int id : MANAGED_BUTTON_IDS) {
                        ButtonLayout layout = buttonLayouts.computeIfAbsent(id, ignored -> newDefaultButtonLayout(id));
                        layout.visible = true;
                    }
                    saveButtonLayouts();
                    invalidate();
                    Toast.makeText(activity, UiLanguage.translate(activity, "Botões predefinidos restaurados."), Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(UiLanguage.translate(activity, "Fechar"), null)
                .create();
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
        text.setText(UiLanguage.translate(activity, value));
        text.setTextColor(0xff18213a);
        text.setTextSize(12);
        return text;
    }

    private void showButtonEditor(int id) {
        showButtonMiniMenu(id);
    }

    private int parseKeyCode(String value) {
        String key = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (key.startsWith("KEYCODE_")) key = key.substring("KEYCODE_".length());
        if (key.startsWith("KEY_")) key = key.substring("KEY_".length());
        int result = KeyEvent.keyCodeFromString("KEYCODE_" + key);
        return result == KeyEvent.KEYCODE_UNKNOWN ? KeyEvent.KEYCODE_UNKNOWN : result;
    }

    private void circle(Canvas canvas, float x, float y, float radius, boolean stroke, float alpha,
                        float effectiveOpacity, int color) {
        paint.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        paint.setStrokeWidth(dp(1.4f));
        int a = Math.round(255 * effectiveOpacity * alpha);
        int tint = stroke ? buttonBorderColor(color)
                : blendColor(BUTTON_PALETTE[clampButtonColor(color)], Color.BLACK, 0.52f);
        paint.setColor(withAlpha(tint, a));
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
                Toast.makeText(activity, UiLanguage.translate(activity, "Layout dos botões salvo."), Toast.LENGTH_SHORT).show();
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
            } else if (track.startGameplay && isCameraDragActionControl(track.control)) {
                // Keep the button's original pressed state while using this same finger to look around.
                // Do not require a second pointer: the held control is released only on pointer-up/cancel.
                if ((Math.abs(dx) >= 0.25f || Math.abs(dy) >= 0.25f) && gameActivity != null) {
                    gameActivity.injectMouseMotion(dx * sensitivity, dy * sensitivity);
                }
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
            setJoystickDirections(false, false, false, false, false);
            setJoystickSprint(false);
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

    private boolean isInsideButtonHitArea(ButtonSpec spec, float x, float y) {
        float dx = Math.abs(x - spec.x);
        float dy = Math.abs(y - spec.y);
        float padding = dp(13);
        if (classicButtonsEnabled) {
            // Classic button art is square, so its corners remain part of the active area.
            return dx <= spec.radius + padding && dy <= spec.radius + padding;
        }
        float hit = spec.radius + padding;
        return dx * dx + dy * dy <= hit * hit;
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
                float d = distanceSquared(x, y, spec.x, spec.y);
                if (isInsideButtonHitArea(spec, x, y) && d < bestDistance) {
                    best = spec;
                    bestDistance = d;
                }
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
            float d = distanceSquared(x, y, spec.x, spec.y);
            if (isInsideButtonHitArea(spec, x, y) && d < bestDistance) {
                best = spec;
                bestDistance = d;
            }
        }
        // While editing, prefer the visible button over the joystick hit region so
        // custom buttons can be dragged anywhere on screen.
        if (editingControls && best != null) return best.id;
        if (findButton(JOYSTICK) != null) {
            float jx = joystickHomeX(), jy = joystickHomeY(), jr = dp(82) * scale;
            if (distanceSquared(x, y, jx, jy) <= jr * jr) return JOYSTICK;
        }
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

    /**
     * Action controls that can be dragged to rotate the camera without releasing the action.
     * UI/navigation controls, the joystick and hotbar hit zones deliberately remain excluded.
     */
    private boolean isCameraDragActionControl(int control) {
        switch (control) {
            case JUMP:
            case SNEAK:
            case SPRINT:
            case ATTACK:
            case USE:
            case DROP:
            case SWAP:
            case PICK:
            case MOUSE_LEFT:
            case MOUSE_RIGHT:
                return true;
            default:
                if (control >= CUSTOM_BUTTON_START) {
                    ButtonSpec spec = findButton(control);
                    if (spec == null) return false;
                    int function = resolvedFunction(spec);
                    return function == FUNCTION_KEY
                            || function == FUNCTION_MOUSE_LEFT
                            || function == FUNCTION_MOUSE_RIGHT;
                }
                return false;
        }
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
        if (joystickSpec == null) {
            setJoystickSprint(false);
            return;
        }
        float rawDx = x - joystickX, rawDy = y - joystickY, radius = joystickSpec.radius;
        float rawForward = -rawDy / radius;
        float rawSide = rawDx / radius;
        float dx = rawDx, dy = rawDy;
        float max = radius * JOYSTICK_MAX_REACH;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > max && len > 0f) {
            float factor = max / len;
            dx *= factor;
            dy *= factor;
        }
        // Movement uses the bounded joystick vector, but sprint activation uses the
        // live finger position before clamping. Otherwise an out-of-range finger stays
        // pinned at the sprint threshold and Ctrl cannot release when the user recedes.
        float forward = -dy / radius;
        boolean up = forward > 0.25f;
        boolean down = dy / radius > 0.25f;
        boolean rectangularZone = classicButtonsEnabled || joystickSpec.shape == 2;
        float lateralLimit = rectangularZone ? 1.0f
                : (float) Math.sqrt(Math.max(0f,
                        1f - SPRINT_TRIGGER_THRESHOLD * SPRINT_TRIGGER_THRESHOLD));
        boolean sprint = rawForward >= SPRINT_TRIGGER_THRESHOLD
                && Math.abs(rawSide) <= lateralLimit;
        setJoystickDirections(up, down, dx / radius < -0.25f,
                dx / radius > 0.25f, sprint);
    }

    private void setJoystickDirections(boolean up, boolean down, boolean left, boolean right, boolean sprint) {
        setJoystickKey(KeyEvent.KEYCODE_W, up);
        setJoystickKey(KeyEvent.KEYCODE_S, down);
        setJoystickKey(KeyEvent.KEYCODE_A, left);
        setJoystickKey(KeyEvent.KEYCODE_D, right);
        setJoystickSprint(autoSprintEnabled && up && sprint);
    }

    private void setJoystickSprint(boolean active) {
        if (joystickSprintActive == active) return;
        joystickSprintActive = active;
        setSprintKeyState();
    }

    private void setSprintKeyState() {
        ButtonSpec sprintButton = findButton(SPRINT);
        boolean sprintButtonDefault = sprintButton == null || !hasConfiguredFunction(sprintButton);
        boolean sprintButtonHeld = sprintButtonDefault
                && (pressedControls.contains(SPRINT) || latchedControls.contains(SPRINT));
        setKeyHeld(KeyEvent.KEYCODE_CTRL_LEFT, joystickSprintActive || sprintButtonHeld);
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
        if (configured != null && configured.toggle) {
            boolean active = !latchedControls.contains(control);
            if (active) {
                latchedControls.add(control);
                pressedControls.add(control);
            } else {
                latchedControls.remove(control);
                pressedControls.remove(control);
            }
            applyButtonAction(configured, active);
            invalidate();
            return;
        }
        if (configured != null) applyButtonAction(configured, true);
        invalidate();
    }

    private void deactivateControl(int control, float x, float y) {
        int activePointers = controlPointerCounts.getOrDefault(control, 0);
        if (activePointers > 1) {
            controlPointerCounts.put(control, activePointers - 1);
            return;
        }
        controlPointerCounts.remove(control);
        ButtonSpec configured = findButton(control);
        if (configured != null && configured.toggle) {
            if (latchedControls.contains(control)) pressedControls.add(control);
            else pressedControls.remove(control);
            invalidate();
            return;
        }
        pressedControls.remove(control);
        if (configured != null) applyButtonAction(configured, false);
        invalidate();
    }

    private void applyButtonAction(ButtonSpec spec, boolean pressed) {
        if (spec == null) return;
        if (hasConfiguredFunction(spec)) {
            applyConfiguredFunction(spec, pressed);
            return;
        }
        switch (spec.id) {
            case JUMP: setKeyHeld(KeyEvent.KEYCODE_SPACE, pressed); break;
            case SNEAK: setKeyHeld(KeyEvent.KEYCODE_SHIFT_LEFT, pressed); break;
            case SPRINT: setSprintKeyState(); break;
            case INVENTORY: setKeyHeld(KeyEvent.KEYCODE_E, pressed); break;
            case ATTACK: setMouseButton(MotionEvent.BUTTON_PRIMARY, pressed, virtualMouseX, virtualMouseY); break;
            case USE: setMouseButton(MotionEvent.BUTTON_SECONDARY, pressed, virtualMouseX, virtualMouseY); break;
            case ESCAPE: setKeyHeld(KeyEvent.KEYCODE_ESCAPE, pressed); break;
            case DROP: setKeyHeld(KeyEvent.KEYCODE_Q, pressed); break;
            case SWAP: setKeyHeld(KeyEvent.KEYCODE_F, pressed); break;
            case PICK: setMouseButton(MotionEvent.BUTTON_TERTIARY, pressed, virtualMouseX, virtualMouseY); break;
            case MOUSE_LEFT:
                if (pressed) sendMouseMotion(virtualMouseX, virtualMouseY);
                setMouseButton(MotionEvent.BUTTON_PRIMARY, pressed, virtualMouseX, virtualMouseY);
                break;
            case MOUSE_RIGHT: setMouseButton(MotionEvent.BUTTON_SECONDARY, pressed, virtualMouseX, virtualMouseY); break;
            case PERSPECTIVE: setKeyHeld(KeyEvent.KEYCODE_F5, pressed); break;
            case CHAT: setKeyHeld(KeyEvent.KEYCODE_T, pressed); break;
            case TAB: setKeyHeld(KeyEvent.KEYCODE_TAB, pressed); break;
            case ENTER: setKeyHeld(KeyEvent.KEYCODE_ENTER, pressed); break;
            case HOTBAR_PREV: if (pressed) sendMouseScroll(1f); break;
            case HOTBAR_NEXT: if (pressed) sendMouseScroll(-1f); break;
            default:
                if (spec.custom && spec.keyCode != KeyEvent.KEYCODE_UNKNOWN) setKeyHeld(spec.keyCode, pressed);
                break;
        }
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
        joystickSprintActive = false;
        latchedControls.clear();
        for (Integer key : new HashSet<>(heldKeys)) setKeyHeld(key, false);
        joystickKeys.clear();
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
        if (activeGameMenu != null) {
            try { if (activeGameMenu.isShowing()) activeGameMenu.dismiss(); }
            catch (IllegalArgumentException ignored) { }
            activeGameMenu = null;
        }
        super.onDetachedFromWindow();
    }
}
