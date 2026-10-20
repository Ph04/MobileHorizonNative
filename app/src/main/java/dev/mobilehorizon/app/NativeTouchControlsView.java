package dev.mobilehorizon.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.HashSet;
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

    private static final int NONE = 0, JOYSTICK = 1, TOGGLE = 2, JUMP = 3, SNEAK = 4,
            SPRINT = 5, INVENTORY = 6, ATTACK = 7, USE = 8, ESCAPE = 9,
            DROP = 10, SWAP = 11, PICK = 12, PERSPECTIVE = 13, CHAT = 14,
            HOTBAR_PREV = 15, HOTBAR_NEXT = 16, TAB = 17, ENTER = 18, IGNORED = 19,
            MOUSE_LEFT = 20, MOUSE_RIGHT = 21;
    private final NativeGameActivity activity;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, PointerTrack> pointers = new HashMap<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Set<Integer> pressedControls = new HashSet<>();
    private final Set<Integer> joystickKeys = new HashSet<>();
    private final Map<Integer, Integer> controlPointerCounts = new HashMap<>();

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
        PointerTrack(float x, float y, int control) {
            startX = lastX = x; startY = lastY = y; this.control = control;
        }
    }

    private static final class ButtonSpec {
        final int id; final String label; final float x, y, radius;
        ButtonSpec(int id, String label, float x, float y, float radius) {
            this.id = id; this.label = label; this.x = x; this.y = y; this.radius = radius;
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
        drawToggle(canvas);
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
        if (getWidth() <= 0 || getHeight() <= 0) return;
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
        circle(canvas, spec.x, spec.y, spec.radius, false,
                pressedControls.contains(spec.id) ? 0.98f : 0.74f);
        circle(canvas, spec.x, spec.y, spec.radius, true, 0.95f);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(spec.radius < dp(27) ? 11 : 15) * scale);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(spec.label, spec.x, spec.y - (metrics.ascent + metrics.descent) / 2f, paint);
    }

    private void drawToggle(Canvas canvas) {
        float x = mirrorControlX(getWidth() - dp(98)), y = dp(31);
        circle(canvas, x, y, dp(18), false, 0.86f);
        circle(canvas, x, y, dp(18), true, 0.95f);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(14));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(controlsVisible ? "×" : "+", x, y - (metrics.ascent + metrics.descent) / 2f, paint);
    }

    private ButtonSpec[] buttonSpecs() {
        float s = scale, w = getWidth(), h = getHeight();
        return new ButtonSpec[] {
                new ButtonSpec(MOUSE_LEFT, "LMB", mirrorControlX(w - dp(164) * s), dp(31) * s, dp(20) * s),
                new ButtonSpec(MOUSE_RIGHT, "RMB", mirrorControlX(w - dp(216) * s), dp(31) * s, dp(20) * s),
                new ButtonSpec(JUMP, "↑", mirrorControlX(w - dp(72) * s), h - dp(109) * s, dp(32) * s),
                new ButtonSpec(SNEAK, "SNEAK", mirrorControlX(w - dp(141) * s), h - dp(55) * s, dp(25) * s),
                new ButtonSpec(SPRINT, "RUN", mirrorControlX(w - dp(72) * s), h - dp(55) * s, dp(25) * s),
                new ButtonSpec(INVENTORY, "INV", mirrorControlX(w - dp(210) * s), h - dp(55) * s, dp(24) * s),
                new ButtonSpec(DROP, "Q", mirrorControlX(w - dp(210) * s), h - dp(113) * s, dp(20) * s),
                new ButtonSpec(SWAP, "F", mirrorControlX(w - dp(210) * s), h - dp(166) * s, dp(20) * s),
                new ButtonSpec(ATTACK, "HIT", mirrorControlX(w - dp(137) * s), h - dp(196) * s, dp(34) * s),
                new ButtonSpec(USE, "USE", mirrorControlX(w - dp(61) * s), h - dp(222) * s, dp(28) * s),
                new ButtonSpec(PICK, "MID", mirrorControlX(w - dp(145) * s), h - dp(265) * s, dp(22) * s),
                new ButtonSpec(PERSPECTIVE, "F5", mirrorControlX(w - dp(210) * s), h - dp(218) * s, dp(21) * s),
                new ButtonSpec(CHAT, "T", mirrorControlX(w - dp(285) * s), h - dp(55) * s, dp(21) * s),
                new ButtonSpec(HOTBAR_PREV, "‹", mirrorControlX(w - dp(285) * s), h - dp(112) * s, dp(20) * s),
                new ButtonSpec(HOTBAR_NEXT, "›", mirrorControlX(w - dp(285) * s), h - dp(166) * s, dp(20) * s),
                new ButtonSpec(TAB, "TAB", mirrorControlX(w - dp(285) * s), h - dp(218) * s, dp(22) * s),
                new ButtonSpec(ENTER, "ENT", mirrorControlX(w - dp(285) * s), h - dp(265) * s, dp(22) * s),
                new ButtonSpec(ESCAPE, "ESC", mirrorControlX(w - dp(36)), dp(31), dp(21))
        };
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
            if (!controlsVisible) releaseHeldControls();
        } else if (control == JOYSTICK && joystickPointer == -1) {
            joystickPointer = id; joystickX = x; joystickY = y;
        } else if (control == JOYSTICK) {
            // Never let a second finger on the joystick trigger mouse input.
            control = IGNORED;
        }
        pointers.put(id, new PointerTrack(x, y, control));
        if (control == JOYSTICK) updateJoystick(x, y);
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
            if (track.control == JOYSTICK) updateJoystick(x, y);
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
        if (track.control == JOYSTICK) {
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
        float toggleX = mirrorControlX(getWidth() - dp(98)), toggleY = dp(31);
        if (distanceSquared(x, y, toggleX, toggleY) <= dp(26) * dp(26)) return TOGGLE;
        if (!controlsVisible) return NONE;
        float jx = joystickHomeX(), jy = joystickHomeY(), jr = dp(82) * scale;
        if (distanceSquared(x, y, jx, jy) <= jr * jr) return JOYSTICK;
        ButtonSpec best = null; float bestDistance = Float.MAX_VALUE;
        for (ButtonSpec spec : buttonSpecs()) {
            float d = distanceSquared(x, y, spec.x, spec.y), hit = spec.radius + dp(13);
            if (d <= hit * hit && d < bestDistance) { best = spec; bestDistance = d; }
        }
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
            default: break;
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
            default: break;
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
}
