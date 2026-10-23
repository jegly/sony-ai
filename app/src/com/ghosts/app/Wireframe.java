package com.ghosts.app;

/**
 * Draws people as glowing wireframes, like a 3D scan: a bright outline, contour lines that follow the body's
 * shape inwards, and horizontal scan lines, over the person dimmed behind them.
 *
 * Everything comes from the engine's person mask: a signed distance from the figure's edge (positive inside)
 * is worked out at the mask's size and stretched to the copy, and lines are drawn where that distance is a
 * multiple of the spacing. Because the distance grows by about one mask pixel per pixel, a line's width can
 * be judged in copy pixels, which keeps the lines thin and smooth at any copy size.
 *
 * Plain Java (no Android classes) so it can be checked on a computer.
 */
public class Wireframe {
    private final int mw, mh;
    private final float[] dist;  // signed distance from the edge in mask pixels, positive inside
    private final float[] alpha; // the figure, 0..1
    private final float spacing; // contour spacing in mask pixels
    private final float dim;     // how dark the person gets behind the lines
    private final float glow;
    private final float cr, cg, cb;

    public Wireframe(byte[] mask, int mw, int mh, float dim, int glowLevel, float[] colour) {
        this.mw = mw;
        this.mh = mh;
        this.dim = dim;
        this.glow = Ghost.GLOW[Math.max(0, Math.min(Ghost.GLOW.length - 1, glowLevel))];
        cr = colour[0];
        cg = colour[1];
        cb = colour[2];
        int n = mw * mh;
        alpha = new float[n];
        boolean[] in = new boolean[n];
        for (int i = 0; i < n; i++) {
            float p = (mask[i] & 255) / 255f;
            in[i] = p >= 0.5f;
            float a = (p - 0.35f) / 0.3f;
            alpha[i] = a <= 0 ? 0 : a >= 1 ? 1 : a * a * (3 - 2 * a);
        }
        float[] inside = chamfer(in, mw, mh, true);
        float[] outside = chamfer(in, mw, mh, false);
        dist = new float[n];
        // the edge sits halfway between the last pixel in and the first pixel out
        for (int i = 0; i < n; i++) dist[i] = in[i] ? inside[i] - 0.5f : 0.5f - outside[i];
        // the chamfer distance steps from pixel to pixel; a light blur makes the contours smooth curves
        Ghost.gaussian(dist, mw, mh, Math.max(1, Math.round(mw / 320f)));
        spacing = Math.max(4f, mw / 60f);
    }

    /** Distance (mask pixels) from each pixel to the nearest pixel on the other side, 3-4 chamfer; 0 for the side itself. */
    static float[] chamfer(boolean[] in, int w, int h, boolean measureInside) {
        float big = 1e6f;
        float[] d = new float[w * h];
        for (int i = 0; i < d.length; i++) d[i] = in[i] == measureInside ? big : 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (d[i] == 0) continue;
                float v = d[i];
                if (x > 0) v = Math.min(v, d[i - 1] + 1);
                if (y > 0) {
                    v = Math.min(v, d[i - w] + 1);
                    if (x > 0) v = Math.min(v, d[i - w - 1] + 1.4142f);
                    if (x < w - 1) v = Math.min(v, d[i - w + 1] + 1.4142f);
                }
                d[i] = v;
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                if (d[i] == 0) continue;
                float v = d[i];
                if (x < w - 1) v = Math.min(v, d[i + 1] + 1);
                if (y < h - 1) {
                    v = Math.min(v, d[i + w] + 1);
                    if (x < w - 1) v = Math.min(v, d[i + w + 1] + 1.4142f);
                    if (x > 0) v = Math.min(v, d[i + w - 1] + 1.4142f);
                }
                d[i] = v;
            }
        }
        return d;
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
        float scale = (float) w / mw;              // copy pixels per mask pixel
        float half = Math.max(0.7f, w / 1500f);    // half the line width, in copy pixels
        float outlineHalf = half * 1.6f;
        float gap = spacing * scale;               // scan line gap, in copy pixels
        for (int row = 0; row < count; row++) {
            int yy = y + row;
            float sy = (yy + 0.5f) * mh / h - 0.5f;
            if (sy < 0) sy = 0;
            int iy = Math.min(mh - 1, (int) sy);
            int iy2 = Math.min(mh - 1, iy + 1);
            float wy = sy - iy;
            int r0 = iy * mw, r1 = iy2 * mw;
            int base = row * w;
            // scan lines: distance (copy pixels) from this row to the nearest one
            float sl = yy % gap;
            float scanDist = Math.min(sl, gap - sl);
            float scan = line(scanDist, half * 0.8f) * 0.6f;
            for (int x = 0; x < w; x++) {
                int a0 = r0 + x0[x], a1 = r0 + x1[x], b0 = r1 + x0[x], b1 = r1 + x1[x];
                float wx = fx[x];
                float d = lerp2(dist, a0, a1, b0, b1, wx, wy);
                float dPx = d * scale; // signed distance from the edge, copy pixels
                if (dPx < -outlineHalf * 8) continue; // well outside any figure
                float a = lerp2(alpha, a0, a1, b0, b1, wx, wy);
                // outline at the edge, plus a soft glow around it
                float outline = line(Math.abs(dPx), outlineHalf);
                float halo = glow * 0.45f * soft(Math.abs(dPx), outlineHalf * 7);
                // contours inside, following the shape
                float lines = 0;
                if (d > 0) {
                    float t = d / spacing;
                    float k = Math.abs(t - Math.round(t)) * spacing * scale;
                    lines = Math.max(line(k, half), scan) * a * 0.85f;
                }
                float light = Math.min(1, Math.max(outline, lines) + halo * (1 - a * 0.7f));
                if (light < 0.002f && a < 0.002f) continue;
                int c = rgb[base + x];
                float r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                // the person dimmed behind the lines
                float keep = 1 - dim * a;
                r *= keep;
                g *= keep;
                b *= keep;
                r = 255 - (255 - r) * (1 - light * cr);
                g = 255 - (255 - g) * (1 - light * cg);
                b = 255 - (255 - b) * (1 - light * cb);
                rgb[base + x] = (c & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
    }

    /** An anti-aliased line: 1 on the line, fading to 0 one pixel past its half width. */
    private static float line(float d, float half) {
        float v = half + 0.5f - d;
        return v <= 0 ? 0 : v >= 1 ? 1 : v;
    }

    /** A soft falloff out to radius r. */
    private static float soft(float d, float r) {
        if (d >= r) return 0;
        float t = 1 - d / r;
        return t * t;
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
}
