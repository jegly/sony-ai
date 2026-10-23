package com.ghosts.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Neon stick figures from YOLO11n-pose: glowing limbs between the 17 COCO keypoints, a ring for the head and
 * a bright dot at each joint, over the photo (dimmed a little by Amount). Line width follows each person's
 * size so near and far figures look alike.
 *
 * Points are given in the copy's own orientation as fractions of its width and height. Plain Java (no
 * Android classes) so it can be checked on a computer.
 */
public class Skeleton {
    /** COCO keypoint pairs joined by limbs (Ultralytics' skeleton, 0-based). */
    private static final int[] LIMBS = {
        15, 13, 13, 11, 16, 14, 14, 12, 11, 12, 5, 11, 6, 12, 5, 6, 5, 7, 6, 8, 7, 9, 8, 10};
    private static final float MIN_VIS = 0.45f;

    private final List<float[]> lines = new ArrayList<float[]>(); // x0 y0 x1 y1 width (fractions; width of height)
    private final List<float[]> dots = new ArrayList<float[]>();  // x y radius
    private final List<float[]> rings = new ArrayList<float[]>(); // x y radius width
    private final float dim, glow;
    private final float cr, cg, cb;
    private int people;

    public Skeleton(float dim, int glowLevel, float[] colour) {
        this.dim = dim;
        this.glow = Ghost.GLOW[Math.max(0, Math.min(Ghost.GLOW.length - 1, glowLevel))];
        cr = colour[0];
        cg = colour[1];
        cb = colour[2];
    }

    /**
     * Adds one person: kp is 17 x (x, y, visibility), x and y as fractions of the copy's width and height;
     * size is the person's box height as a fraction of the copy's height; aspect is the copy's width / height.
     */
    public void addPerson(float[] kp, float size, float aspect) {
        float width = Math.max(0.0012f, size * 0.012f); // limb half width, as a fraction of the height
        for (int i = 0; i < LIMBS.length; i += 2) {
            int a = LIMBS[i], b = LIMBS[i + 1];
            if (kp[3 * a + 2] < MIN_VIS || kp[3 * b + 2] < MIN_VIS) continue;
            lines.add(new float[] {kp[3 * a], kp[3 * a + 1], kp[3 * b], kp[3 * b + 1], width});
        }
        // neck: shoulders' midpoint to the head
        boolean ls = kp[3 * 5 + 2] >= MIN_VIS, rs = kp[3 * 6 + 2] >= MIN_VIS, nose = kp[2] >= MIN_VIS;
        // head ring round the face keypoints
        float hx = 0, hy = 0, n = 0;
        for (int k = 0; k < 5; k++) {
            if (kp[3 * k + 2] < MIN_VIS) continue;
            hx += kp[3 * k];
            hy += kp[3 * k + 1];
            n++;
        }
        if (n > 0) {
            hx /= n;
            hy /= n;
            float r = size * 0.065f;
            boolean le = kp[3 * 3 + 2] >= MIN_VIS, re = kp[3 * 4 + 2] >= MIN_VIS;
            if (le && re) r = Math.max(r, dist(kp, 3, 4, aspect) * 0.75f);
            rings.add(new float[] {hx, hy, r, width});
            if (ls && rs) {
                float mx = (kp[15] + kp[18]) / 2, my = (kp[16] + kp[19]) / 2;
                // stop the neck at the ring
                float dx = (mx - hx) * aspect, dy = my - hy, len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > r) lines.add(new float[] {hx + dx / len * r / aspect, hy + dy / len * r, mx, my, width});
            }
        } else if (ls && rs && nose) {
            lines.add(new float[] {(kp[15] + kp[18]) / 2, (kp[16] + kp[19]) / 2, kp[0], kp[1], width});
        }
        for (int k = 5; k < 17; k++) {
            if (kp[3 * k + 2] >= MIN_VIS) dots.add(new float[] {kp[3 * k], kp[3 * k + 1], width * 1.9f});
        }
        people++;
    }

    public int people() {
        return people;
    }

    private static float dist(float[] kp, int a, int b, float aspect) {
        float dx = (kp[3 * a] - kp[3 * b]) * aspect, dy = kp[3 * a + 1] - kp[3 * b + 1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float[] light = new float[0];

    /** Applies the effect to `count` rows of packed RGB starting at row y of a w x h copy. */
    public void apply(int[] rgb, int y, int count, int w, int h) {
        if (light.length < w * count) light = new float[w * count];
        java.util.Arrays.fill(light, 0, w * count, 0f);
        for (float[] l : lines) {
            float half = Math.max(0.8f, l[4] * h);
            seg(l[0] * w, l[1] * h, l[2] * w, l[3] * h, half, y, count, w);
        }
        for (float[] r : rings) ring(r[0] * w, r[1] * h, r[2] * h, Math.max(0.8f, r[3] * h), y, count, w);
        for (float[] d : dots) ring(d[0] * w, d[1] * h, 0, Math.max(1.2f, d[2] * h), y, count, w);
        for (int i = 0; i < w * count; i++) {
            float l = light[i];
            int c = rgb[i];
            float r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            float k = 1 - dim * 0.6f;
            r *= k;
            g *= k;
            b *= k;
            if (l > 0) {
                r = 255 - (255 - r) * (1 - Math.min(1, l * cr));
                g = 255 - (255 - g) * (1 - Math.min(1, l * cg));
                b = 255 - (255 - b) * (1 - Math.min(1, l * cb));
            }
            rgb[i] = (c & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }
    }

    /** A glowing line: a solid core of half width `half` and a soft glow four times wider. */
    private void seg(float ax, float ay, float bx, float by, float half, int y, int count, int w) {
        float reach = half * (1 + 4 * glow) + 1;
        int ry0 = Math.max(y, (int) Math.floor(Math.min(ay, by) - reach));
        int ry1 = Math.min(y + count - 1, (int) Math.ceil(Math.max(ay, by) + reach));
        if (ry0 > ry1) return;
        int rx0 = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach));
        int rx1 = Math.min(w - 1, (int) Math.ceil(Math.max(ax, bx) + reach));
        float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        for (int yy = ry0; yy <= ry1; yy++) {
            float cy = yy + 0.5f;
            int base = (yy - y) * w;
            for (int xx = rx0; xx <= rx1; xx++) {
                float cx = xx + 0.5f;
                float t = len2 > 0 ? ((cx - ax) * dx + (cy - ay) * dy) / len2 : 0;
                t = t < 0 ? 0 : t > 1 ? 1 : t;
                float ex = ax + t * dx - cx, ey = ay + t * dy - cy;
                put(base + xx, (float) Math.sqrt(ex * ex + ey * ey), half);
            }
        }
    }

    /** A glowing ring of radius r (r = 0: a dot of radius `half`). */
    private void ring(float cx0, float cy0, float r, float half, int y, int count, int w) {
        float reach = r + half * (1 + 4 * glow) + 1;
        int ry0 = Math.max(y, (int) Math.floor(cy0 - reach)), ry1 = Math.min(y + count - 1, (int) Math.ceil(cy0 + reach));
        if (ry0 > ry1) return;
        int rx0 = Math.max(0, (int) Math.floor(cx0 - reach)), rx1 = Math.min(w - 1, (int) Math.ceil(cx0 + reach));
        for (int yy = ry0; yy <= ry1; yy++) {
            int base = (yy - y) * w;
            for (int xx = rx0; xx <= rx1; xx++) {
                float ex = xx + 0.5f - cx0, ey = yy + 0.5f - cy0;
                put(base + xx, Math.abs((float) Math.sqrt(ex * ex + ey * ey) - r), half);
            }
        }
    }

    private void put(int i, float d, float half) {
        float core = half + 0.5f - d;
        float v = core >= 1 ? 1 : core > 0 ? core : 0;
        float g = half * 4 * glow;
        if (g > 0 && d < half + g) {
            float t = 1 - (d - half) / g;
            if (t > 1) t = 1;
            v = Math.max(v, 0.55f * t * t);
        }
        // cores of crossing lines run white-hot
        if (v > light[i]) light[i] = v;
        else if (core > 0) light[i] = Math.min(1.25f, light[i] + v * 0.25f);
    }

    private static int clamp(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }
}
