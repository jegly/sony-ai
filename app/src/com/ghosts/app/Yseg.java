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

    /** People mask (0..255 per pixel, w x h) for an RGB image; returns how many people were found. */
    public int run(byte[] rgb, int w, int h, int threads, float conf, byte[] mask, double[] timings) throws IOException {
        if (handle == 0) throw new IOException("model was freed");
        return nativeRun(handle, rgb, w, h, threads, conf, mask, timings);
    }

    public void free() {
        if (handle != 0) nativeFree(handle);
        handle = 0;
    }

    private static native int nativeLoad(AssetManager assets, String name) throws IOException;

    private static native void nativeFree(int handle);

    private static native int nativeRun(int handle, byte[] rgb, int w, int h, int threads, float conf, byte[] mask,
            double[] timings) throws IOException;
}
