package com.ghosts.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Draws people as a glowing triangle mesh, like a face-mesh or 3D-scan map: points are scattered over each
 * figure (closer together along its outline), joined into triangles (Delaunay), and only the triangles lying
 * inside the figure are kept. Thin edges, a dot at each corner and a glowing outline go over the person,
 * who is dimmed behind them. Extra lines (a face mesh) can be added on top.
 *
 * Points are kept in mask pixels and scaled to the copy when drawn, so the lines stay thin and sharp at
 * any copy size. Plain Java (no Android classes) so it can be checked on a computer.
 */
public class Mesh {
    private final int mw, mh;
    private final float[] alpha; // the figure, 0..1
    private final float[] dist;  // signed distance from the edge in mask pixels, positive inside
    private final float dim, glow;
    private final float cr, cg, cb;
    private final float[] px, py;     // mesh points, mask pixels
    private final List<int[]> edges = new ArrayList<int[]>();
    private boolean[] hidden;          // body points covered by a face
    // extra line segments (x0, y0, x1, y1 in mask pixels, strength) and dots: the face meshes
    private final List<float[]> extraLines = new ArrayList<float[]>();
    private final List<float[]> extraDots = new ArrayList<float[]>();
    private int faces;

    public Mesh(byte[] mask, int mw, int mh, float dim, int glowLevel, float[] colour, long seed) {
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
        float[] inside = Wireframe.chamfer(in, mw, mh, true);
        float[] outside = Wireframe.chamfer(in, mw, mh, false);
        dist = new float[n];
        for (int i = 0; i < n; i++) dist[i] = in[i] ? inside[i] - 0.5f : 0.5f - outside[i];
        Ghost.gaussian(dist, mw, mh, Math.max(1, Math.round(mw / 320f)));

        // 1. points: along the outline first, then filling the inside
        float s = Math.max(5f, mw / 42f);
        Random rnd = new Random(seed);
        PointSet pts = new PointSet(mw, mh, s);
        List<Integer> edge = new ArrayList<Integer>();
        for (int y = 1; y < mh - 1; y++) {
            for (int x = 1; x < mw - 1; x++) {
                int i = y * mw + x;
                if (in[i] && (!in[i - 1] || !in[i + 1] || !in[i - mw] || !in[i + mw])) edge.add(i);
            }
        }
        shuffle(edge, rnd);
        for (int i : edge) pts.tryAdd(i % mw + 0.5f, i / mw + 0.5f, s * 0.7f);
        int area = 0;
        for (boolean b : in) if (b) area++;
        int tries = (int) (area / (s * s) * 30);
        for (int t = 0; t < tries; t++) {
            float x = rnd.nextFloat() * mw, y = rnd.nextFloat() * mh;
            int i = Math.min(mh - 1, (int) y) * mw + Math.min(mw - 1, (int) x);
            if (dist[i] < s * 0.35f) continue;
            // a little closer together near the edge, roomier in the middle
            float spacing = s * (0.95f + 0.35f * Math.min(1f, dist[i] / (s * 3)));
            pts.tryAdd(x, y, spacing);
        }
        px = pts.xs();
        py = pts.ys();

        // 2. triangles, keeping only those inside a figure
        if (px.length >= 3) {
            int[] tris = Delaunay.triangulate(px, py);
            HashSet<Long> seen = new HashSet<Long>();
            float maxLen = s * 3.4f;
            for (int t = 0; t < tris.length; t += 3) {
                int a = tris[t], b = tris[t + 1], c = tris[t + 2];
                if (!insideTri(a, b, c, maxLen)) continue;
                addEdge(a, b, seen);
                addEdge(b, c, seen);
                addEdge(c, a, seen);
            }
        }
    }

    /**
     * Puts a face mesh over the body mesh: the body's points and edges inside the face outline go, and the
     * face's own edges (eyes, lips and brows a little brighter) and points come in.
     *
     * @param pts face landmarks in mask pixels (x, y pairs)
     */
    public void addFace(float[] pts, FaceMesh mesh) {
        int n = mesh.oval.length;
        float[] ox = new float[n], oy = new float[n];
        for (int i = 0; i < n; i++) {
            ox[i] = pts[2 * mesh.oval[i]];
            oy[i] = pts[2 * mesh.oval[i] + 1];
        }
        if (hidden == null) hidden = new boolean[px.length];
        for (int i = 0; i < px.length; i++) if (inPolygon(px[i], py[i], ox, oy)) hidden[i] = true;
        java.util.Iterator<int[]> it = edges.iterator();
        while (it.hasNext()) {
            int[] e = it.next();
            float mx = (px[e[0]] + px[e[1]]) / 2, my = (py[e[0]] + py[e[1]]) / 2;
            if (hidden[e[0]] || hidden[e[1]] || inPolygon(mx, my, ox, oy)) it.remove();
        }
        HashSet<Long> feature = new HashSet<Long>();
        for (int i = 0; i < mesh.features.length; i += 2) {
            int a = mesh.features[i], b = mesh.features[i + 1];
            feature.add(a < b ? ((long) a << 32) | b : ((long) b << 32) | a);
        }
        for (int i = 0; i < mesh.edges.length; i += 2) {
            int a = mesh.edges[i], b = mesh.edges[i + 1];
            boolean f = feature.contains(a < b ? ((long) a << 32) | b : ((long) b << 32) | a);
            extraLines.add(new float[] {pts[2 * a], pts[2 * a + 1], pts[2 * b], pts[2 * b + 1], f ? 1f : 0.55f});
        }
        for (int i = 0; i < mesh.features.length; i += 2) {
            int a = mesh.features[i], b = mesh.features[i + 1];
            extraLines.add(new float[] {pts[2 * a], pts[2 * a + 1], pts[2 * b], pts[2 * b + 1], 1f});
        }
        for (int i = 0; i < pts.length / 2; i++) extraDots.add(new float[] {pts[2 * i], pts[2 * i + 1]});
        faces++;
    }

    public int faces() {
        return faces;
    }

    private static boolean inPolygon(float x, float y, float[] ox, float[] oy) {
        boolean in = false;
        for (int i = 0, j = ox.length - 1; i < ox.length; j = i++) {
            if ((oy[i] > y) != (oy[j] > y) && x < (ox[j] - ox[i]) * (y - oy[i]) / (oy[j] - oy[i]) + ox[i]) in = !in;
        }
        return in;
    }

    public int points() {
        return px.length;
    }

    public int edgeCount() {
        return edges.size();
    }

    private boolean insideTri(int a, int b, int c, float maxLen) {
        if (len(a, b) > maxLen || len(b, c) > maxLen || len(c, a) > maxLen) return false;
        float cx = (px[a] + px[b] + px[c]) / 3, cy = (py[a] + py[b] + py[c]) / 3;
        return in(cx, cy) && in((px[a] + px[b]) / 2, (py[a] + py[b]) / 2) && in((px[b] + px[c]) / 2, (py[b] + py[c]) / 2)
                && in((px[c] + px[a]) / 2, (py[c] + py[a]) / 2);
    }

    private float len(int a, int b) {
        float dx = px[a] - px[b], dy = py[a] - py[b];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private boolean in(float x, float y) {
        int ix = Math.max(0, Math.min(mw - 1, (int) x)), iy = Math.max(0, Math.min(mh - 1, (int) y));
        return dist[iy * mw + ix] > -0.5f;
    }

    private void addEdge(int a, int b, HashSet<Long> seen) {
        long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
        if (seen.add(key)) edges.add(new int[] {a, b});
    }

    private static void shuffle(List<Integer> list, Random rnd) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            Integer t = list.get(i);
            list.set(i, list.get(j));
            list.set(j, t);
        }
    }

    // ---------------- drawing ----------------

    private int[] x0, x1;
    private float[] fx;
    private int forWidth = -1;
    private float[] light = new float[0];

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
        float sxs = (float) w / mw, sys = (float) h / mh;
        float half = Math.max(0.55f, w / 2000f);
        float dot = half * 2.4f;
        float outlineHalf = half * 1.5f;
        if (light.length < w * count) light = new float[w * count];
        java.util.Arrays.fill(light, 0, w * count, 0f);
        // mesh edges, the extra lines, then the dots
        for (int[] e : edges) {
            drawLine(px[e[0]] * sxs, py[e[0]] * sys, px[e[1]] * sxs, py[e[1]] * sys, half, 0.8f, y, count, w);
        }
        for (float[] l : extraLines) drawLine(l[0] * sxs, l[1] * sys, l[2] * sxs, l[3] * sys, half * 0.7f, l[4], y, count, w);
        for (int i = 0; i < px.length; i++) {
            if (hidden == null || !hidden[i]) drawDot(px[i] * sxs, py[i] * sys, dot, 1f, y, count, w);
        }
        for (float[] d : extraDots) drawDot(d[0] * sxs, d[1] * sys, half * 1.1f, 0.8f, y, count, w);

        for (int row = 0; row < count; row++) {
            int yy = y + row;
            float sy = (yy + 0.5f) * mh / h - 0.5f;
            if (sy < 0) sy = 0;
            int iy = Math.min(mh - 1, (int) sy);
            int iy2 = Math.min(mh - 1, iy + 1);
            float wy = sy - iy;
            int r0 = iy * mw, r1 = iy2 * mw;
            int base = row * w;
            for (int x = 0; x < w; x++) {
                int a0 = r0 + x0[x], a1 = r0 + x1[x], b0 = r1 + x0[x], b1 = r1 + x1[x];
                float wx = fx[x];
                float dPx = lerp2(dist, a0, a1, b0, b1, wx, wy) * sxs;
                float l = light[base + x];
                if (dPx < -outlineHalf * 8 && l == 0) continue;
                float a = lerp2(alpha, a0, a1, b0, b1, wx, wy);
                float outline = line(Math.abs(dPx), outlineHalf);
                float halo = glow * 0.4f * soft(Math.abs(dPx), outlineHalf * 7) * (1 - a * 0.7f);
                float lit = Math.min(1, Math.max(outline, l * Math.max(a, 0.6f)) + halo);
                if (lit < 0.002f && a < 0.002f) continue;
                int c = rgb[base + x];
                float r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                float keep = 1 - dim * a;
                r *= keep;
                g *= keep;
                b *= keep;
                r = 255 - (255 - r) * (1 - lit * cr);
                g = 255 - (255 - g) * (1 - lit * cg);
                b = 255 - (255 - b) * (1 - lit * cb);
                rgb[base + x] = (c & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
    }

    /** Anti-aliased line from (ax, ay) to (bx, by) in copy pixels into the strip's light buffer. */
    private void drawLine(float ax, float ay, float bx, float by, float half, float strength, int y, int count, int w) {
        float pad = half + 1;
        int ry0 = Math.max(y, (int) Math.floor(Math.min(ay, by) - pad));
        int ry1 = Math.min(y + count - 1, (int) Math.ceil(Math.max(ay, by) + pad));
        if (ry0 > ry1) return;
        int rx0 = Math.max(0, (int) Math.floor(Math.min(ax, bx) - pad));
        int rx1 = Math.min(w - 1, (int) Math.ceil(Math.max(ax, bx) + pad));
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        for (int yy = ry0; yy <= ry1; yy++) {
            float cy = yy + 0.5f;
            int base = (yy - y) * w;
            for (int xx = rx0; xx <= rx1; xx++) {
                float cx = xx + 0.5f;
                float t = len2 > 0 ? ((cx - ax) * dx + (cy - ay) * dy) / len2 : 0;
                if (t < 0) t = 0;
                else if (t > 1) t = 1;
                float ex = ax + t * dx - cx, ey = ay + t * dy - cy;
                float v = line((float) Math.sqrt(ex * ex + ey * ey), half) * strength;
                if (v > light[base + xx]) light[base + xx] = v;
            }
        }
    }

    private void drawDot(float cx0, float cy0, float r, float strength, int y, int count, int w) {
        int ry0 = Math.max(y, (int) Math.floor(cy0 - r - 1)), ry1 = Math.min(y + count - 1, (int) Math.ceil(cy0 + r + 1));
        if (ry0 > ry1) return;
        int rx0 = Math.max(0, (int) Math.floor(cx0 - r - 1)), rx1 = Math.min(w - 1, (int) Math.ceil(cx0 + r + 1));
        for (int yy = ry0; yy <= ry1; yy++) {
            int base = (yy - y) * w;
            for (int xx = rx0; xx <= rx1; xx++) {
                float ex = xx + 0.5f - cx0, ey = yy + 0.5f - cy0;
                float v = line((float) Math.sqrt(ex * ex + ey * ey), r - 0.5f) * strength;
                if (v > light[base + xx]) light[base + xx] = v;
            }
        }
    }

    private static float line(float d, float half) {
        float v = half + 0.5f - d;
        return v <= 0 ? 0 : v >= 1 ? 1 : v;
    }

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

    /** Points with a minimum spacing, checked through a coarse grid. */
    private static class PointSet {
        private final float cell;
        private final int gw, gh;
        private final List<float[]>[] grid;
        private final List<float[]> all = new ArrayList<float[]>();

        @SuppressWarnings("unchecked")
        PointSet(int w, int h, float s) {
            cell = s;
            gw = (int) (w / s) + 2;
            gh = (int) (h / s) + 2;
            grid = new List[gw * gh];
        }

        boolean tryAdd(float x, float y, float min) {
            int gx = (int) (x / cell), gy = (int) (y / cell);
            int reach = (int) Math.ceil(min / cell);
            float min2 = min * min;
            for (int j = Math.max(0, gy - reach); j <= Math.min(gh - 1, gy + reach); j++) {
                for (int i = Math.max(0, gx - reach); i <= Math.min(gw - 1, gx + reach); i++) {
                    List<float[]> l = grid[j * gw + i];
                    if (l == null) continue;
                    for (float[] p : l) {
                        float dx = p[0] - x, dy = p[1] - y;
                        if (dx * dx + dy * dy < min2) return false;
                    }
                }
            }
            float[] p = {x, y};
            int k = Math.min(gh - 1, gy) * gw + Math.min(gw - 1, gx);
            if (grid[k] == null) grid[k] = new ArrayList<float[]>();
            grid[k].add(p);
            all.add(p);
            return true;
        }

        float[] xs() {
            float[] o = new float[all.size()];
            for (int i = 0; i < o.length; i++) o[i] = all.get(i)[0];
            return o;
        }

        float[] ys() {
            float[] o = new float[all.size()];
            for (int i = 0; i < o.length; i++) o[i] = all.get(i)[1];
            return o;
        }
    }
}
