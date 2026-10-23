package com.ghosts.app;

import android.content.res.AssetManager;

import java.io.IOException;

/** The YOLO11-seg engine (engine/yseg.c), loaded from the APK's assets. Not thread safe: use from one thread. */
public class Yseg {
    static {
        System.loadLibrary("yseg");
    }

    public static final String[] OP_NAMES = {"", "conv", "add", "concat", "slice", "maxpool", "up2", "convt2", "attn"};

    private int handle;

    public Yseg(AssetManager assets, String name) throws IOException {
        handle = nativeLoad(assets, name);
    }

    /**
     * Mask (0..255 per pixel, w x h) of the wanted COCO classes (classes[c] != 0; null = people) for an RGB
     * image; returns how many were found.
     */
    public int run(byte[] rgb, int w, int h, int threads, float conf, byte[] classes, byte[] mask, double[] timings) throws IOException {
        if (handle == 0) throw new IOException("model was freed");
        return nativeRun(handle, rgb, w, h, threads, conf, classes, mask, timings);
    }

    /** Boxes of the people found by the last run: x1 y1 x2 y2 score each, in model input pixels. */
    public float[] boxes() {
        return handle == 0 ? new float[0] : nativeBoxes(handle);
    }

    /** Runs a plain model (a face model) and returns all its outputs one after another. */
    public float[] runRaw(byte[] rgb, int w, int h, int outputFloats) throws IOException {
        if (handle == 0) throw new IOException("model was freed");
        float[] out = new float[outputFloats];
        nativeRunRaw(handle, rgb, w, h, out);
        return out;
    }

    public void free() {
        if (handle != 0) nativeFree(handle);
        handle = 0;
    }

    private static native int nativeLoad(AssetManager assets, String name) throws IOException;

    private static native void nativeFree(int handle);

    private static native float[] nativeBoxes(int handle);

    private static native int nativeRunRaw(int handle, byte[] rgb, int w, int h, float[] out) throws IOException;

    private static native int nativeRun(int handle, byte[] rgb, int w, int h, int threads, float conf, byte[] classes,
            byte[] mask, double[] timings) throws IOException;
}
