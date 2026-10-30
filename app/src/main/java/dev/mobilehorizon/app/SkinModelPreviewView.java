package dev.mobilehorizon.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight, touch-rotatable Minecraft skin mannequin.
 *
 * Every cuboid is transformed by the same yaw/pitch pair, so the avatar turns
 * as one model. Classic and slim variants differ only in arm geometry.
 */
final class SkinModelPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, Bitmap> tiles = new HashMap<>();

    private Bitmap skin;
    private boolean slim;
    private float yaw = -0.48f;
    private float pitch = 0.08f;
    private float lastX;
    private float lastY;
    private boolean dragging;

    SkinModelPreviewView(Context context) {
        super(context);
        setContentDescription("Prévia 3D interativa da skin. Arraste para girar o modelo.");
        // drawBitmapMesh has consistent pixel sampling on the software canvas.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        paint.setFilterBitmap(false);
        floorPaint.setColor(0x44000000);
        textPaint.setColor(0xffb9b3d1);
        textPaint.setTextSize(dp(12));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setSkin(Bitmap bitmap, String variant) {
        if (bitmap != null && (bitmap.getWidth() < 64 || bitmap.getHeight() < 64)) {
            skin = null;
        } else {
            skin = bitmap;
        }
        slim = "SLIM".equalsIgnoreCase(variant) || "ALEX".equalsIgnoreCase(variant);
        tiles.clear();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff100f1a);
        float width = getWidth();
        float height = getHeight();
        if (skin == null || skin.isRecycled() || skin.getWidth() < 64 || skin.getHeight() < 64) {
            canvas.drawText("A prévia 3D da skin aparecerá aqui", width * 0.5f,
                    height * 0.5f, textPaint);
            return;
        }

        float scale = Math.min(width / 28.0f, height / 39.0f);
        if (scale <= 0f) return;
        float centerX = width * 0.5f;
        float centerY = height * 0.55f;
        canvas.drawOval(centerX - scale * 9.5f, centerY + scale * 15.0f,
                centerX + scale * 9.5f, centerY + scale * 17.2f, floorPaint);

        List<Face> faces = new ArrayList<>(84);
        float armWidth = slim ? 3.0f : 4.0f;

        // Base model: head, torso, two arms and two legs.
        addBox(faces, 0f, 16f, 0f, 8f, 8f, 8f, HEAD_BASE, false);
        addBox(faces, 0f, 6f, 0f, 8f, 12f, 4f, BODY_BASE, false);
        addBox(faces, -6f, 6f, 0f, armWidth, 12f, 4f, RIGHT_ARM_BASE, false);
        addBox(faces, 6f, 6f, 0f, armWidth, 12f, 4f, LEFT_ARM_BASE, false);
        addBox(faces, -2f, -6f, 0f, 4f, 12f, 4f, RIGHT_LEG_BASE, false);
        addBox(faces, 2f, -6f, 0f, 4f, 12f, 4f, LEFT_LEG_BASE, false);

        // Transparent second layer (hat, jacket, sleeves and trousers).
        addBox(faces, 0f, 16f, 0f, 8.45f, 8.45f, 8.45f, HEAD_OVERLAY, true);
        addBox(faces, 0f, 6f, 0f, 8.42f, 12.42f, 4.42f, BODY_OVERLAY, true);
        addBox(faces, -6f, 6f, 0f, armWidth + 0.38f, 12.42f, 4.42f, RIGHT_ARM_OVERLAY, true);
        addBox(faces, 6f, 6f, 0f, armWidth + 0.38f, 12.42f, 4.42f, LEFT_ARM_OVERLAY, true);
        addBox(faces, -2f, -6f, 0f, 4.38f, 12.42f, 4.38f, RIGHT_LEG_OVERLAY, true);
        addBox(faces, 2f, -6f, 0f, 4.38f, 12.42f, 4.38f, LEFT_LEG_OVERLAY, true);

        final float cameraDistance = 58.0f;
        final float focalLength = cameraDistance * scale;
        for (Face face : faces) {
            project(face, centerX, centerY, focalLength, cameraDistance);
        }
        Collections.sort(faces, Comparator.comparingDouble(face -> face.depth));
        for (Face face : faces) {
            Bitmap tile = tile(face.u, face.v, face.uvWidth, face.uvHeight);
            if (tile == null) continue;
            int tone = face.shade;
            paint.setColor(Color.rgb(tone, tone, tone));
            paint.setAlpha(face.overlay ? 240 : 255);
            canvas.drawBitmapMesh(tile, 1, 1, face.vertices, 0, null, 0, paint);
        }
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);

        if (!dragging) {
            yaw += 0.009f;
            postInvalidateDelayed(33L);
        }
    }

    private Bitmap tile(int x, int y, int width, int height) {
        String key = x + ":" + y + ":" + width + ":" + height;
        Bitmap result = tiles.get(key);
        if (result != null && !result.isRecycled()) return result;
        if (skin == null || x < 0 || y < 0 || width <= 0 || height <= 0
                || x + width > skin.getWidth() || y + height > skin.getHeight()) return null;
        try {
            result = Bitmap.createBitmap(skin, x, y, width, height);
            tiles.put(key, result);
            return result;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private void addBox(List<Face> out, float cx, float cy, float cz,
                        float width, float height, float depth,
                        int[] uv, boolean overlay) {
        float x0 = cx - width * 0.5f;
        float x1 = cx + width * 0.5f;
        float y0 = cy - height * 0.5f;
        float y1 = cy + height * 0.5f;
        float z0 = cz - depth * 0.5f;
        float z1 = cz + depth * 0.5f;
        addFace(out, x0,y1,z1, x1,y1,z1, x0,y1,z0, x1,y1,z0, uv, 0, 242, overlay);
        addFace(out, x0,y0,z0, x1,y0,z0, x0,y0,z1, x1,y0,z1, uv, 1, 166, overlay);
        addFace(out, x0,y1,z1, x1,y1,z1, x0,y0,z1, x1,y0,z1, uv, 2, 255, overlay);
        addFace(out, x0,y1,z0, x0,y1,z1, x0,y0,z0, x0,y0,z1, uv, 3, 198, overlay);
        addFace(out, x1,y1,z1, x1,y1,z0, x1,y0,z1, x1,y0,z0, uv, 4, 214, overlay);
        addFace(out, x1,y1,z0, x0,y1,z0, x1,y0,z0, x0,y0,z0, uv, 5, 184, overlay);
    }

    private void addFace(List<Face> out,
                         float x0,float y0,float z0, float x1,float y1,float z1,
                         float x2,float y2,float z2, float x3,float y3,float z3,
                         int[] uv, int faceIndex, int shade, boolean overlay) {
        int offset = faceIndex * 4;
        Face face = new Face();
        face.points = new float[]{x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3};
        face.u = uv[offset];
        face.v = uv[offset + 1];
        face.uvWidth = uv[offset + 2];
        face.uvHeight = uv[offset + 3];
        face.shade = shade;
        face.overlay = overlay;
        out.add(face);
    }

    private void project(Face face, float centerX, float centerY,
                         float focalLength, float cameraDistance) {
        face.vertices = new float[8];
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        face.depth = 0f;
        for (int i = 0; i < 4; i++) {
            float x = face.points[i * 3];
            float y = face.points[i * 3 + 1];
            float z = face.points[i * 3 + 2];
            double rotatedX = x * cosYaw + z * sinYaw;
            double rotatedZ = -x * sinYaw + z * cosYaw;
            double rotatedY = y * cosPitch - rotatedZ * sinPitch;
            double cameraZ = y * sinPitch + rotatedZ * cosPitch;
            double denominator = Math.max(10.0, cameraDistance - cameraZ);
            double perspective = focalLength / denominator;
            face.vertices[i * 2] = (float) (centerX + rotatedX * perspective);
            face.vertices[i * 2 + 1] = (float) (centerY - rotatedY * perspective);
            face.depth += (float) cameraZ * 0.25f;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                yaw += dx * 0.012f;
                pitch = Math.max(-0.42f, Math.min(0.42f, pitch + dy * 0.006f));
                lastX = event.getX();
                lastY = event.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                dragging = false;
                performClick();
                postInvalidateDelayed(33L);
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final int[] HEAD_BASE = {
            8,0,8,8, 16,0,8,8, 8,8,8,8, 0,8,8,8, 16,8,8,8, 24,8,8,8
    };
    private static final int[] HEAD_OVERLAY = {
            40,0,8,8, 48,0,8,8, 40,8,8,8, 32,8,8,8, 48,8,8,8, 56,8,8,8
    };
    private static final int[] BODY_BASE = {
            20,16,8,4, 28,16,8,4, 20,20,8,12, 16,20,4,12, 28,20,4,12, 32,20,8,12
    };
    private static final int[] BODY_OVERLAY = {
            20,32,8,4, 28,32,8,4, 20,36,8,12, 16,36,4,12, 28,36,4,12, 32,36,8,12
    };
    private static final int[] RIGHT_ARM_BASE = {
            44,16,4,4, 48,16,4,4, 44,20,4,12, 40,20,4,12, 48,20,4,12, 52,20,4,12
    };
    private static final int[] RIGHT_ARM_OVERLAY = {
            44,32,4,4, 48,32,4,4, 44,36,4,12, 40,36,4,12, 48,36,4,12, 52,36,4,12
    };
    private static final int[] LEFT_ARM_BASE = {
            36,48,4,4, 40,48,4,4, 36,52,4,12, 32,52,4,12, 40,52,4,12, 44,52,4,12
    };
    private static final int[] LEFT_ARM_OVERLAY = {
            52,48,4,4, 56,48,4,4, 52,52,4,12, 48,52,4,12, 56,52,4,12, 60,52,4,12
    };
    private static final int[] RIGHT_LEG_BASE = {
            4,16,4,4, 8,16,4,4, 4,20,4,12, 0,20,4,12, 8,20,4,12, 12,20,4,12
    };
    private static final int[] RIGHT_LEG_OVERLAY = {
            4,32,4,4, 8,32,4,4, 4,36,4,12, 0,36,4,12, 8,36,4,12, 12,36,4,12
    };
    private static final int[] LEFT_LEG_BASE = {
            20,48,4,4, 24,48,4,4, 20,52,4,12, 16,52,4,12, 24,52,4,12, 28,52,4,12
    };
    private static final int[] LEFT_LEG_OVERLAY = {
            4,48,4,4, 8,48,4,4, 4,52,4,12, 0,52,4,12, 8,52,4,12, 12,52,4,12
    };

    private static final class Face {
        float[] points;
        float[] vertices;
        float depth;
        int u;
        int v;
        int uvWidth;
        int uvHeight;
        int shade;
        boolean overlay;
    }
}
