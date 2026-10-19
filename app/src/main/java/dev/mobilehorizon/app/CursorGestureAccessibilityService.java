package dev.mobilehorizon.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/** Opt-in bridge that sends a real Android tap for virtual-cursor clicks. */
public final class CursorGestureAccessibilityService extends AccessibilityService {
    static final String ACTION_INJECT_TAP = "dev.mobilehorizon.app.action.INJECT_CURSOR_TAP";
    static final String ACTION_TAP_FINISHED = "dev.mobilehorizon.app.action.CURSOR_TAP_FINISHED";
    static final String EXTRA_X = "screen_x";
    static final String EXTRA_Y = "screen_y";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;

    private final BroadcastReceiver injectionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_INJECT_TAP.equals(intent.getAction())) return;
            float x = intent.getFloatExtra(EXTRA_X, -1f);
            float y = intent.getFloatExtra(EXTRA_Y, -1f);
            if (x < 0f || y < 0f) {
                announceFinished(false);
                return;
            }
            Path tap = new Path();
            tap.moveTo(x, y);
            tap.lineTo(x + 0.1f, y + 0.1f);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(tap, 0, 70))
                    .build();
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription description) {
                    announceFinished(true);
                }
                @Override public void onCancelled(GestureDescription description) {
                    announceFinished(false);
                }
            }, mainHandler);
            if (!accepted) announceFinished(false);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_INJECT_TAP);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(injectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(injectionReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deliberately do not inspect window text, node trees, or user input.
    }

    @Override public void onInterrupt() {
        // No ongoing observation is active to interrupt.
    }

    @Override public boolean onUnbind(Intent intent) {
        if (receiverRegistered) {
            try { unregisterReceiver(injectionReceiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
        return super.onUnbind(intent);
    }

    private void announceFinished(boolean success) {
        Intent result = new Intent(ACTION_TAP_FINISHED)
                .setPackage(getPackageName())
                .putExtra("success", success);
        sendBroadcast(result);
    }
}
