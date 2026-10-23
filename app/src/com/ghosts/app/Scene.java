package com.ghosts.app;

/**
 * Effects that use the subject mask to treat the subjects and the rest of the scene differently:
 *   Noir pop  - the world in rich black and white film, the subjects in vivid colour.
 *   Bokeh     - the background thrown out of focus, more so further from the subjects, highlights opening
 *               into bright discs.
 *   Rim light - backlit like a concert: the scene falls dark, the subjects become moody silhouettes with a
 *               glowing coloured rim of light round their edges.
 *   Glitch    - subjects broken up digitally: colour channels pulled apart and slices knocked sideways.
 *   Thermal   - a heat camera: subjects glow hot, the scene stays cold.
 *   Comic     - subjects drawn as pop art: flat bold colour, Ben-Day dots in the mid tones, an ink outline.
 *
 * Like the other looks it works on strips of the copy, from maps at the mask's small size (and, for Bokeh,
 * a larger blurred copy of the photo). Plain Java (no Android classes) so it can be checked on a computer.
 */
public class Scene {
    public static final int NOIR = 0, BOKEH = 1, RIM = 2, GLITCH = 3, THERMAL = 4, COMIC = 5;

    private final int kind, mw, mh;
    private final float amount;
    private final float[] alpha; // subjects, 0..1, feathered a little
    private final float[] aux;   // Rim light: the rim; Thermal: the body's warm core; Bokeh: distance from subjects
    private final float[] aux2;  // Rim light: the rim's glow
    private final float cr, cg, cb;
    private final int seed;
    // Bokeh: two blur levels of the background at bw x bh, packed RGB
    private final int[] blurNear, blurFar;
    private final int bw, bh;

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
     * @param mask   subject mask, mw x mh, same orientation as the copy
     * @param big    for Bokeh: the photo at bigW x bigH (larger than the mask for a cleaner blur), packed RGB
     * @param amount strength, 0..1
     */
    public Scene(int kind, byte[] mask, int mw, int mh, int[] big, int bigW, int bigH, float amount, float[] colour, int seed) {
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
        float[] a1 = null, a2 = null;
        if (kind == RIM) {
            // the rim: the figure minus a shrunk copy of it, then a wide glow from the rim
            float[] inner = alpha.clone();
            Ghost.gaussian(inner, mw, mh, Math.max(1, Math.round(mw / 320f)));
            a1 = new float[n];
            for (int i = 0; i < n; i++) a1[i] = alpha[i] * (1 - smooth(0.8f, 0.99f, inner[i]));
            a2 = alpha.clone();
            Ghost.gaussian(a2, mw, mh, Math.max(2, Math.round(mw / 22f)));
            for (int i = 0; i < n; i++) a2[i] = Math.min(1, a2[i] * 1.8f);
        } else if (kind == THERMAL) {
            a1 = alpha.clone();
            Ghost.gaussian(a1, mw, mh, Math.max(2, Math.round(mw / 70f)));
            for (int i = 0; i < n; i++) a1[i] = Math.min(1, a1[i] * a1[i] * 1.6f);
        } else if (kind == BOKEH) {
            // further from the subjects means further out of focus
            a1 = alpha.clone();
            Ghost.gaussian(a1, mw, mh, Math.max(2, Math.round(mw / 12f)));
            for (int i = 0; i < n; i++) a1[i] = 1 - Math.min(1, a1[i] * 2.2f);
        }
        aux = a1;
        aux2 = a2;
        if (kind == BOKEH && big != null) {
            bw = bigW;
            bh = bigH;
            int[][] levels = bokehLevels(big, bigW, bigH);
            blurNear = levels[0];
            blurFar = levels[1];
        } else {
            bw = bh = 0;
            blurNear = blurFar = null;
        }
    }

    /** Two blurs of the background alone (subjects masked out and filled in), highlights boosted into discs. */
    private int[][] bokehLevels(int[] big, int w, int h) {
        int n = w * h;
        float[] wgt = new float[n];
        for (int y = 0; y < h; y++) {
            float sy = (y + 0.5f) * mh / h - 0.5f;
            for (int x = 0; x < w; x++) {
                float sx = (x + 0.5f) * mw / w - 0.5f;
                wgt[y * w + x] = 1 - Math.min(1, sample(alpha, sx, sy, mw, mh) * 1.6f);
            }
        }
        int rNear = Math.max(1, Math.round(w * (0.003f + 0.007f * amount)));
        int rFar = Math.max(2, Math.round(w * (0.008f + 0.02f * amount)));
        float[] wn = wgt.clone(), wf = wgt.clone();
        Ghost.gaussian(wn, w, h, rNear);
        Ghost.gaussian(wf, w, h, rFar);
        int[] near = new int[n], far = new int[n];
        float[] ch = new float[n], c2 = new float[n];
        for (int c = 0; c < 3; c++) {
            int shift = 16 - 8 * c;
            for (int i = 0; i < n; i++) {
                int p = big[i];
                float l = TO_LINEAR[(p >> shift) & 255];
                float mx = Math.max(TO_LINEAR[(p >> 16) & 255], Math.max(TO_LINEAR[(p >> 8) & 255], TO_LINEAR[p & 255]));
                // bright spots get much more light, so they open up into discs like a real lens
                ch[i] = l * (1 + 10 * smooth(0.55f, 1f, mx)) * wgt[i];
            }
            System.arraycopy(ch, 0, c2, 0, n);
            Ghost.gaussian(ch, w, h, rNear);
            Ghost.gaussian(c2, w, h, rFar);
            for (int i = 0; i < n; i++) {
                float vn = wn[i] > 1e-4f ? ch[i] / wn[i] : 0, vf = wf[i] > 1e-4f ? c2[i] / wf[i] : 0;
                near[i] |= srgb(vn / (1 + vn * 0.2f)) << shift;
                far[i] |= srgb(vf / (1 + vf * 0.2f)) << shift;
            }
        }
        return new int[][] {near, far};
    }

    private static float sample(float[] m, float sx, float sy, int w, int h) {
        if (sx < 0) sx = 0;
        if (sy < 0) sy = 0;
        int x0 = Math.min(w - 1, (int) sx), y0 = Math.min(h - 1, (int) sy);
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float fx = sx - x0, fy = sy - y0;
        float top = m[y0 * w + x0] + (m[y0 * w + x1] - m[y0 * w + x0]) * fx;
        float bot = m[y1 * w + x0] + (m[y1 * w + x1] - m[y1 * w + x0]) * fx;
        return top + (bot - top) * fy;
    }

    private int[] x0, x1, bx0, bx1;
    private float[] fx, bfx;
    private int forWidth = -1;
    private int[] src = new int[0];

    /** Applies the effect to `count` rows of packed RGB starting at row y of a w x h copy. */
    public void apply(int[] rgb, int y, int count, int w, int h) {
        if (forWidth != w) {
            x0 = new int[w];
            x1 = new int[w];
            fx = new float[w];
            prepare(w, mw, x0, x1, fx);
            if (bw > 0) {
                bx0 = new int[w];
                bx1 = new int[w];
                bfx = new float[w];
                prepare(w, bw, bx0, bx1, bfx);
            }
            forWidth = w;
        }
        if (kind == GLITCH) {
            if (src.length < w * count) src = new int[w * count];
            System.arraycopy(rgb, 0, src, 0, w * count);
        }
        float cell = Math.max(5, w / 85f); // comic dots, copy pixels
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
            int by0 = 0, by1 = 0;
            float bwy = 0;
            if (bw > 0) {
                float bsy = (yy + 0.5f) * bh / h - 0.5f;
                if (bsy < 0) bsy = 0;
                by0 = Math.min(bh - 1, (int) bsy);
                by1 = Math.min(bh - 1, by0 + 1);
                bwy = bsy - by0;
            }
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
                    case NOIR: {
                        // background: black and white with a film S-curve (deep blacks, bright whites)
                        float v = lum / 255f;
                        float sv = v * v * (3 - 2 * v) * 0.85f + v * 0.15f;
                        float gray = sv * 245;
                        // subjects: a touch more colour
                        float sat = 1.25f;
                        float pr = lum + (r - lum) * sat, pg = lum + (g - lum) * sat, pb = lum + (b - lum) * sat;
                        float k = (1 - a) * amount;
                        r = pr + (gray - pr) * k;
                        g = pg + (gray - pg) * k;
                        b = pb + (gray - pb) * k;
                        break;
                    }
                    case BOKEH: {
                        if (blurNear == null || a > 0.999f) continue;
                        float far = lerp2(aux, a0, a1, b0, b1, wx, wy);
                        int q00 = by0 * bw + bx0[x], q01 = by0 * bw + bx1[x], q10 = by1 * bw + bx0[x], q11 = by1 * bw + bx1[x];
                        float u = bfx[x];
                        float nr = mixRgb(blurNear, q00, q01, q10, q11, u, bwy, 16), ng = mixRgb(blurNear, q00, q01, q10, q11, u, bwy, 8);
                        float nb = mixRgb(blurNear, q00, q01, q10, q11, u, bwy, 0);
                        float fr = mixRgb(blurFar, q00, q01, q10, q11, u, bwy, 16), fg = mixRgb(blurFar, q00, q01, q10, q11, u, bwy, 8);
                        float fb = mixRgb(blurFar, q00, q01, q10, q11, u, bwy, 0);
                        float br = nr + (fr - nr) * far, bg = ng + (fg - ng) * far, bb = nb + (fb - nb) * far;
                        r = r * a + br * (1 - a);
                        g = g * a + bg * (1 - a);
                        b = b * a + bb * (1 - a);
                        break;
                    }
                    case RIM: {
                        float rim = lerp2(aux, a0, a1, b0, b1, wx, wy), halo = lerp2(aux2, a0, a1, b0, b1, wx, wy);
                        // the scene falls into darkness except the light spilling from behind the figures
                        float dark = 1 - amount * 0.9f * (1 - halo * 0.45f);
                        float k = dark + (0.32f - dark) * a; // the figures themselves: dark silhouettes
                        r *= k;
                        g *= k;
                        b *= k;
                        float light = Math.min(1, rim * 1.15f + halo * 0.5f * (1 - a));
                        r = 255 - (255 - r) * (1 - light * cr);
                        g = 255 - (255 - g) * (1 - light * cg);
                        b = 255 - (255 - b) * (1 - light * cb);
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
                        float core = lerp2(aux, a0, a1, b0, b1, wx, wy);
                        float t = (0.08f + 0.32f * v) * (1 - a) + (0.46f + 0.26f * core + 0.28f * v) * a;
                        int heat = iron(t);
                        float hr = (heat >> 16) & 255, hg = (heat >> 8) & 255, hb = heat & 255;
                        r += (hr - r) * amount;
                        g += (hg - g) * amount;
                        b += (hb - b) * amount;
                        break;
                    }
                    case COMIC: {
                        if (a < 0.01f) continue;
                        // flat, punchy colour in three tones
                        float v = lum / 255f;
                        float sat = 1.35f;
                        float pr = lum + (r - lum) * sat, pg = lum + (g - lum) * sat, pb = lum + (b - lum) * sat;
                        float mx = Math.max(1, Math.max(pr, Math.max(pg, pb)));
                        float tone = v < 0.22f ? 0.35f : v < 0.55f ? 0.75f : 1f;
                        float scale = 235 * tone / mx;
                        pr *= scale;
                        pg *= scale;
                        pb *= scale;
                        // Ben-Day dots over the middle tone, on a 45 degree grid
                        if (v >= 0.22f && v < 0.55f) {
                            float u = (x + yy) / (cell * 1.414f), t = (x - yy) / (cell * 1.414f);
                            float du = u - (float) Math.floor(u) - 0.5f, dt = t - (float) Math.floor(t) - 0.5f;
                            float d = (float) Math.sqrt(du * du + dt * dt) * cell * 1.414f;
                            float dot = Math.max(0, Math.min(1, cell * 0.28f + 0.5f - d));
                            pr *= 1 - 0.55f * dot;
                            pg *= 1 - 0.55f * dot;
                            pb *= 1 - 0.55f * dot;
                        }
                        // ink outline where the figure's edge is
                        float edge = 1 - Math.abs(a - 0.5f) * 2;
                        float ink = Math.max(0, Math.min(1, (edge - 0.35f) * 2.5f));
                        pr *= 1 - ink;
                        pg *= 1 - ink;
                        pb *= 1 - ink;
                        float k = Math.min(1, a * 1.6f) * amount;
                        r += (pr - r) * k;
                        g += (pg - g) * k;
                        b += (pb - b) * k;
                        break;
                    }
                }
                rgb[base + x] = (c & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
    }

    private static void prepare(int w, int mw, int[] x0, int[] x1, float[] fx) {
        for (int x = 0; x < w; x++) {
            float sx = (x + 0.5f) * mw / w - 0.5f;
            if (sx < 0) sx = 0;
            int i = (int) sx;
            if (i > mw - 1) i = mw - 1;
            x0[x] = i;
            x1[x] = Math.min(mw - 1, i + 1);
            fx[x] = sx - i;
        }
    }

    private static float mixRgb(int[] m, int a0, int a1, int b0, int b1, float wx, float wy, int shift) {
        float p = (m[a0] >> shift) & 255, q = (m[a1] >> shift) & 255, s = (m[b0] >> shift) & 255, t = (m[b1] >> shift) & 255;
        float top = p + (q - p) * wx, bot = s + (t - s) * wx;
        return top + (bot - top) * wy;
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
