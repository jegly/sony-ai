package com.ghosts.app;

/**
 * Combines several shots of the same scene (camera on a tripod), a row strip at a time:
 *   Crowd remover - the median of the shots at each pixel, so anything that moves through the scene goes
 *                   and only what stays put (the empty street) is left.
 *   Trails        - that empty scene with each shot's movers laid back on it in order, the earlier ones
 *                   fainter and paler, so one walker becomes a trail of ghosts.
 *
 * Plain Java (no Android classes) so it can be checked on a computer.
 */
public class MultiShot {
    public static final int CROWD = 0, TRAILS = 1;

    private final int kind;
    private final float amount;
    private final float cr, cg, cb;

    public MultiShot(int kind, float amount, float[] colour) {
        this.kind = kind;
        this.amount = amount;
        cr = colour[0];
        cg = colour[1];
        cb = colour[2];
    }

    private int[] vr = new int[0], vg = new int[0], vb = new int[0];

    /**
     * frames[i] holds the same rows (count x w, packed RGB) of shot i, oldest first; the result goes in out.
     */
    public void combine(int[][] frames, int count, int w, int[] out) {
        int n = frames.length;
        if (vr.length < n) {
            vr = new int[n];
            vg = new int[n];
            vb = new int[n];
        }
        for (int i = 0; i < count * w; i++) {
            for (int k = 0; k < n; k++) {
                int c = frames[k][i];
                vr[k] = (c >> 16) & 255;
                vg[k] = (c >> 8) & 255;
                vb[k] = c & 255;
            }
            int mr = median(vr, n), mg = median(vg, n), mb = median(vb, n);
            if (kind == CROWD) {
                out[i] = 0xFF000000 | (mr << 16) | (mg << 8) | mb;
                continue;
            }
            // trails: each shot's movers over the empty scene, oldest first and faintest
            float r = mr, g = mg, b = mb;
            for (int k = 0; k < n; k++) {
                int c = frames[k][i];
                float fr = (c >> 16) & 255, fg = (c >> 8) & 255, fb = c & 255;
                float diff = Math.max(Math.abs(fr - mr), Math.max(Math.abs(fg - mg), Math.abs(fb - mb)));
                float m = smooth(18, 46, diff);
                if (m <= 0) continue;
                float age = n == 1 ? 1 : k / (float) (n - 1); // 0 oldest .. 1 newest
                float op = 0.3f + 0.7f * age;
                // older ghosts turn paler, towards the Colour's light
                float pale = (1 - age) * amount * 0.7f;
                fr += (255 * cr - fr) * pale;
                fg += (255 * cg - fg) * pale;
                fb += (255 * cb - fb) * pale;
                float a = m * op;
                r += (fr - r) * a;
                g += (fg - g) * a;
                b += (fb - b) * a;
            }
            out[i] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }
    }

    /** Median of the first n values (sorted in place; n is small). */
    private static int median(int[] v, int n) {
        for (int i = 1; i < n; i++) {
            int x = v[i], j = i - 1;
            while (j >= 0 && v[j] > x) {
                v[j + 1] = v[j];
                j--;
            }
            v[j + 1] = x;
        }
        return (n & 1) == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
    }

    private static float smooth(float e0, float e1, float v) {
        float t = (v - e0) / (e1 - e0);
        return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
    }

    private static int clamp(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }
}
