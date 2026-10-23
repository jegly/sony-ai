package com.ghosts.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Delaunay triangulation (Bowyer-Watson). Points are added in order of x; a triangle whose circumcircle lies
 * wholly to the left of the current point can never change again, so it is set aside, which keeps the
 * search short.
 */
public class Delaunay {
    private static class Tri {
        final int a, b, c;
        final double cx, cy, r2;

        Tri(int a, int b, int c, double[] x, double[] y) {
            this.a = a;
            this.b = b;
            this.c = c;
            double ax = x[a], ay = y[a], bx = x[b], by = y[b], qx = x[c], qy = y[c];
            double d = 2 * (ax * (by - qy) + bx * (qy - ay) + qx * (ay - by));
            if (Math.abs(d) < 1e-12) {
                cx = (ax + bx + qx) / 3;
                cy = (ay + by + qy) / 3;
                r2 = Double.MAX_VALUE;
            } else {
                double a2 = ax * ax + ay * ay, b2 = bx * bx + by * by, c2 = qx * qx + qy * qy;
                cx = (a2 * (by - qy) + b2 * (qy - ay) + c2 * (ay - by)) / d;
                cy = (a2 * (qx - bx) + b2 * (ax - qx) + c2 * (bx - ax)) / d;
                r2 = (ax - cx) * (ax - cx) + (ay - cy) * (ay - cy);
            }
        }
    }

    /** Returns triangles as vertex index triples. */
    public static int[] triangulate(float[] px, float[] py) {
        final int n = px.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final float[] fx = px;
        Arrays.sort(order, new java.util.Comparator<Integer>() {
            public int compare(Integer p, Integer q) {
                return Float.compare(fx[p], fx[q]);
            }
        });
        // points plus a big triangle around them all
        double[] x = new double[n + 3], y = new double[n + 3];
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            x[i] = px[i];
            y[i] = py[i];
            minX = Math.min(minX, x[i]);
            minY = Math.min(minY, y[i]);
            maxX = Math.max(maxX, x[i]);
            maxY = Math.max(maxY, y[i]);
        }
        double span = Math.max(maxX - minX, maxY - minY) + 1, mx = (minX + maxX) / 2, my = (minY + maxY) / 2;
        x[n] = mx - 20 * span;
        y[n] = my - span;
        x[n + 1] = mx;
        y[n + 1] = my + 20 * span;
        x[n + 2] = mx + 20 * span;
        y[n + 2] = my - span;

        List<Tri> open = new ArrayList<Tri>();
        List<Tri> done = new ArrayList<Tri>();
        open.add(new Tri(n, n + 1, n + 2, x, y));
        HashMap<Long, int[]> edges = new HashMap<Long, int[]>();
        List<Tri> keep = new ArrayList<Tri>();
        for (int k = 0; k < n; k++) {
            int p = order[k];
            double qx = x[p], qy = y[p];
            edges.clear();
            keep.clear();
            for (Tri t : open) {
                double dx = t.cx - qx;
                if (dx < 0 && dx * dx > t.r2) {
                    done.add(t); // entirely to the left: finished
                    continue;
                }
                double dy = t.cy - qy;
                if (dx * dx + dy * dy < t.r2) {
                    edge(edges, t.a, t.b);
                    edge(edges, t.b, t.c);
                    edge(edges, t.c, t.a);
                } else {
                    keep.add(t);
                }
            }
            List<Tri> next = new ArrayList<Tri>(keep);
            for (int[] e : edges.values()) {
                if (e[2] == 1) next.add(new Tri(e[0], e[1], p, x, y));
            }
            open = next;
        }
        done.addAll(open);
        int count = 0;
        for (Tri t : done) if (t.a < n && t.b < n && t.c < n) count++;
        int[] out = new int[count * 3];
        int o = 0;
        for (Tri t : done) {
            if (t.a < n && t.b < n && t.c < n) {
                out[o++] = t.a;
                out[o++] = t.b;
                out[o++] = t.c;
            }
        }
        return out;
    }

    /** Counts an edge of a removed triangle; edges seen once form the hole's border. */
    private static void edge(HashMap<Long, int[]> edges, int a, int b) {
        long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
        int[] e = edges.get(key);
        if (e == null) edges.put(key, new int[] {a, b, 1});
        else e[2]++;
    }
}
