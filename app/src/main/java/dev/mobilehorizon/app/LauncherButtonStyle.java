package dev.mobilehorizon.app;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;

import java.util.WeakHashMap;

/** Shared small/medium/large button sizing for every launcher Activity. */
final class LauncherButtonStyle {
    static final String KEEP_SIZE_TAG = "dev.mobilehorizon.app.KEEP_BUTTON_SIZE";
    private static final int SMALL_DP = 34;
    private static final int MEDIUM_DP = 42;
    private static final int LARGE_DP = 48;
    private static final WeakHashMap<View, Boolean> WATCHED_DECOR = new WeakHashMap<>();

    private LauncherButtonStyle() { }

    static void keepOriginalSize(Button button) {
        if (button != null) button.setTag(KEEP_SIZE_TAG);
    }

    static void install(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        View decor = activity.getWindow().getDecorView();
        synchronized (WATCHED_DECOR) {
            if (WATCHED_DECOR.containsKey(decor)) {
                normalizeTree(decor, activity.getResources().getDisplayMetrics().density);
                return;
            }
            WATCHED_DECOR.put(decor, Boolean.TRUE);
        }
        final float density = activity.getResources().getDisplayMetrics().density;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(() -> normalizeTree(decor, density));
        decor.post(() -> normalizeTree(decor, density));
    }

    private static void normalizeTree(View view, float density) {
        if (view instanceof Button) {
            Button button = (Button) view;
            if (!KEEP_SIZE_TAG.equals(button.getTag())) {
                ViewGroup.LayoutParams params = button.getLayoutParams();
                int currentPx = params == null ? 0 : params.height;
                if (currentPx <= 0) currentPx = button.getHeight();
                if (currentPx <= 0) currentPx = Math.round(MEDIUM_DP * density);
                int currentDp = Math.round(currentPx / Math.max(0.1f, density));
                int targetDp = currentDp <= 38 ? SMALL_DP
                        : (currentDp <= 46 ? MEDIUM_DP : LARGE_DP);
                int targetPx = Math.round(targetDp * density);
                if (params != null && params.height != targetPx) {
                    params.height = targetPx;
                    button.setLayoutParams(params);
                }
                if (button.getMinHeight() != 0) button.setMinHeight(0);
                if (button.getMinimumHeight() != 0) button.setMinimumHeight(0);
                float wantedTextPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 12f,
                        button.getResources().getDisplayMetrics());
                if (Math.abs(button.getTextSize() - wantedTextPx) > 0.5f) {
                    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                normalizeTree(group.getChildAt(i), density);
            }
        }
    }
}
