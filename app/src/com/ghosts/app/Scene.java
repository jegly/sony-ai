package com.ghosts.app;

/**
 * Effects that use the subject mask to treat the subjects and the rest of the scene differently:
 *   Colour pop - subjects in colour, the rest black and white.
 *   Bokeh      - the background thrown out of focus behind the subjects, highlights opening into soft discs.
 *   Spotlight  - the background falls into darkness, the subjects stay lit, with light spilling round them.
 *   Glitch     - subjects broken up digitally: colour channels pulled apart and slices knocked sideways.
 *   Thermal    - a heat camera: subjects glow hot, the scene stays cold.
 *   Halftone   - subjects printed as a grid of dots.
 *
 * Like the other looks it works on strips of the copy, from maps at the mask's small size (and, for Bokeh,
 * a blurred small copy of the photo). Plain Java (no Android classes) so it can be checked on a computer.
 */
public class Scene {
    public static final int COLOUR_POP = 0, BOKEH = 1, SPOTLIGHT = 2, GLITCH = 3, THERMAL = 4, HALFTONE = 5;

    private final int kind, mw, mh;
    private final float amount;
    private final float[] alpha; // subjects, 0..1, feathered a little
    private final float[] spill; // Spotlight: light spilling out round the subjects; Thermal: the body's warm core
    private final float[] bgR, bgG, bgB; // Bokeh: the blurred background, linear light
    private final float cr, cg, cb;
    private final int seed;

    private static final float[] TO_LINEAR = new float[256];
    private static final byte[] TO_SRGB = new byte[4097];

    static {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            TO_LINEAR[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i <= 4096; i++) {
            double v = i / 4096.0;
            double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            TO_SRGB[i] = (byte) Math.max(0, Math.min(255, Math.round(s * 255)));
        }
    }

    /**
     * @param mask    subject mask, mw x mh, same orientation as the copy
     * @param preview the photo at mw x mh as packed RGB (used by Bokeh)
     * @param amount  strength, 0..1
     */
    public Scene(int kind, byte[] mask, int mw, int mh, int[] preview, float amount, float[] colour, int seed) {
        this.kind = kind;
        this.mw = mw;
        this.mh = mh;
        this.amount = amount;
        this.seed = seed;
        cr = colour[0];
        cg = colour[1];
        cb = colour[2];
        int n = mw * mh;
        alpha = new float[n];
        for (int i = 0; i < n; i++) {
            float p = (mask[i] & 255) / 255f;
            float a = (p - 0.35f) / 0.3f;
            alpha[i] = a <= 0 ? 0 : a >= 1 ? 1 : a * a * (3 - 2 * a);
        }
        Ghost.gaussian(alpha, mw, mh, Math.max(1, Math.round(mw / 400f)));
        if (kind == SPOTLIGHT) {
            spill = alpha.clone();
            Ghost.gaussian(spill, mw, mh, Math.max(2, Math.round(mw / 14f)));
        } else if (kind == THERMAL) {
            spill = alpha.clone();
            Ghost.gaussian(spill, mw, mh, Math.max(2, Math.round(mw / 70f)));
            for (int i = 0; i < n; i++) spill[i] = Math.min(1, spill[i] * spill[i] * 1.6f);
        } else {
            spill = null;
        }
        if (kind == BOKEH && preview != null) {
            // normalised blur of the background alone, so the subjects don't bleed into it
            bgR = new float[n];
            bgG = new float[n];
            bgB = new float[n];
            float[] wgt = new float[n];
            for (int i = 0; i < n; i++) {
                int c = preview[i];
                float lr = TO_LINEAR[(c >> 16) & 255], lg = TO_LINEAR[(c >> 8) & 255], lb = TO_LINEAR[c & 255];
                // bright spots get extra light, so they open up into discs like a real lens
                float mx = Math.max(lr, Math.max(lg, lb));
                float boost = 1 + 6 * smooth(0.6f, 1f, mx);
                float bw = 1 - Math.min(1, alpha[i] * 1.5f);
                bgR[i] = lr * boost * bw;
                bgG[i] = lg * boost * bw;
                bgB[i] = lb * boost * bw;
                wgt[i] = bw;
            }
            int r = Math.max(1, Math.round(mw * (0.004f + 0.016f * amount)));
            for (float[] m : new float[][] {bgR, bgG, bgB, wgt}) Ghost.gaussian(m, mw, mh, r);
            for (int i = 0; i < n; i++) {
                float k = wgt[i] > 1e-4f ? 1 / wgt[i] : 0;
                bgR[i] *= k;
                bgG[i] *= k;
                bgB[i] *= k;
            }
        } else {
            bgR = bgG = bgB = null;
        }
    }

    private int[] x0, x1;
    private float[] fx;
    private int forWidth = -1;
    private int[] src = new int[0];

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
        if (kind == GLITCH) {
            if (src.length < w * count) src = new int[w * count];
            System.arraycopy(rgb, 0, src, 0, w * count);
        }
        float cell = Math.max(5, w / 75f); // halftone cell, copy pixels
        int split = Math.max(2, Math.round(w * 0.009f * (0.4f + amount)));
        for (int row = 0; row < count; row++) {
            int yy = y + row;
            float sy = (yy + 0.5f) * mh / h - 0.5f;
            if (sy < 0) sy = 0;
            int iy = Math.min(mh - 1, (int) sy);
            int iy2 = Math.min(mh - 1, iy + 1);
            float wy = sy - iy;
            int r0 = iy * mw, r1 = iy2 * mw;
            int base = row * w;
            // glitch: rows fall into bands, some knocked sideways
            int shift = 0;
            boolean scan = false, swap = false;
            if (kind == GLITCH) {
                // bands of uneven height, about half of them knocked sideways, a few with colours swapped
                int band = (yy + (int) (hash(yy / Math.max(2, h / 40), seed + 3) * h / 60)) / Math.max(2, h / 70);
                float hb = hash(band, seed);
                if (hb > 0.5f) shift = Math.round((hash(band, seed + 7) - 0.5f) * w * 0.12f * (0.3f + amount));
                swap = hb > 0.92f;
                scan = yy % 3 == 0;
            }
            for (int x = 0; x < w; x++) {
                int a0 = r0 + x0[x], a1 = r0 + x1[x], b0 = r1 + x0[x], b1 = r1 + x1[x];
                float wx = fx[x];
                float a = lerp2(alpha, a0, a1, b0, b1, wx, wy);
                int c = rgb[base + x];
                float r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                switch (kind) {
                    case COLOUR_POP: {
                        float k = (1 - a) * amount; // how grey
                        r += (lum - r) * k;
                        g += (lum - g) * k;
                        b += (lum - b) * k;
                        break;
                    }
                    case BOKEH: {
                        if (bgR == null || a > 0.999f) continue;
                        float br = lerp2(bgR, a0, a1, b0, b1, wx, wy), bg = lerp2(bgG, a0, a1, b0, b1, wx, wy);
                        float bb = lerp2(bgB, a0, a1, b0, b1, wx, wy);
                        // tone the boosted highlights back into range, gently
                        br = br / (1 + br * 0.15f);
                        bg = bg / (1 + bg * 0.15f);
                        bb = bb / (1 + bb * 0.15f);
                        float lr = TO_LINEAR[(int) r] * a + br * (1 - a), lg = TO_LINEAR[(int) g] * a + bg * (1 - a);
                        float lb = TO_LINEAR[(int) b] * a + bb * (1 - a);
                        r = srgb(lr);
                        g = srgb(lg);
                        b = srgb(lb);
                        break;
                    }
                    case SPOTLIGHT: {
                        float l = Math.max(a, lerp2(spill, a0, a1, b0, b1, wx, wy) * 0.75f);
                        float k = 1 - amount * 0.9f * (1 - l);
                        r *= k;
                        g *= k;
                        b *= k;
                        break;
                    }
                    case GLITCH: {
                        if (a < 0.01f) continue;
                        int sxr = clampI(x + shift + split, w), sxb = clampI(x + shift - split, w), sxg = clampI(x + shift, w);
                        float nr = (src[base + sxr] >> 16) & 255, ng = (src[base + sxg] >> 8) & 255, nb = src[base + sxb] & 255;
                        if (swap) {
                            float t = nr;
                            nr = nb;
                            nb = 255 - t;
                        }
                        if (scan) {
                            nr *= 0.82f;
                            ng *= 0.82f;
                            nb *= 0.82f;
                        }
                        r += (nr - r) * a;
                        g += (ng - g) * a;
                        b += (nb - b) * a;
                        break;
                    }
                    case THERMAL: {
                        float v = lum / 255f;
                        float core = lerp2(spill, a0, a1, b0, b1, wx, wy);
                        float t = (0.08f + 0.32f * v) * (1 - a) + (0.46f + 0.26f * core + 0.28f * v) * a;
                        int heat = iron(t);
                        float hr = (heat >> 16) & 255, hg = (heat >> 8) & 255, hb = heat & 255;
                        r += (hr - r) * amount;
                        g += (hg - g) * amount;
                        b += (hb - b) * amount;
                        break;
                    }
                    case HALFTONE: {
                        if (a < 0.01f) continue;
                        // dot size follows the brightness; dots sit on a dark ground
                        float cx = (float) Math.floor(x / cell) * cell + cell / 2, cy = (float) Math.floor(yy / cell) * cell + cell / 2;
                        float d = (float) Math.sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (yy + 0.5f - cy) * (yy + 0.5f - cy));
                        float rad = cell * 0.66f * (float) Math.sqrt(0.08f + 0.92f * lum / 255f);
                        float dot = Math.max(0, Math.min(1, rad + 0.5f - d));
                        float k = a * amount;
                        float gr = 18 + 237 * dot;
                        r += (gr * cr - r) * k;
                        g += (gr * cg - g) * k;
                        b += (gr * cb - b) * k;
                        break;
                    }
                }
                rgb[base + x] = (c & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
    }

    /** "Iron" heat palette: black, violet, red, orange, yellow, white, for t in 0..1. */
    private static int iron(float t) {
        float[][] stops = {{0, 0, 0, 0}, {0.2f, 40, 0, 90}, {0.42f, 150, 0, 140}, {0.6f, 225, 40, 40}, {0.78f, 255, 140, 0},
            {0.92f, 255, 230, 60}, {1f, 255, 255, 235}};
        if (t <= 0) return 0;
        for (int i = 1; i < stops.length; i++) {
            if (t <= stops[i][0]) {
                float[] p = stops[i - 1], q = stops[i];
                float k = (t - p[0]) / (q[0] - p[0]);
                int r = (int) (p[1] + (q[1] - p[1]) * k), g = (int) (p[2] + (q[2] - p[2]) * k), b = (int) (p[3] + (q[3] - p[3]) * k);
                return (r << 16) | (g << 8) | b;
            }
        }
        return 0xFFFFEB;
    }

    private static float hash(int i, int seed) {
        int h = i * 374761393 + seed * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0x1000000;
    }

    private static int srgb(float v) {
        if (v <= 0) return 0;
        if (v >= 1) return 255;
        return TO_SRGB[(int) (v * 4096 + 0.5f)] & 255;
    }

    private static float smooth(float e0, float e1, float v) {
        float t = (v - e0) / (e1 - e0);
        return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
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

    private static int clampI(int i, int n) {
        return i < 0 ? 0 : i >= n ? n - 1 : i;
    }
}
