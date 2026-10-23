package com.ghosts.app;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.os.Process;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * Background queue that finds the people in each JPEG and writes a copy with them turned into glowing
 * white figures, in GHOSTS, at a half (Large) or quarter (Small) of the photo's width and height.
 * Each copy is written to a .TMP file and renamed when complete, so a cut-off run never leaves a broken JPEG.
 * The model is loaded when work arrives and freed when the queue empties.
 */
public class Ghoster {
    public interface Listener {
        /** True while the camera is busy with the card (taking a photo); writing waits meanwhile. */
        boolean cameraBusy();

        void onStatus(int queued, String stage);

        void onDone(String source, File result, int people, long millis);

        void onFailed(String source, Throwable error);
    }

    public static class Options {
        /** Model width for landscape photos (height for portrait ones): 320, 480 or 640. */
        public int modelSize = 480;
        /** Glow level, see Ghost.GLOW. */
        public int glow = 2;
        /** Copy size as a fraction of the photo: 2 = half (Large), 4 = quarter (Small). */
        public int sample = 4;
        public int threads = 4;
        public float confidence = 0.2f;
    }

    private static final String MODEL = "yolo11n-seg.bin";
    private static final long MB = 1024 * 1024;
    private static final int STRIP = 64;

    private final File outDir;
    private final AssetManager assets;
    private final Listener listener;
    private final LinkedList<File> queue = new LinkedList<File>();
    private final HashMap<File, Options> options = new HashMap<File, Options>();
    private volatile boolean cancelled;
    private Thread worker;
    /** Where the time went on the last copy, for the log. */
    public volatile String lastTiming = "";

    public Ghoster(File outDir, AssetManager assets, Listener listener) {
        this.outDir = outDir;
        this.assets = assets;
        this.listener = listener;
        cleanTemp();
    }

    public synchronized void add(File jpeg, Options opt) {
        if (queue.contains(jpeg)) return;
        queue.add(jpeg);
        options.put(jpeg, opt);
        listener.onStatus(queue.size(), "");
        if (worker == null) {
            cancelled = false;
            worker = new Thread(new Runnable() {
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    loop();
                }
            }, "ghoster");
            worker.start();
        }
    }

    public synchronized int pending() {
        return queue.size();
    }

    /** Stops after the current step; the unfinished copy is deleted. */
    public void cancel() {
        cancelled = true;
        synchronized (this) {
            queue.clear();
            options.clear();
        }
    }

    /** Runs on the worker thread; the model belongs to this run, so a later worker never shares it. */
    private void loop() {
        Yseg[] model = new Yseg[1];
        try {
            while (true) {
                File next;
                Options opt;
                synchronized (this) {
                    if (queue.isEmpty() || cancelled) {
                        worker = null;
                        queue.clear();
                        listener.onStatus(0, "");
                        return;
                    }
                    next = queue.peek();
                    opt = options.get(next);
                }
                long t0 = System.currentTimeMillis();
                try {
                    int[] people = new int[1];
                    File result = ghost(next, opt, people, model);
                    listener.onDone(next.getName(), result, people[0], System.currentTimeMillis() - t0);
                } catch (Throwable t) {
                    if (!cancelled) listener.onFailed(next.getName(), t);
                }
                synchronized (this) {
                    queue.remove(next);
                    options.remove(next);
                    listener.onStatus(queue.size(), "");
                }
            }
        } finally {
            if (model[0] != null) model[0].free();
        }
    }

    private void stage(String s) {
        listener.onStatus(pending(), s);
    }

    /** Makes the ghost copy of src; returns it, or null when no one was found (nothing is written). */
    private File ghost(File src, Options opt, int[] peopleOut, Yseg[] model) throws IOException {
        waitUntilStable(src);
        long t0 = System.currentTimeMillis();

        // 1. a small upright copy for the model
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(src.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
        }
        int deg = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), bounds);
        int pw = bounds.outWidth, ph = bounds.outHeight;
        if (pw <= 0 || ph <= 0) throw new IOException("cannot read " + src.getName());
        boolean sideways = deg == 90 || deg == 270;
        int dispW = sideways ? ph : pw, dispH = sideways ? pw : ph;
        int mw, mh;
        if (dispW >= dispH) {
            mw = opt.modelSize;
            mh = round32((float) opt.modelSize * dispH / dispW);
        } else {
            mh = opt.modelSize;
            mw = round32((float) opt.modelSize * dispW / dispH);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (Math.max(pw, ph) / (o.inSampleSize * 2) >= Math.max(mw, mh)) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(src.getPath(), o);
        if (b == null) throw new IOException("cannot decode " + src.getName());
        Matrix m = new Matrix();
        if (deg != 0) m.postRotate(deg);
        float rw = sideways ? b.getHeight() : b.getWidth(), rh = sideways ? b.getWidth() : b.getHeight();
        m.postScale(mw / rw, mh / rh);
        Bitmap small = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (small != b) b.recycle();
        int[] px = new int[mw * mh];
        small.getPixels(px, 0, mw, 0, 0, Math.min(mw, small.getWidth()), Math.min(mh, small.getHeight()));
        small.recycle();
        byte[] rgb = new byte[mw * mh * 3];
        for (int i = 0; i < px.length; i++) {
            rgb[i * 3] = (byte) (px[i] >> 16);
            rgb[i * 3 + 1] = (byte) (px[i] >> 8);
            rgb[i * 3 + 2] = (byte) px[i];
        }
        px = null;
        long t1 = System.currentTimeMillis();
        if (cancelled) throw new IOException("cancelled");

        // 2. find the people
        stage("Finding people");
        long tl = System.currentTimeMillis();
        if (model[0] == null) model[0] = new Yseg(assets, MODEL);
        long loadMs = System.currentTimeMillis() - tl;
        byte[] mask = new byte[mw * mh];
        double[] times = new double[9];
        int people = model[0].run(rgb, mw, mh, opt.threads, opt.confidence, mask, times);
        rgb = null;
        long t2 = System.currentTimeMillis();
        peopleOut[0] = people;
        StringBuilder sb = new StringBuilder();
        sb.append(mw).append('x').append(mh).append(" model ").append(t2 - tl - loadMs).append(" ms (")
                .append(opt.threads).append(" threads");
        for (int i = 1; i < times.length; i++) {
            if (times[i] >= 1) sb.append(", ").append(Yseg.OP_NAMES[i]).append(' ').append(Math.round(times[i]));
        }
        sb.append("), load ").append(loadMs).append(" ms, prepare ").append(t1 - t0).append(" ms");
        if (people == 0) {
            lastTiming = sb.toString();
            return null;
        }
        if (cancelled) throw new IOException("cancelled");

        // 3. the mask back in the file's own pixel orientation, then the copy
        int qw = sideways ? mh : mw, qh = sideways ? mw : mh;
        byte[] pmask = unrotate(mask, mw, mh, deg);
        Ghost ghost = new Ghost(pmask, qw, qh, opt.glow);
        stage("Saving copy");
        outDir.mkdirs();
        File tmp = new File(outDir, outName(src.getName()) + ".TMP");
        File done = new File(outDir, outName(src.getName()) + ".JPG");
        byte[] exif = null;
        try {
            exif = Jpeg.readExif(new FileInputStream(src));
        } catch (IOException ignored) {
        }
        boolean ok = false;
        String how;
        try {
            how = writeNative(src, tmp, opt.sample, ghost, exif);
            if (how == null) how = writeStreaming(src, tmp, opt.sample, ghost, exif);
            ok = true;
        } finally {
            if (!ok) tmp.delete();
        }
        done.delete();
        if (!tmp.renameTo(done)) throw new IOException("could not rename " + tmp.getName());
        lastTiming = sb + ", " + how + String.format(", %.0f%% of frame", ghost.coverage() * 100);
        return done;
    }

    private static int round32(float v) {
        return Math.max(32, Math.round(v / 32f) * 32);
    }

    /** The mask of the upright photo (w x h) turned back to the file's pixel orientation. */
    private static byte[] unrotate(byte[] d, int w, int h, int deg) {
        if (deg == 0) return d;
        byte[] out = new byte[w * h];
        if (deg == 180) {
            for (int i = 0; i < w * h; i++) out[i] = d[w * h - 1 - i];
            return out;
        }
        // the file is h wide and w tall; deg is how far it was turned to stand upright
        int fw = h, fh = w;
        for (int y = 0; y < fh; y++) {
            for (int x = 0; x < fw; x++) {
                int ux, uy; // where (x, y) landed in the upright image
                if (deg == 90) {
                    ux = fh - 1 - y;
                    uy = x;
                } else {
                    ux = y;
                    uy = fw - 1 - x;
                }
                out[y * fw + x] = d[uy * w + ux];
            }
        }
        return out;
    }

    private boolean waitForCard() {
        while (!cancelled && listener.cameraBusy()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !cancelled;
    }

    /**
     * Fast path: the camera's native decoder and encoder with the whole copy in one bitmap.
     * Returns null (and leaves nothing behind) when there isn't enough memory, so the caller streams instead.
     */
    private String writeNative(File src, File tmp, int sample, Ghost ghost, byte[] exif) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), bounds);
        long bytes = (long) (bounds.outWidth / sample) * (bounds.outHeight / sample) * 4;
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (free < bytes * 2 + 4 * MB) return null;
        long t0 = System.currentTimeMillis();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap decoded = null;
        BitmapRegionDecoder region = null;
        try {
            // the region decoder is noticeably quicker than decodeFile on the camera
            region = BitmapRegionDecoder.newInstance(src.getPath(), false);
            if (region != null) decoded = region.decodeRegion(new Rect(0, 0, region.getWidth(), region.getHeight()), o);
        } catch (OutOfMemoryError e) {
            decoded = null;
        } finally {
            if (region != null) region.recycle();
        }
        if (decoded == null) {
            try {
                decoded = BitmapFactory.decodeFile(src.getPath(), o);
            } catch (OutOfMemoryError e) {
                decoded = null;
            }
        }
        if (decoded == null) return null;
        Bitmap full;
        if (decoded.isMutable()) {
            full = decoded;
        } else {
            try {
                full = decoded.copy(Bitmap.Config.ARGB_8888, true);
            } catch (OutOfMemoryError e) {
                full = null;
            }
            decoded.recycle();
            if (full == null) return null;
        }
        try {
            long t1 = System.currentTimeMillis();
            int w = full.getWidth(), h = full.getHeight();
            int[] rows = new int[w * STRIP];
            for (int y = 0; y < h; y += STRIP) {
                if (cancelled) throw new IOException("cancelled");
                int n = Math.min(STRIP, h - y);
                full.getPixels(rows, 0, w, 0, y, w, n);
                ghost.apply(rows, y, n, w, h);
                full.setPixels(rows, 0, w, 0, y, w, n);
            }
            long t2 = System.currentTimeMillis();
            if (!waitForCard()) throw new IOException("cancelled");
            OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024);
            try {
                // the camera's encoder writes its own start marker; put the original Exif right after ours
                os.write(0xFF);
                os.write(0xD8);
                if (exif != null) os.write(exif);
                full.compress(Bitmap.CompressFormat.JPEG, 92, new SkipStart(os));
            } finally {
                os.close();
            }
            long t3 = System.currentTimeMillis();
            return w + "x" + h + " native: decode " + (t1 - t0) + " ms, ghost " + (t2 - t1) + " ms, encode " + (t3 - t2) + " ms";
        } finally {
            full.recycle();
        }
    }

    /** Low-memory path: decodes and encodes in strips so the copy never has to fit in memory at once. */
    private String writeStreaming(File src, File tmp, int sample, Ghost ghost, byte[] exif) throws IOException {
        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(src.getPath(), false);
        if (decoder == null) throw new IOException("cannot decode " + src.getName());
        int fullW = decoder.getWidth(), fullH = decoder.getHeight();
        int w = fullW / sample, h = fullH / sample;
        long t0 = System.currentTimeMillis();
        OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024);
        try {
            Jpeg.Writer jw = new Jpeg.Writer(os, w, h, 92, exif);
            int[] rows = new int[w * STRIP];
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            for (int y = 0; y < h; y += STRIP) {
                if (!waitForCard()) throw new IOException("cancelled");
                int count = Math.min(STRIP, h - y);
                Bitmap b = decoder.decodeRegion(new Rect(0, y * sample, fullW, Math.min(fullH, (y + count) * sample)), o);
                if (b == null) throw new IOException("region decode failed at row " + y);
                int bw = Math.min(w, b.getWidth()), bh = Math.min(count, b.getHeight());
                for (int row = 0; row < count; row++) {
                    b.getPixels(rows, row * w, w, 0, Math.min(row, bh - 1), bw, 1);
                    for (int x = bw; x < w; x++) rows[row * w + x] = rows[row * w + bw - 1];
                }
                b.recycle();
                ghost.apply(rows, y, count, w, h);
                jw.writeRows(rows, count);
            }
            jw.finish();
        } finally {
            os.close();
            decoder.recycle();
        }
        return w + "x" + h + " streaming " + (System.currentTimeMillis() - t0) + " ms";
    }

    /** Drops the first two bytes (the encoder's own start-of-image marker). */
    private static class SkipStart extends java.io.FilterOutputStream {
        private int skip = 2;

        SkipStart(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            if (skip > 0) {
                skip--;
                return;
            }
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (skip > 0 && len > 0) {
                skip--;
                off++;
                len--;
            }
            if (len > 0) out.write(b, off, len);
        }
    }

    /** The camera may still be writing the JPEG when it is queued. */
    private void waitUntilStable(File f) throws IOException {
        long last = -1;
        for (int i = 0; i < 60 && !cancelled; i++) {
            long size = f.length();
            if (size > 0 && size == last && !listener.cameraBusy()) return;
            last = size;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        }
        if (cancelled) throw new IOException("cancelled");
    }

    /** 8.3-safe output name: "GH" plus the photo's number, e.g. DSC05218.JPG -> GH05218. */
    static String outName(String photo) {
        StringBuilder digits = new StringBuilder();
        for (char c : photo.toCharArray()) {
            if (c == '.') break;
            if (Character.isDigit(c)) digits.append(c);
        }
        String d = digits.length() > 6 ? digits.substring(digits.length() - 6) : digits.toString();
        return "GH" + (d.length() > 0 ? d : "0");
    }

    private void cleanTemp() {
        File[] files = outDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().toUpperCase().endsWith(".TMP")) f.delete();
        }
    }
}
