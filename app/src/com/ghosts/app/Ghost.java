package com.ghosts.app;

/**
 * Turns people into glowing white figures, given the engine's person mask (0..255 per pixel,
 * at the model's small size). Works on rows of the full-size copy, stretching small maps made from
 * the mask (and, for the Dreamy look, from a small copy of the photo), so the copy never needs more
 * than a strip in memory.
 *
 * Looks:
 *   Solid    - opaque white figures with a soft glow.
 *   Ethereal - feathered, slightly see-through figures with a tight glow and a wide halo of light.
 *   Dreamy   - Ethereal in a hazy scene: lifted blacks, a soft bloom on bright areas, gentler colour.
 *
 * Plain Java (no Android classes) so it can be checked on a computer.
 */
public class Ghost {
    public static final int SOLID = 0, ETHEREAL = 1, DREAMY = 2;
    public static final String[] LOOKS = {"Solid", "Ethereal", "Dreamy"};
    /** Glow levels: 0 off, 1 soft, 2 strong. */
    public static final float[] GLOW = {0f, 0.6f, 1f};

    private final int mw, mh;
    private final int look;
    private final float[] figure;   // the figure's shape, 0..1 (feathered for Ethereal and Dreamy)
    private final float opacity;
    private final float[] nearGlow; // light hugging the figures
    private final float[] farGlow;  // wide halo (Ethereal, Dreamy)
    private final float[] bloomR, bloomG, bloomB; // soft bloom of the whole scene (Dreamy)
    private final float nearAmount, farAmount;

    // tint of the wide halo: a faint cool white
    private static final float FAR_R = 0.93f, FAR_G = 0.96f, FAR_B = 1f;
    // Dreamy scene: black lift (0..255), bloom strength, saturation kept
    private static final float LIFT = 22f, BLOOM = 0.5f, SATURATION = 0.78f;

    /**
     * @param mask    person mask, mw x mh, same orientation as the copy
     * @param opacity how solid the figures are, 0..1 (1 = pure white, lower lets the person show through)
     * @param preview the photo at mw x mh as packed RGB (only used by Dreamy; may be null)
     */
    public Ghost(byte[] mask, int mw, int mh, int glowLevel, int look, float opacity, int[] preview) {
        this.mw = mw;
        this.mh = mh;
        this.look = Math.max(SOLID, Math.min(DREAMY, look));
        this.opacity = Math.max(0, Math.min(1, opacity));
        float glow = GLOW[Math.max(0, Math.min(GLOW.length - 1, glowLevel))];
        float[] alpha = new float[mw * mh];
        for (int i = 0; i < alpha.length; i++) {
            float p = (mask[i] & 255) / 255f;
            // firm edge around the model's 50% line
            float a = (p - 0.35f) / 0.3f;
            alpha[i] = a <= 0 ? 0 : a >= 1 ? 1 : a * a * (3 - 2 * a);
        }
        if (this.look == SOLID) {
            figure = alpha;
            nearGlow = alpha.clone();
            gaussian(nearGlow, mw, mh, Math.max(1, Math.round(mw / 110f)));
            nearAmount = 0.85f * glow;
            farGlow = null;
            farAmount = 0;
        } else {
            // feathered edge
            figure = alpha.clone();
            gaussian(figure, mw, mh, Math.max(1, Math.round(mw / 240f)));
            nearGlow = alpha.clone();
            gaussian(nearGlow, mw, mh, Math.max(1, Math.round(mw / 80f)));
            nearAmount = 0.75f * glow;
            farGlow = alpha.clone();
            gaussian(farGlow, mw, mh, Math.max(2, Math.round(mw / 26f)));
            farAmount = 0.7f * glow;
        }
        if (this.look == DREAMY && preview != null) {
            // bloom: the brighter parts of the scene, blurred wide
            bloomR = new float[mw * mh];
            bloomG = new float[mw * mh];
            bloomB = new float[mw * mh];
            for (int i = 0; i < preview.length && i < bloomR.length; i++) {
                int c = preview[i];
                float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                float k = Math.max(0, (lum - 0.35f) / 0.65f);
                k *= k;
                bloomR[i] = r * k;
                bloomG[i] = g * k;
                bloomB[i] = b * k;
            }
            int r = Math.max(2, Math.round(mw / 40f));
            gaussian(bloomR, mw, mh, r);
            gaussian(bloomG, mw, mh, r);
            gaussian(bloomB, mw, mh, r);
        } else {
            bloomR = bloomG = bloomB = null;
        }
    }

    /** How much of the frame the figures cover, 0..1 (for the log). */
    public float coverage() {
        double s = 0, max = 0;
        for (float a : figure) {
            s += a;
            if (a > max) max = a;
        }
        return max > 0 ? (float) (s / max / figure.length) : 0;
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
        boolean dreamy = bloomR != null;
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
                float shape = lerp2(figure, a0, a1, b0, b1, wx, wy);
                float s = shape * opacity;
                // the glow spills outward from the figures; inside them, opacity decides how white they are
                float out = 1 - shape;
                float g = nearAmount == 0 ? 0 : lerp2(nearGlow, a0, a1, b0, b1, wx, wy) * nearAmount * out;
                float f = farGlow == null || farAmount == 0 ? 0 : lerp2(farGlow, a0, a1, b0, b1, wx, wy) * farAmount * out;
                if (!dreamy && g < 0.002f && s < 0.002f && f < 0.002f) continue;
                int c = rgb[base + x];
                float r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
                if (dreamy) {
                    // softer colour, lifted blacks, then the scene's own light blooming over it
                    float lum = 0.299f * r + 0.587f * gg + 0.114f * b;
                    r = lum + (r - lum) * SATURATION;
                    gg = lum + (gg - lum) * SATURATION;
                    b = lum + (b - lum) * SATURATION;
                    r = LIFT + r * (1 - LIFT / 255f);
                    gg = LIFT + gg * (1 - LIFT / 255f);
                    b = LIFT + b * (1 - LIFT / 255f);
                    r = screen(r, lerp2(bloomR, a0, a1, b0, b1, wx, wy) * BLOOM);
                    gg = screen(gg, lerp2(bloomG, a0, a1, b0, b1, wx, wy) * BLOOM);
                    b = screen(b, lerp2(bloomB, a0, a1, b0, b1, wx, wy) * BLOOM);
                }
                // wide halo, tight glow, then the figure itself, each screened over what's below
                if (f > 0) {
                    r = screen(r, f * FAR_R);
                    gg = screen(gg, f * FAR_G);
                    b = screen(b, f * FAR_B);
                }
                if (g > 0) {
                    r = screen(r, g);
                    gg = screen(gg, g);
                    b = screen(b, g);
                }
                r = screen(r, s);
                gg = screen(gg, s);
                b = screen(b, s);
                rgb[base + x] = (c & 0xFF000000) | (clamp(r) << 16) | (clamp(gg) << 8) | clamp(b);
            }
        }
    }

    /** Screen blend of white light at strength k (0..1) over a 0..255 value. */
    private static float screen(float v, float k) {
        if (k > 1) k = 1;
        return 255 - (255 - v) * (1 - k);
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

    /** Three box blurs of radius r, close to a Gaussian. */
    private static void gaussian(float[] m, int w, int h, int r) {
        for (int pass = 0; pass < 3; pass++) blur(m, w, h, r);
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
