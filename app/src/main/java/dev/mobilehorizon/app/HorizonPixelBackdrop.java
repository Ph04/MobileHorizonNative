package dev.mobilehorizon.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Android port of Horizon desktop launcher's procedural pixel landscape (launcher/src/art.rs).
 * The same fixed seed, sky palettes, sun bands, cloud rhythm, ridges and square trees are used;
 * the image is baked into texels and scaled without filtering so it stays pixel-sharp on phones.
 */
final class HorizonPixelBackdrop extends View {
    private static final int SCENE_HEIGHT = 56;
    private static final int SCENE_STEP = 64;

    private static final Palette SUNSET = new Palette(
            new int[]{0x0a0f2c, 0x26206a, 0x4b2a7e, 0x7c3a88, 0xc4527a, 0xee6e5a, 0xffb25e},
            new int[]{0xfff6cf, 0xffd272, 0xff9a44},
            0x4a2f7e, 0x2b1f5e, 0x151a45, 0x3d2f80, 0x0e1236);
    private static final Palette DAWN = new Palette(
            new int[]{0x8d7fd8, 0xa596e2, 0xbcaeea, 0xd2c4f0, 0xe7d2ee, 0xf6dce4, 0xffe6d6},
            new int[]{0xffffff, 0xfff1d0, 0xffd9a8},
            0xa898dc, 0x8676c8, 0x6a5ab4, 0x9a8ae0, 0x4c3e94);

    private final Paint bitmapPaint = new Paint();
    private final Paint veilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap sceneBitmap;
    private int sceneTexelWidth;
    private boolean lightAppearance;

    HorizonPixelBackdrop(Context context) {
        super(context);
        bitmapPaint.setFilterBitmap(false);
        bitmapPaint.setDither(false);
        bitmapPaint.setAntiAlias(false);
        setWillNotDraw(false);
    }

    void setLightAppearance(boolean light) {
        if (lightAppearance != light) {
            lightAppearance = light;
            sceneBitmap = null;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ensureScene(w, h);
    }

    private void ensureScene(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return;
        float texelPx = heightPx / (float) SCENE_HEIGHT;
        int needed = Math.max(1, (int) Math.ceil(widthPx / texelPx));
        int rounded = ((needed + SCENE_STEP - 1) / SCENE_STEP) * SCENE_STEP;
        if (sceneBitmap != null && sceneTexelWidth >= rounded) return;
        Palette palette = lightAppearance ? DAWN : SUNSET;
        int[] pixels = renderScene(palette, 3, rounded, SCENE_HEIGHT);
        Bitmap next = Bitmap.createBitmap(rounded, SCENE_HEIGHT, Bitmap.Config.ARGB_8888);
        next.setPixels(pixels, 0, rounded, 0, 0, rounded, SCENE_HEIGHT);
        if (sceneBitmap != null) sceneBitmap.recycle();
        sceneBitmap = next;
        sceneTexelWidth = rounded;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureScene(w, h);
        Palette palette = lightAppearance ? DAWN : SUNSET;
        canvas.drawColor(0xff000000 | palette.sky[0]);
        if (sceneBitmap != null) {
            float texelPx = h / (float) SCENE_HEIGHT;
            float scaledWidth = sceneBitmap.getWidth() * texelPx;
            canvas.drawBitmap(sceneBitmap, null, new RectF(0, 0, scaledWidth, h), bitmapPaint);
        }

        // Port of the desktop banner's page-colour veil: enough shade behind the hero text,
        // fading toward the landscape's right edge instead of replacing the art with a screenshot.
        veilPaint.setShader(new LinearGradient(0, 0, Math.max(1, w), 0,
                new int[]{0xd90f0d17, 0x8c0f0d17, 0x120f0d17},
                new float[]{0f, 0.56f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, veilPaint);
        veilPaint.setShader(null);
    }

    private static int[] renderScene(Palette p, int seed, int w, int h) {
        Rng seeds = new Rng(seed * 0x2545f491 | 1);
        int[] out = new int[w * h];
        int horizon = h * 62 / 100;
        float band = horizon / (float) p.sky.length;

        for (int y = 0; y < h; y++) {
            float f = y / band;
            int i = Math.min((int) f, p.sky.length - 1);
            boolean dither = f - i > 0.88f && i + 1 < p.sky.length;
            for (int x = 0; x < w; x++) {
                int c = dither && ((x + y) & 1) == 1 ? p.sky[i + 1] : p.sky[i];
                out[y * w + x] = 0xff000000 | c;
            }
        }

        Rng clouds = nextRng(seeds);
        final int cloudSpan = 220;
        for (int stretch = 0; stretch < (w + cloudSpan - 1) / cloudSpan; stretch++) {
            for (int n = 0; n < 4; n++) {
                int cx = stretch * cloudSpan + (int) (clouds.next() * cloudSpan);
                int cy = (int) (horizon * (0.12f + clouds.next() * 0.4f));
                int cw = 10 + (int) (clouds.next() * 14f);
                int under = p.sky[Math.min((int) (cy / band) + 1, p.sky.length - 1)];
                int color = mix(under, 0xffffff, 0.12f);
                rect(out, w, h, cx, cy, cw, 2, color);
                rect(out, w, h, cx + 3, cy - 2, Math.max(0, cw - 6), 2, color);
            }
        }

        int r = (int) (h * 0.2f);
        // Desktop art fixes the sun at texel 149; on a phone, move it into the visible
        // two-thirds point so the signature remains present at narrower aspect ratios.
        int cx = Math.min(149, Math.max(r + 2, Math.round(w * 0.68f)));
        int cy = horizon - (int) (h * 0.11f);
        for (int dy = -r; dy <= 0; dy++) {
            int half = Math.round((float) Math.sqrt(r * r - dy * dy));
            float t = (dy + r) / (float) r;
            int sunColor = p.sun[t < 0.4f ? 0 : (t < 0.75f ? 1 : 2)];
            if ((cy + dy) % 5 != 0 || dy < -r * 0.6f) {
                rect(out, w, h, cx - half, cy + dy, half * 2, 1, sunColor);
            }
        }

        int[][] layers = new int[3][];
        float[][] ridgeSettings = {
                {horizon - h * 0.1f, h * 0.12f, 3.2f},
                {horizon + h * 0.02f, h * 0.09f, 2.6f},
                {horizon + h * 0.18f, h * 0.07f, 2.2f}
        };
        int[] layerColors = {p.far, p.mid, p.near};
        int[] near = null;
        for (int layer = 0; layer < 3; layer++) {
            int[] heights = ridge(nextRng(seeds), w, h,
                    ridgeSettings[layer][0], ridgeSettings[layer][1], ridgeSettings[layer][2]);
            for (int x = 0; x < heights.length; x++) {
                int y = Math.min(heights[x], h);
                for (int yy = y; yy < h; yy++) out[yy * w + x] = 0xff000000 | layerColors[layer];
                if (layer == 2 && y < h) out[y * w + x] = 0xff000000 | p.top;
            }
            layers[layer] = heights;
            near = heights;
        }

        Rng trees = nextRng(seeds);
        for (int cell = 0; cell < (w + 25) / 26; cell++) {
            int tx = cell * 26 + 2 + (int) (trees.next() * 22f);
            if (tx < 0 || tx >= w) break;
            int ty = near[tx];
            rect(out, w, h, tx - 2, ty - 8, 5, 5, p.trees);
            rect(out, w, h, tx - 1, ty - 10, 3, 2, p.trees);
            rect(out, w, h, tx, ty - 3, 1, 3, p.trees);
        }
        return out;
    }

    private static int[] ridge(Rng rng, int w, int h, float base, float amp, float rough) {
        float y = base;
        float v = 0f;
        int[] heights = new int[w];
        for (int x = 0; x < w; x++) {
            if ((x & 1) == 0) {
                v = (v + (rng.next() - 0.5f) * rough) * 0.86f;
                y += v + (base - y) * 0.06f;
            }
            heights[x] = Math.max(0, Math.round(Math.max(base - amp, Math.min(base + amp, y))));
        }
        return heights;
    }

    private static Rng nextRng(Rng seeds) {
        seeds.next();
        return new Rng(seeds.state);
    }

    private static void rect(int[] out, int w, int h, int x, int y,
                             int width, int height, int rgb) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(w, x + Math.max(0, width));
        int bottom = Math.min(h, y + Math.max(0, height));
        int color = 0xff000000 | rgb;
        for (int yy = top; yy < bottom; yy++) {
            int row = yy * w;
            for (int xx = left; xx < right; xx++) out[row + xx] = color;
        }
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static final class Palette {
        final int[] sky;
        final int[] sun;
        final int far, mid, near, top, trees;
        Palette(int[] sky, int[] sun, int far, int mid, int near, int top, int trees) {
            this.sky = sky;
            this.sun = sun;
            this.far = far;
            this.mid = mid;
            this.near = near;
            this.top = top;
            this.trees = trees;
        }
    }

    private static final class Rng {
        int state;
        Rng(int state) { this.state = state; }
        float next() {
            int x = state;
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            state = x;
            return (x >>> 8) / 16777216f;
        }
    }
}
