package com.ghosts.app;

/**
 * A diffusion filter, like a Black Pro-Mist or Glimmerglass in front of the lens.
 *
 * A real filter scatters a small share of the incoming light into a halo, so it works on light, not on
 * JPEG values: the photo is turned back into linear light, a fraction of it is replaced by a blurred copy
 * (a tight glow plus a wide one), and the result goes back to sRGB. Bright lights therefore bloom, and
 * shadows are lifted only as much as the stray light reaches them. Black mist filters scatter mostly from
 * the highlights (their black specks cut the veil), white mist scatters everything for a hazier look.
 *
 * The blurred light comes from a small copy of the photo (the one made for the model), stretched to full
 * size, since the glow is smooth anyway. Plain Java so it can be checked on a computer.
 */
public class Diffusion {
    public static final String[] NAMES = {"Off", "Black Mist 1/8", "Black Mist 1/4", "Black Mist 1/2", "Black Mist 1",
            "White Mist 1/4", "White Mist 1/2", "Glimmer 1/4"};

    // per setting: tight glow share, its radius (fraction of width), wide glow share, its radius, highlight weighting
    private static final float[][] SETTINGS = {
        {0, 0, 0, 0, 0},
        {0.14f, 0.006f, 0.07f, 0.04f, 1},
        {0.22f, 0.007f, 0.11f, 0.045f, 1},
        {0.32f, 0.008f, 0.16f, 0.05f, 1},
        {0.44f, 0.009f, 0.22f, 0.055f, 1},
        {0.14f, 0.008f, 0.24f, 0.06f, 0},
        {0.20f, 0.009f, 0.36f, 0.07f, 0},
        {0.28f, 0.003f, 0.05f, 0.03f, 1},
    };

    private static final float[] TO_LINEAR = new float[256];
    private static final int OUT_STEPS = 4096;
    private static final byte[] TO_SRGB = new byte[OUT_STEPS + 1];

    static {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            TO_LINEAR[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i <= OUT_STEPS; i++) {
            double v = i / (double) OUT_STEPS;
            double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            TO_SRGB[i] = (byte) Math.max(0, Math.min(255, Math.round(s * 255)));
        }
    }

    private final int mw, mh;
    private final float share;         // share of the light the filter scatters (from fully weighted pixels)
    private final float[] keepFor = new float[256]; // share of a pixel's light that stays, by its brightest channel
    private final float[] glowR, glowG, glowB; // scattered light, already weighted (near + far)

    /** @param preview the photo at mw x mh as packed RGB, same orientation as the copy */
    public Diffusion(int[] preview, int mw, int mh, int setting) {
        this.mw = mw;
        this.mh = mh;
        float[] p = SETTINGS[Math.max(0, Math.min(SETTINGS.length - 1, setting))];
        float near = p[0], far = p[2];
        share = near + far;
        // a pixel loses only the light it scatters, so the filter moves light around without losing any
        for (int v = 0; v < 256; v++) keepFor[v] = 1 - share * weight(p[4] > 0, TO_LINEAR[v]);
        int n = mw * mh;
        float[] r = new float[n], g = new float[n], b = new float[n];
        for (int i = 0; i < n; i++) {
            int c = preview[i];
            float lr = TO_LINEAR[(c >> 16) & 255], lg = TO_LINEAR[(c >> 8) & 255], lb = TO_LINEAR[c & 255];
            // clipped highlights were brighter than the JPEG can say: give them more light to scatter
            float mx = Math.max(lr, Math.max(lg, lb));
            float boost = 1 + 3 * smooth(0.75f, 1f, mx);
            float k = boost * weight(p[4] > 0, mx);
            r[i] = lr * k;
            g[i] = lg * k;
            b[i] = lb * k;
        }
        glowR = mix(r, near, p[1], far, p[3]);
        glowG = mix(g, near, p[1], far, p[3]);
        glowB = mix(b, near, p[1], far, p[3]);
    }

    /** How much of its light a pixel scatters: black mist scatters little from dark areas, so the veil stays off the shadows. */
    private static float weight(boolean blackMist, float linearMax) {
        return blackMist ? 0.25f + 0.75f * smooth(0.05f, 0.6f, linearMax) : 1;
    }

    /** near * blur(radius rn) + far * blur(radius rf), radii as fractions of the width. */
    private float[] mix(float[] src, float near, float rn, float far, float rf) {
        float[] a = src.clone();
        float[] c = src;
        gaussian(a, mw, mh, Math.max(1, Math.round(rn * mw)));
        gaussian(c, mw, mh, Math.max(1, Math.round(rf * mw)));
        for (int i = 0; i < a.length; i++) a[i] = a[i] * near + c[i] * far;
        return a;
    }

    private int[] x0, x1;
    private float[] fx;
    private int forWidth = -1;

    /** Applies the filter to `count` rows of packed RGB starting at row y of a w x h copy. */
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
                int c = rgb[base + x];
                int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
                float keep = keepFor[Math.max(cr, Math.max(cg, cb))];
                float lr = TO_LINEAR[cr] * keep + lerp2(glowR, a0, a1, b0, b1, wx, wy);
                float lg = TO_LINEAR[cg] * keep + lerp2(glowG, a0, a1, b0, b1, wx, wy);
                float lb = TO_LINEAR[cb] * keep + lerp2(glowB, a0, a1, b0, b1, wx, wy);
                rgb[base + x] = (c & 0xFF000000) | (srgb(lr) << 16) | (srgb(lg) << 8) | srgb(lb);
            }
        }
    }

    private static int srgb(float v) {
        if (v <= 0) return 0;
        if (v >= 1) return 255;
        return TO_SRGB[(int) (v * OUT_STEPS + 0.5f)] & 255;
    }

    private static float smooth(float e0, float e1, float v) {
        float t = (v - e0) / (e1 - e0);
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    private static float lerp2(float[] m, int a0, int a1, int b0, int b1, float wx, float wy) {
        float top = m[a0] + (m[a1] - m[a0]) * wx;
        float bot = m[b0] + (m[b1] - m[b0]) * wx;
        return top + (bot - top) * wy;
    }

    /** Three box blurs of radius r, close to a Gaussian. */
    private static void gaussian(float[] m, int w, int h, int r) {
        for (int pass = 0; pass < 3; pass++) blur(m, w, h, r);
    }

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
