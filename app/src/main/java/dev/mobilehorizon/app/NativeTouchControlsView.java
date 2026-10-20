package dev.mobilehorizon.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
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

    private static final int NONE = 0, JOYSTICK = 1, TOGGLE = 2, JUMP = 3, SNEAK = 4,
            SPRINT = 5, INVENTORY = 6, ATTACK = 7, USE = 8, ESCAPE = 9,
            DROP = 10, SWAP = 11, PICK = 12, PERSPECTIVE = 13, CHAT = 14,
            HOTBAR_PREV = 15, HOTBAR_NEXT = 16, TAB = 17, ENTER = 18, IGNORED = 19,
            MOUSE_LEFT = 20, MOUSE_RIGHT = 21, KEYBOARD = 22, EDITOR = 23,
            ADD_BUTTON = 24, DONE_EDITING = 25, CUSTOM_BUTTON_START = 1000;
    private final NativeGameActivity activity;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private final Runnable modePoll = new Runnable() {
        @Override public void run() {
            boolean active = activity.isGameplayMode();
            if (active != gameplayMode) {
                gameplayMode = active;
                invalidate();
            }
            modeHandler.postDelayed(this, 220);
        }
    };

    private final float density;
    private final float scale;
    private final float opacity;
    private final float sensitivity;
    private final boolean leftHanded;
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
    }

    private static final class CustomButton {
        final int id;
        String label;
        int keyCode;
        float x, y, scale;
        CustomButton(int id, String label, int keyCode, float x, float y, float scale) {
            this.id = id; this.label = label; this.keyCode = keyCode;
            this.x = x; this.y = y; this.scale = scale;
        }
    }

    NativeTouchControlsView(NativeGameActivity activity) {
        super(activity);
        this.activity = activity;
        density = getResources().getDisplayMetrics().density;
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        scale = clamp(prefs.getFloat(KEY_SCALE, 1f), 0.70f, 1.30f);
        opacity = clamp(prefs.getFloat(KEY_OPACITY, 0.82f), 0.35f, 1f);
        sensitivity = clamp(prefs.getFloat(KEY_SENSITIVITY, 1f), 0.50f, 2f);
        leftHanded = prefs.getBoolean(KEY_LEFT_HANDED, false);
        loadButtonLayouts(prefs.getString(KEY_LAYOUT, ""));
        loadCustomButtons(prefs.getString(KEY_CUSTOM_BUTTONS, ""));
        gameplayMode = activity.isGameplayMode();
        modeHandler.post(modePoll);
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
            drawJoystick(canvas);
            for (ButtonSpec spec : buttonSpecs()) drawButton(canvas, spec);
        }
        drawToolbar(canvas);
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
        if (getWidth() <= 0 || getHeight() <= 0 || (gameplayMode && !editingControls)) return;
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

    private void drawButton(Canvas canvas, ButtonSpec spec) {
        boolean pressed = pressedControls.contains(spec.id);
        float r = spec.radius;
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(dp(8), r * 0.48f));
        paint.setFakeBoldText(true);
        float halfWidth = Math.max(r * 0.86f, paint.measureText(spec.label) / 2f + dp(7));
        float halfHeight = r * 0.76f;
        RectF bounds = new RectF(spec.x - halfWidth, spec.y - halfHeight,
                spec.x + halfWidth, spec.y + halfHeight);
        float corner = Math.min(dp(10), halfHeight * 0.55f);
        paint.setColor(Color.argb(Math.round(255 * opacity * (pressed ? 0.92f : 0.70f)),
                pressed ? 29 : 15, pressed ? 105 : 28, pressed ? 183 : 46));
        canvas.drawRoundRect(bounds, corner, corner, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(editingControls ? 2.2f : 1.5f));
        paint.setColor(Color.argb(Math.round(255 * opacity * (pressed ? 1f : 0.9f)),
                pressed ? 115 : 105, pressed ? 210 : 175, 255));
        canvas.drawRoundRect(bounds, corner, corner, paint);
        if (editingControls) {
            RectF selection = new RectF(bounds.left - dp(3), bounds.top - dp(3),
                    bounds.right + dp(3), bounds.bottom + dp(3));
            paint.setStrokeWidth(dp(1f));
            paint.setColor(Color.argb(210, 115, 205, 255));
            canvas.drawRoundRect(selection, corner + dp(2), corner + dp(2), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(spec.label, spec.x, spec.y - (metrics.ascent + metrics.descent) / 2f, paint);
        paint.setFakeBoldText(false);
    }

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
        paint.setColor(Color.argb(Math.round(235 * opacity), active ? 22 : 17,
                active ? 111 : 31, active ? 166 : 51));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(Color.argb(235, 118, 190, 255));
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
            specs.add(new ButtonSpec(custom.id, custom.label,
                    clamp(custom.x * w, radius, Math.max(radius, w - radius)),
                    clamp(custom.y * h, radius, Math.max(radius, h - radius)),
                    radius, true, custom.keyCode));
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
                customButtons.add(new CustomButton(id, label, keyCode,
                        clamp((float) item.optDouble("x", 0.62), 0.03f, 0.97f),
                        clamp((float) item.optDouble("y", 0.55), 0.05f, 0.95f),
                        clamp((float) item.optDouble("scale", 1), 0.60f, 2.40f)));
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

    private void setButtonPosition(int id, float x, float y) {
        float width = Math.max(1, getWidth()), height = Math.max(1, getHeight());
        float nx = clamp(x / width, 0.035f, 0.965f);
        float ny = clamp(y / height, 0.055f, 0.945f);
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
        if (!activity.hasLoadedWorld()) {
            Toast.makeText(activity, "Entre em um mundo para escrever no chat.", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("Mensagem ou comando (use /)");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Teclado do Minecraft")
                .setView(panel)
                .setNegativeButton("Cancelar", (d, which) -> { })
                .setPositiveButton("Enviar", (d, which) -> {
                    String text = input.getText().toString();
                    if (!text.trim().isEmpty()) activity.injectChatText(text);
                })
                .create();
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager manager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
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
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));
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

    private void showButtonEditor(int id) {
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
        panel.setPadding(dp(20), dp(10), dp(20), dp(4));
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
            Toast.makeText(activity, editingControls
                    ? "Modo de edição: arraste um botão ou toque para alterar seu tamanho."
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
            saveButtonLayouts();
            saveCustomButtons();
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
                activity.injectMouseMotion(dx * sensitivity, dy * sensitivity);
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

    private void activateControl(int control, float x, float y) {
        int activePointers = controlPointerCounts.getOrDefault(control, 0);
        controlPointerCounts.put(control, activePointers + 1);
        pressedControls.add(control);
        if (activePointers > 0) {
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
        activity.injectKeyCode(keyCode, held);
    }

    private void setMouseButton(int button, boolean held, float x, float y) {
        int previous = mouseButtons;
        if (held) mouseButtons |= button; else mouseButtons &= ~button;
        if (previous == mouseButtons) return;
        activity.injectMouseButton(button, held, x, y);
    }

    private void sendMouseMotion(float x, float y) {
        activity.injectCursorMove(x, y);
    }

    private void sendMouseScroll(float amount) {
        activity.injectMouseScroll(amount, virtualMouseX, virtualMouseY);
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
