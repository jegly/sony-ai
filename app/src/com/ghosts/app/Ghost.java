package com.ghosts.app;

/**
 * Turns people into glowing white figures, given the engine's person mask (0..255 per pixel,
 * at the model's small size). Works on rows of the full-size copy, stretching the mask and a
 * blurred glow made from it, so the copy never needs more than a strip in memory.
 *
 * Plain Java (no Android classes) so it can be checked on a computer.
 */
public class Ghost {
    /** Glow levels: 0 off, 1 soft, 2 strong. */
    public static final float[] GLOW = {0f, 0.5f, 0.85f};

    private final int mw, mh;
    private final float[] alpha; // solid white figure, soft edged
    private final float[] glow;  // light spilling out around it
    private final float glowAmount;

    public Ghost(byte[] mask, int mw, int mh, int glowLevel) {
        this.mw = mw;
        this.mh = mh;
        alpha = new float[mw * mh];
        for (int i = 0; i < alpha.length; i++) {
            float p = (mask[i] & 255) / 255f;
            // firm edge around the model's 50% line
            float a = (p - 0.35f) / 0.3f;
            alpha[i] = a <= 0 ? 0 : a >= 1 ? 1 : a * a * (3 - 2 * a);
        }
        glowAmount = GLOW[Math.max(0, Math.min(GLOW.length - 1, glowLevel))];
        glow = alpha.clone();
        if (glowAmount > 0) {
            int r = Math.max(1, Math.round(mw / 110f));
            for (int pass = 0; pass < 3; pass++) blur(glow, mw, mh, r); // three box blurs ~ a Gaussian
        }
    }

    /** How much of the frame the figures cover, 0..1 (for the log). */
    public float coverage() {
        double s = 0;
        for (float a : alpha) s += a;
        return (float) (s / alpha.length);
    }

    private int[] x0, x1;
    private float[] fx;
    private int forWidth = -1;

    /** Applies the effect to `count` rows of packed RGB starting at row y of a w x h copy. */
    public void apply(int[] rgb, int y, int count, int w, int h) {
        if (forWidth != w) {
            x0 = new int[w];
            x1 = new int[w];
            fx = new float[w];
            for (int x = 0; x < w; x++) {
                float sx = (x + 0.5f) * mw / w - 0.5f;
                if (sx < 0) sx = 0;
                int i = (int) sx;
                if (i > mw - 1) i = mw - 1;
                x0[x] = i;
                x1[x] = Math.min(mw - 1, i + 1);
                fx[x] = sx - i;
            }
            forWidth = w;
        }
        for (int row = 0; row < count; row++) {
            float sy = (y + row + 0.5f) * mh / h - 0.5f;
            if (sy < 0) sy = 0;
            int iy = Math.min(mh - 1, (int) sy);
            int iy2 = Math.min(mh - 1, iy + 1);
            float wy = sy - iy;
            int r0 = iy * mw, r1 = iy2 * mw;
            int base = row * w;
            for (int x = 0; x < w; x++) {
                int a0 = r0 + x0[x], a1 = r0 + x1[x], b0 = r1 + x0[x], b1 = r1 + x1[x];
                float wx = fx[x];
                float g = glowAmount == 0 ? 0 : lerp2(glow, a0, a1, b0, b1, wx, wy) * glowAmount;
                float s = lerp2(alpha, a0, a1, b0, b1, wx, wy);
                if (g < 0.002f && s < 0.002f) continue;
                int c = rgb[base + x];
                float r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
                // screen the glow over the photo, then lay the solid figure on top
                r = 255 - (255 - r) * (1 - g);
                gg = 255 - (255 - gg) * (1 - g);
                b = 255 - (255 - b) * (1 - g);
                r += (255 - r) * s;
                gg += (255 - gg) * s;
                b += (255 - b) * s;
                rgb[base + x] = (c & 0xFF000000) | (clamp(r) << 16) | (clamp(gg) << 8) | clamp(b);
            }
        }
    }

    private static float lerp2(float[] m, int a0, int a1, int b0, int b1, float wx, float wy) {
        float top = m[a0] + (m[a1] - m[a0]) * wx;
        float bot = m[b0] + (m[b1] - m[b0]) * wx;
        return top + (bot - top) * wy;
    }

    private static int clamp(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }

    /** Box blur of radius r, horizontally then vertically, edges clamped. */
    private static void blur(float[] m, int w, int h, int r) {
        float[] tmp = new float[Math.max(w, h)];
        float norm = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int off = y * w;
            float sum = 0;
            for (int i = -r; i <= r; i++) sum += m[off + clampI(i, w)];
            for (int x = 0; x < w; x++) {
                tmp[x] = sum * norm;
                sum += m[off + clampI(x + r + 1, w)] - m[off + clampI(x - r, w)];
            }
            System.arraycopy(tmp, 0, m, off, w);
        }
        for (int x = 0; x < w; x++) {
            float sum = 0;
            for (int i = -r; i <= r; i++) sum += m[clampI(i, h) * w + x];
            for (int y = 0; y < h; y++) {
                tmp[y] = sum * norm;
                sum += m[clampI(y + r + 1, h) * w + x] - m[clampI(y - r, h) * w + x];
            }
            for (int y = 0; y < h; y++) m[y * w + x] = tmp[y];
        }
    }

    private static int clampI(int i, int n) {
        return i < 0 ? 0 : i >= n ? n - 1 : i;
    }
}
