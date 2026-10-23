package com.ghosts.app;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The face-mesh part of the Mesh look, using MediaPipe's face detector (BlazeFace short range) and face
 * landmark model (478 points), both run by the camera's engine. For each person the app crops the head
 * area, finds the face in it, crops the face upright, and gets the landmarks, which are joined with
 * MediaPipe's own triangle map (Apache 2.0).
 *
 * This class holds the maths that doesn't need Android: cropping with an affine map, decoding the
 * detector's anchors, and mapping the landmarks back. Plain Java so it can be checked on a computer.
 */
public class FaceMesh {
    public static final int DET_SIZE = 128, LM_SIZE = 256, POINTS = 478;
    /** Floats the detector and landmark models output in total. */
    public static final int DET_FLOATS = 2 * 16 * 16 + 6 * 8 * 8 + 32 * 16 * 16 + 96 * 8 * 8, LM_FLOATS = POINTS * 3 + 2;

    /** The mesh's edges (point index pairs), the face outline in loop order, and the eye/lip/brow edges. */
    public final int[] edges, oval, features;

    public FaceMesh(InputStream asset) throws IOException {
        DataInputStream in = new DataInputStream(asset);
        try {
            edges = readPairs(in);
            int n = readShort(in);
            oval = new int[n];
            for (int i = 0; i < n; i++) oval[i] = readShort(in);
            features = readPairs(in);
        } finally {
            in.close();
        }
    }

    private static int[] readPairs(DataInputStream in) throws IOException {
        int n = readShort(in);
        int[] e = new int[n * 2];
        for (int i = 0; i < e.length; i++) e[i] = readShort(in);
        return e;
    }

    private static int readShort(DataInputStream in) throws IOException {
        int lo = in.readUnsignedByte(), hi = in.readUnsignedByte();
        return (short) (lo | (hi << 8));
    }

    /** A face found by the detector, in detector input pixels (0..128). Keypoints: eyes, nose, mouth, ears. */
    public static class Face {
        public float score, cx, cy, w, h;
        public final float[] kp = new float[12];
    }

    /**
     * Crops src (packed RGB, sw x sh) into an RGB byte image ow x oh. m maps output pixel centres to source
     * pixels: sx = m[0] x + m[1] y + m[2], sy = m[3] x + m[4] y + m[5]. Bilinear, black outside.
     */
    public static byte[] warp(int[] src, int sw, int sh, double[] m, int ow, int oh) {
        byte[] out = new byte[ow * oh * 3];
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                double fx = m[0] * (x + 0.5) + m[1] * (y + 0.5) + m[2] - 0.5;
                double fy = m[3] * (x + 0.5) + m[4] * (y + 0.5) + m[5] - 0.5;
                int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
                double ax = fx - ix, ay = fy - iy;
                float r = 0, g = 0, b = 0;
                for (int k = 0; k < 4; k++) {
                    int px = ix + (k & 1), py = iy + (k >> 1);
                    double wgt = ((k & 1) == 1 ? ax : 1 - ax) * ((k >> 1) == 1 ? ay : 1 - ay);
                    if (px < 0 || py < 0 || px >= sw || py >= sh || wgt == 0) continue;
                    int c = src[py * sw + px];
                    r += ((c >> 16) & 255) * wgt;
                    g += ((c >> 8) & 255) * wgt;
                    b += (c & 255) * wgt;
                }
                int o = (y * ow + x) * 3;
                out[o] = (byte) Math.min(255, Math.round(r));
                out[o + 1] = (byte) Math.min(255, Math.round(g));
                out[o + 2] = (byte) Math.min(255, Math.round(b));
            }
        }
        return out;
    }

    /** Scales a square crop (side x side, from cx0, cy0) of src to size x size. */
    public static double[] squareCrop(double x0, double y0, double side, int size) {
        double s = side / size;
        return new double[] {s, 0, x0, 0, s, y0};
    }

    /**
     * The best face in the detector's outputs (classificators at strides 8 and 16, then regressors, each CHW),
     * or null when none scores at least minScore.
     */
    public static Face detect(float[] out, float minScore) {
        int cls8 = 0, cls16 = cls8 + 2 * 256, reg8 = cls16 + 6 * 64, reg16 = reg8 + 32 * 256;
        int best = -1, bestLayer = 0, bestA = 0, bestCell = 0;
        float bestLogit = -1e9f;
        for (int a = 0; a < 2; a++) {
            for (int cell = 0; cell < 256; cell++) {
                float v = out[cls8 + a * 256 + cell];
                if (v > bestLogit) {
                    bestLogit = v;
                    best = 0;
                    bestLayer = 8;
                    bestA = a;
                    bestCell = cell;
                }
            }
        }
        for (int a = 0; a < 6; a++) {
            for (int cell = 0; cell < 64; cell++) {
                float v = out[cls16 + a * 64 + cell];
                if (v > bestLogit) {
                    bestLogit = v;
                    best = 0;
                    bestLayer = 16;
                    bestA = a;
                    bestCell = cell;
                }
            }
        }
        if (best < 0) return null;
        float score = (float) (1 / (1 + Math.exp(-Math.max(-100, Math.min(100, bestLogit)))));
        if (score < minScore) return null;
        int grid = bestLayer == 8 ? 16 : 8;
        int base = bestLayer == 8 ? reg8 : reg16;
        int plane = grid * grid;
        float ax = (bestCell % grid + 0.5f) / grid * DET_SIZE, ay = (bestCell / grid + 0.5f) / grid * DET_SIZE;
        Face f = new Face();
        f.score = score;
        float[] r = new float[16];
        for (int v = 0; v < 16; v++) r[v] = out[base + (bestA * 16 + v) * plane + bestCell];
        f.cx = ax + r[0];
        f.cy = ay + r[1];
        f.w = r[2];
        f.h = r[3];
        for (int k = 0; k < 6; k++) {
            f.kp[2 * k] = ax + r[4 + 2 * k];
            f.kp[2 * k + 1] = ay + r[5 + 2 * k];
        }
        return f;
    }

    /**
     * MediaPipe's face crop for the landmark model: centred on the detection, 1.5 times its size, turned so
     * the eyes are level. f is in source pixels here. Returns the map from landmark input pixels to source.
     */
    public static double[] landmarkCrop(Face f) {
        double angle = -Math.atan2(-(f.kp[3] - f.kp[1]), f.kp[2] - f.kp[0]);
        double size = Math.max(f.w, f.h) * 1.5;
        double s = size / LM_SIZE, c = Math.cos(angle) * s, sn = Math.sin(angle) * s;
        // source = centre + R(angle) * (p - 128) * s
        return new double[] {c, -sn, f.cx - c * LM_SIZE / 2 + sn * LM_SIZE / 2, sn, c, f.cy - sn * LM_SIZE / 2 - c * LM_SIZE / 2};
    }

    /** Face presence from the landmark model's outputs, 0..1. */
    public static float presence(float[] out) {
        return (float) (1 / (1 + Math.exp(-out[POINTS * 3])));
    }

    /** The landmarks in source pixels (x, y pairs), given the crop map from landmarkCrop. */
    public static float[] landmarks(float[] out, double[] m) {
        float[] p = new float[POINTS * 2];
        for (int i = 0; i < POINTS; i++) {
            double u = out[i * 3], v = out[i * 3 + 1];
            p[2 * i] = (float) (m[0] * u + m[1] * v + m[2]);
            p[2 * i + 1] = (float) (m[3] * u + m[4] * v + m[5]);
        }
        return p;
    }

    /** Converts a face found in a DET_SIZE crop (cropped by squareCrop map m) to source pixels. */
    public static Face toSource(Face f, double[] m) {
        Face o = new Face();
        o.score = f.score;
        o.cx = (float) (m[0] * f.cx + m[2]);
        o.cy = (float) (m[4] * f.cy + m[5]);
        o.w = (float) (m[0] * f.w);
        o.h = (float) (m[4] * f.h);
        for (int k = 0; k < 6; k++) {
            o.kp[2 * k] = (float) (m[0] * f.kp[2 * k] + m[2]);
            o.kp[2 * k + 1] = (float) (m[4] * f.kp[2 * k + 1] + m[5]);
        }
        return o;
    }
}
