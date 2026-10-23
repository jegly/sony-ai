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
        /** Ghost.SOLID, ETHEREAL or DREAMY. */
        public int look = Ghost.ETHEREAL;
        /** How solid the figures are, 0..1. */
        public float opacity = 0.85f;
        /** Colour of the figures' light, see Ghost.COLOURS. */
        public int colour = 0;
        /** The effect on the subjects (PEOPLE_* below); PEOPLE_OFF applies only the diffusion filter. */
        public int people = PEOPLE_GHOSTS;
        /** Which COCO classes are subjects (80 flags; null = people). */
        public byte[] classes;
        /** Face style for meshed faces, see Mesh.FACE_STYLES. */
        public int faceStyle = Mesh.FACE_MESH;
        /** For Crowd remover and Trails: the set of shots, oldest first (the job is keyed by the last one). */
        public File[] burst;
        /** Diffusion filter setting, see Diffusion.NAMES (0 = off). */
        public int diffusion = 0;
        /** Copy size as a fraction of the photo: 2 = half (Large), 4 = quarter (Small). */
        public int sample = 4;
        public int threads = 1;
        public float confidence = 0.2f;
    }

    public static final int PEOPLE_OFF = 0, PEOPLE_GHOSTS = 1, PEOPLE_WIREFRAME = 2, PEOPLE_CONTOURS = 3, PEOPLE_FACES = 4,
            PEOPLE_POINTS = 5, PEOPLE_NOIR = 6, PEOPLE_BOKEH = 7, PEOPLE_RIM = 8, PEOPLE_GLITCH = 9, PEOPLE_THERMAL = 10,
            PEOPLE_COMIC = 11, PEOPLE_SKELETON = 12, PEOPLE_CROWD = 13, PEOPLE_TRAILS = 14;
    /** Effects done by Scene, in Scene's order from PEOPLE_COLOUR_POP. */
    private static final int SCENE_FIRST = PEOPLE_NOIR, SCENE_LAST = PEOPLE_COMIC;
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
        Yseg[] model = new Yseg[4]; // people, face detector, face landmarks, pose
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
                    File result;
                    if (opt.burst != null) {
                        result = multi(opt.burst, opt);
                        people[0] = -opt.burst.length;
                    } else {
                        result = ghost(next, opt, people, model);
                    }
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
            for (Yseg y : model) if (y != null) y.free();
        }
    }

    private void stage(String s) {
        listener.onStatus(pending(), s);
    }

    /** One effect applied to strips of the copy. */
    private interface Effect {
        void apply(int[] rgb, int y, int count, int w, int h);
    }

    /**
     * Makes the copy of src; returns it, or null when it would be unchanged (ghosts only, and no one was
     * found). peopleOut gets the number of people, or -1 when people weren't looked for.
     */
    private File ghost(File src, Options opt, int[] peopleOut, Yseg[] model) throws IOException {
        waitUntilStable(src);
        long t0 = System.currentTimeMillis();

        // 1. the photo: decoded once at copy size when it fits in memory, and shrunk upright for the model
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
        Bitmap full = decodeCopy(src, pw, ph, opt.sample);
        long t1 = System.currentTimeMillis();
        Bitmap b = full;
        if (b == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            while (Math.max(pw, ph) / (o.inSampleSize * 2) >= Math.max(mw, mh)) o.inSampleSize *= 2;
            b = BitmapFactory.decodeFile(src.getPath(), o);
            if (b == null) throw new IOException("cannot decode " + src.getName());
        }
        try {
            Matrix m = new Matrix();
            if (deg != 0) m.postRotate(deg);
            float rw = sideways ? b.getHeight() : b.getWidth(), rh = sideways ? b.getWidth() : b.getHeight();
            m.postScale(mw / rw, mh / rh);
            Bitmap small = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (b != full) b.recycle();
            int[] px = new int[mw * mh];
            small.getPixels(px, 0, mw, 0, 0, Math.min(mw, small.getWidth()), Math.min(mh, small.getHeight()));
            small.recycle();
            byte[] rgb = new byte[mw * mh * 3];
            for (int i = 0; i < px.length; i++) {
                rgb[i * 3] = (byte) (px[i] >> 16);
                rgb[i * 3 + 1] = (byte) (px[i] >> 8);
                rgb[i * 3 + 2] = (byte) px[i];
            }
            long t2 = System.currentTimeMillis();
            if (cancelled) throw new IOException("cancelled");

            // 2. find the people
            StringBuilder sb = new StringBuilder();
            sb.append(mw).append('x').append(mh);
            byte[] mask = null;
            int people = -1;
            if (opt.people != PEOPLE_OFF && opt.people != PEOPLE_FACES && opt.people != PEOPLE_SKELETON) {
                stage("Finding people");
                long tl = System.currentTimeMillis();
                if (model[0] == null) model[0] = new Yseg(assets, MODEL);
                long loadMs = System.currentTimeMillis() - tl;
                mask = new byte[mw * mh];
                double[] times = new double[9];
                people = model[0].run(rgb, mw, mh, opt.threads, opt.confidence, opt.classes, mask, times);
                long t3 = System.currentTimeMillis();
                sb.append(" model ").append(t3 - tl - loadMs).append(" ms (").append(opt.threads)
                        .append(opt.threads == 1 ? " thread" : " threads");
                for (int i = 1; i < times.length; i++) {
                    if (times[i] >= 1) sb.append(", ").append(Yseg.OP_NAMES[i]).append(' ').append(Math.round(times[i]));
                }
                sb.append("), load ").append(loadMs).append(" ms");
            }
            rgb = null;
            sb.append(", decode ").append(t1 - t0).append(" ms, shrink ").append(t2 - t1).append(" ms");
            peopleOut[0] = people;
            Skeleton skeletonV = null;
            if (opt.people == PEOPLE_SKELETON) {
                stage("Finding poses");
                long tp = System.currentTimeMillis();
                if (model[3] == null) model[3] = new Yseg(assets, "yolo11n-pose.bin");
                float[] poses = model[3].pose(rgb, mw, mh, 0.3f);
                skeletonV = new Skeleton(opt.opacity, opt.glow, Ghost.tint(opt.colour));
                float aspect = sideways ? (float) mh / mw : (float) mw / mh; // the copy's width / height
                for (int p = 0; p + 55 < poses.length; p += 56) {
                    float[] kp = new float[51];
                    for (int k = 0; k < 17; k++) {
                        float[] f = uprightToFileFraction(poses[p + 5 + 3 * k] / mw, poses[p + 6 + 3 * k] / mh, deg);
                        kp[3 * k] = f[0];
                        kp[3 * k + 1] = f[1];
                        kp[3 * k + 2] = poses[p + 7 + 3 * k];
                    }
                    // the person's size as a fraction of the copy's height
                    float size = sideways ? (poses[p + 3] - poses[p + 1]) / mh * ((float) mh / mw) : (poses[p + 3] - poses[p + 1]) / mh;
                    skeletonV.addPerson(kp, size, aspect);
                }
                people = skeletonV.people();
                sb.append(", pose ").append(System.currentTimeMillis() - tp).append(" ms");
            }
            Mesh faceOnly = null;
            if (opt.people == PEOPLE_FACES) {
                // faces only: no people model, the face detector scans the whole frame
                int qw0 = sideways ? mh : mw, qh0 = sideways ? mw : mh;
                faceOnly = new Mesh(new byte[qw0 * qh0], qw0, qh0, 0, opt.glow, Ghost.tint(opt.colour), 0);
                faceOnly.setFaceDim(opt.opacity * 0.5f);
                sb.append(", ").append(addFaces(faceOnly, model, null, mw, deg, full, src, opt.sample, qw0, opt.faceStyle));
                people = faceOnly.faces();
            }
            peopleOut[0] = people;
            if (people == 0 && opt.diffusion == 0) {
                lastTiming = sb.toString();
                return null;
            }
            if (cancelled) throw new IOException("cancelled");

            // 3. the mask and the small photo back in the file's own pixel orientation, then the copy
            int qw = sideways ? mh : mw, qh = sideways ? mw : mh;
            int[] index = uprightIndex(mw, mh, deg);
            int[] ppx = new int[px.length];
            for (int i = 0; i < index.length; i++) ppx[i] = px[index[i]];
            Ghost ghostV = null;
            Wireframe wireV = null;
            Scene sceneV = null;
            Mesh meshV = faceOnly;
            if (people > 0 && faceOnly == null) {
                byte[] pmask = new byte[mask.length];
                for (int i = 0; i < index.length; i++) pmask[i] = mask[index[i]];
                if (opt.people == PEOPLE_CONTOURS) {
                    wireV = new Wireframe(pmask, qw, qh, opt.opacity, opt.glow, Ghost.tint(opt.colour));
                } else if (opt.people == PEOPLE_WIREFRAME || opt.people == PEOPLE_POINTS) {
                    long tm = System.currentTimeMillis();
                    boolean points = opt.people == PEOPLE_POINTS;
                    meshV = new Mesh(pmask, qw, qh, opt.opacity, opt.glow, Ghost.tint(opt.colour), src.getName().hashCode(), points);
                    sb.append(", mesh ").append(meshV.points()).append(" points in ").append(System.currentTimeMillis() - tm).append(" ms");
                    // faces only make sense when the subjects are people
                    if (opt.classes == null || (opt.classes[0] != 0 && countOn(opt.classes) == 1)) {
                        try {
                            sb.append(", ").append(addFaces(meshV, model, model[0].boxes(), mw, deg, full, src, opt.sample, qw,
                                    points ? Mesh.FACE_POINTS : opt.faceStyle));
                        } catch (IOException e) {
                            if (cancelled) throw e;
                            sb.append(", faces failed: ").append(e.getMessage());
                        }
                    }
                } else if (opt.people >= SCENE_FIRST && opt.people <= SCENE_LAST) {
                    // Bokeh blurs a larger copy of the photo than the mask, for a cleaner background
                    int[] big = ppx;
                    int bigW = qw, bigH = qh;
                    if (opt.people == PEOPLE_BOKEH && full != null) {
                        bigW = Math.max(qw, Math.round(full.getWidth() * 0.4f));
                        bigH = Math.max(qh, Math.round(full.getHeight() * 0.4f));
                        Bitmap sb2 = Bitmap.createScaledBitmap(full, bigW, bigH, true);
                        big = new int[bigW * bigH];
                        sb2.getPixels(big, 0, bigW, 0, 0, bigW, bigH);
                        if (sb2 != full) sb2.recycle();
                    }
                    sceneV = new Scene(opt.people - SCENE_FIRST, pmask, qw, qh, big, bigW, bigH, opt.opacity, Ghost.tint(opt.colour),
                            src.getName().hashCode());
                } else {
                    ghostV = new Ghost(pmask, qw, qh, opt.glow, opt.look, opt.opacity, opt.colour, ppx);
                }
            }
            final Ghost ghost = ghostV;
            final Wireframe wire = wireV;
            final Mesh mesh = meshV;
            final Scene scene = sceneV;
            final Skeleton skeleton = skeletonV;
            final Diffusion diffusion;
            if (opt.diffusion > 0) {
                // the filter sits in front of the lens, so the ghosts' own light gets diffused too
                int[] lit = ppx;
                if (ghost != null || wire != null || mesh != null || scene != null || skeleton != null) {
                    lit = ppx.clone();
                    if (skeleton != null) skeleton.apply(lit, 0, qh, qw, qh);
                    if (scene != null) scene.apply(lit, 0, qh, qw, qh);
                    if (ghost != null) ghost.apply(lit, 0, qh, qw, qh);
                    if (wire != null) wire.apply(lit, 0, qh, qw, qh);
                    if (mesh != null) mesh.apply(lit, 0, qh, qw, qh);
                }
                diffusion = new Diffusion(lit, qw, qh, opt.diffusion);
            } else {
                diffusion = null;
            }
            Effect effect = new Effect() {
                public void apply(int[] rows, int y, int count, int w, int h) {
                    if (scene != null) scene.apply(rows, y, count, w, h);
                    if (skeleton != null) skeleton.apply(rows, y, count, w, h);
                    if (ghost != null) ghost.apply(rows, y, count, w, h);
                    if (wire != null) wire.apply(rows, y, count, w, h);
                    if (mesh != null) mesh.apply(rows, y, count, w, h);
                    if (diffusion != null) diffusion.apply(rows, y, count, w, h);
                }
            };
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
                how = full != null ? writeBitmap(full, tmp, effect, exif) : writeStreaming(src, tmp, opt.sample, effect, exif);
                ok = true;
            } finally {
                if (!ok) tmp.delete();
            }
            done.delete();
            if (!tmp.renameTo(done)) throw new IOException("could not rename " + tmp.getName());
            lastTiming = sb + ", " + how + (ghost != null ? String.format(", %.0f%% of frame", ghost.coverage() * 100) : "")
                    + (diffusion != null ? ", " + Diffusion.NAMES[opt.diffusion] : "");
            return done;
        } finally {
            if (full != null) full.recycle();
        }
    }

    /** Faces meshed per photo at most; each takes a few seconds on the camera. */
    private static final int MAX_FACES = 4;
    /** Smallest head crop worth looking in, in copy pixels (smaller faces wouldn't show a mesh anyway). */
    private static final int MIN_HEAD = 48;
    private FaceMesh faceMesh;

    /**
     * Looks for a face in the head area of each person and puts MediaPipe's face mesh on it. Crops come from
     * the decoded copy (or regions of the file when the copy is streamed), turned upright for the models.
     * Returns a note for the log.
     */
    private String addFaces(Mesh mesh, Yseg[] model, float[] boxes, int mw, int deg, Bitmap full, File src, int sample,
            int qw, int style) throws IOException {
        if (boxes != null && boxes.length == 0) return "";
        if (faceMesh == null) faceMesh = new FaceMesh(assets.open("facemesh.bin"));
        long t0 = System.currentTimeMillis();
        BitmapRegionDecoder dec = null;
        int fw, fh; // the copy's size, in the file's own orientation
        if (full != null) {
            fw = full.getWidth();
            fh = full.getHeight();
        } else {
            dec = BitmapRegionDecoder.newInstance(src.getPath(), false);
            if (dec == null) return "no faces (cannot decode)";
            fw = dec.getWidth() / sample;
            fh = dec.getHeight() / sample;
        }
        boolean sideways = deg == 90 || deg == 270;
        double su = (double) (sideways ? fh : fw) / mw; // upright copy pixels per model pixel
        double[] t = uprightToFile(deg, fw, fh);
        int found = 0, tried = 0;
        try {
            if (boxes == null) {
                // scan the whole frame in squares, then mesh the faces found
                java.util.List<FaceMesh.Face> faces = new java.util.ArrayList<FaceMesh.Face>();
                java.util.List<double[]> tiles = FaceMesh.tiles(sideways ? fh : fw, sideways ? fw : fh);
                stage("Finding faces");
                for (double[] cm : tiles) {
                    if (cancelled) throw new IOException("cancelled");
                    byte[] crop = crop(full, dec, sample, fw, fh, compose(t, cm), FaceMesh.DET_SIZE, FaceMesh.DET_SIZE);
                    if (model[1] == null) model[1] = new Yseg(assets, "face_detector.bin");
                    FaceMesh.Face f = FaceMesh.detect(model[1].runRaw(crop, FaceMesh.DET_SIZE, FaceMesh.DET_SIZE, FaceMesh.DET_FLOATS), 0.6f);
                    tried++;
                    if (f != null) faces.add(FaceMesh.toSource(f, cm));
                }
                for (FaceMesh.Face fs : FaceMesh.merge(faces, MAX_FACES + 2)) {
                    if (cancelled) throw new IOException("cancelled");
                    if (Math.max(fs.w, fs.h) < MIN_HEAD / 3) continue;
                    stage("Meshing faces");
                    if (meshFace(mesh, model, fs, t, full, dec, sample, fw, fh, qw, style)) found++;
                }
                return found + (found == 1 ? " face" : " faces") + " (" + tried + " squares scanned, "
                        + (System.currentTimeMillis() - t0) + " ms)";
            }
            for (int i = 0; i + 4 < boxes.length && found < MAX_FACES; i += 5) {
                if (cancelled) throw new IOException("cancelled");
                float x1 = (float) (boxes[i] * su), y1 = (float) (boxes[i + 1] * su);
                float x2 = (float) (boxes[i + 2] * su), y2 = (float) (boxes[i + 3] * su);
                float side = Math.min(x2 - x1, y2 - y1) * 0.9f;
                if (side < MIN_HEAD) continue;
                tried++;
                stage("Finding faces");
                double[] cm = FaceMesh.squareCrop((x1 + x2) / 2 - side / 2, y1 - side * 0.05f, side, FaceMesh.DET_SIZE);
                byte[] crop = crop(full, dec, sample, fw, fh, compose(t, cm), FaceMesh.DET_SIZE, FaceMesh.DET_SIZE);
                if (model[1] == null) model[1] = new Yseg(assets, "face_detector.bin");
                FaceMesh.Face f = FaceMesh.detect(model[1].runRaw(crop, FaceMesh.DET_SIZE, FaceMesh.DET_SIZE, FaceMesh.DET_FLOATS), 0.5f);
                if (f == null) continue;
                stage("Meshing faces");
                if (meshFace(mesh, model, FaceMesh.toSource(f, cm), t, full, dec, sample, fw, fh, qw, style)) found++;
            }
        } finally {
            if (dec != null) dec.recycle();
        }
        return found + (found == 1 ? " face" : " faces") + " (" + tried + " heads looked at, "
                + (System.currentTimeMillis() - t0) + " ms)";
    }

    /** Runs the landmark model on one face (upright copy pixels) and adds its mesh; false if it isn't a face after all. */
    private boolean meshFace(Mesh mesh, Yseg[] model, FaceMesh.Face fs, double[] t, Bitmap full, BitmapRegionDecoder dec,
            int sample, int fw, int fh, int qw, int style) throws IOException {
        double[] lmFile = compose(t, FaceMesh.landmarkCrop(fs));
        byte[] lcrop = crop(full, dec, sample, fw, fh, lmFile, FaceMesh.LM_SIZE, FaceMesh.LM_SIZE);
        if (model[2] == null) model[2] = new Yseg(assets, "face_landmarks.bin");
        float[] out = model[2].runRaw(lcrop, FaceMesh.LM_SIZE, FaceMesh.LM_SIZE, FaceMesh.LM_FLOATS);
        if (FaceMesh.presence(out) < 0.5f) return false;
        float[] pts = FaceMesh.landmarks(out, lmFile);
        float toMask = (float) qw / fw;
        for (int k = 0; k < pts.length; k++) pts[k] *= toMask;
        mesh.addFace(pts, faceMesh, style);
        return true;
    }

    /** A point of the upright image, as fractions of its width and height, in the file's own orientation. */
    private static float[] uprightToFileFraction(float x, float y, int deg) {
        switch (deg) {
            case 90: return new float[] {y, 1 - x};
            case 180: return new float[] {1 - x, 1 - y};
            case 270: return new float[] {1 - y, x};
            default: return new float[] {x, y};
        }
    }

    /** Maps upright copy coordinates to the file's own orientation (continuous pixel coordinates). */
    private static double[] uprightToFile(int deg, int fw, int fh) {
        switch (deg) {
            case 90: return new double[] {0, 1, 0, -1, 0, fh};
            case 180: return new double[] {-1, 0, fw, 0, -1, fh};
            case 270: return new double[] {0, -1, fw, 1, 0, 0};
            default: return new double[] {1, 0, 0, 0, 1, 0};
        }
    }

    /** t after m, both as 2x3 affine maps. */
    private static double[] compose(double[] t, double[] m) {
        return new double[] {
            t[0] * m[0] + t[1] * m[3], t[0] * m[1] + t[1] * m[4], t[0] * m[2] + t[1] * m[5] + t[2],
            t[3] * m[0] + t[4] * m[3], t[3] * m[1] + t[4] * m[4], t[3] * m[2] + t[4] * m[5] + t[5]};
    }

    /** A crop for a face model: only the pixels the map m (output -> copy pixels) touches are read. */
    private static byte[] crop(Bitmap full, BitmapRegionDecoder dec, int sample, int fw, int fh, double[] m, int ow, int oh) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            double x = (k & 1) * ow, y = (k >> 1) * oh;
            double sx = m[0] * x + m[1] * y + m[2], sy = m[3] * x + m[4] * y + m[5];
            minX = Math.min(minX, sx);
            maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy);
            maxY = Math.max(maxY, sy);
        }
        int rx = Math.max(0, (int) Math.floor(minX) - 1), ry = Math.max(0, (int) Math.floor(minY) - 1);
        int rx1 = Math.min(fw, (int) Math.ceil(maxX) + 1), ry1 = Math.min(fh, (int) Math.ceil(maxY) + 1);
        if (rx1 <= rx || ry1 <= ry) return new byte[ow * oh * 3];
        int rw = rx1 - rx, rh = ry1 - ry;
        int[] px;
        if (full != null) {
            px = new int[rw * rh];
            full.getPixels(px, 0, rw, rx, ry, rw, rh);
        } else {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = dec.decodeRegion(new Rect(rx * sample, ry * sample, rx1 * sample, ry1 * sample), o);
            if (b == null) return new byte[ow * oh * 3];
            rw = b.getWidth();
            rh = b.getHeight();
            px = new int[rw * rh];
            b.getPixels(px, 0, rw, 0, 0, rw, rh);
            b.recycle();
        }
        double[] shifted = {m[0], m[1], m[2] - rx, m[3], m[4], m[5] - ry};
        return FaceMesh.warp(px, rw, rh, shifted, ow, oh);
    }

    /**
     * Combines a set of shots (Crowd remover or Trails) into one copy, named after the last shot. Each shot
     * is decoded once to a raw temporary file, then all of them are read back a strip at a time.
     */
    private File multi(File[] shots, Options opt) throws IOException {
        for (File f : shots) waitUntilStable(f);
        long t0 = System.currentTimeMillis();
        outDir.mkdirs();
        int n = shots.length;
        File[] raws = new File[n];
        int w = -1, h = -1, sample = opt.sample;
        int[] rows = null;
        try {
            for (int i = 0; i < n; i++) {
                if (cancelled) throw new IOException("cancelled");
                stage("Reading shot " + (i + 1) + " of " + n);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(shots[i].getPath(), bounds);
                Bitmap bm = i == 0 || sample == opt.sample ? decodeCopy(shots[i], bounds.outWidth, bounds.outHeight, sample) : null;
                if (bm == null) {
                    if (i == 0) sample = opt.sample * 2; // not enough memory: smaller copies for the whole set
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    bm = BitmapFactory.decodeFile(shots[i].getPath(), o);
                }
                if (bm == null) throw new IOException("cannot decode " + shots[i].getName());
                if (w < 0) {
                    w = bm.getWidth();
                    h = bm.getHeight();
                    rows = new int[w * STRIP];
                }
                raws[i] = new File(outDir, "SHOT" + i + ".TMP");
                OutputStream os = new BufferedOutputStream(new FileOutputStream(raws[i]), 64 * 1024);
                byte[] line = new byte[w * 3];
                try {
                    for (int y = 0; y < h; y += STRIP) {
                        int c = Math.min(STRIP, h - y);
                        java.util.Arrays.fill(rows, 0);
                        int bw = Math.min(w, bm.getWidth()), bh = Math.max(0, Math.min(c, bm.getHeight() - y));
                        if (bh > 0) bm.getPixels(rows, 0, w, 0, y, bw, bh);
                        for (int r = 0; r < c; r++) {
                            for (int x = 0; x < w; x++) {
                                int p = rows[r * w + x];
                                line[3 * x] = (byte) (p >> 16);
                                line[3 * x + 1] = (byte) (p >> 8);
                                line[3 * x + 2] = (byte) p;
                            }
                            os.write(line);
                        }
                    }
                } finally {
                    os.close();
                    bm.recycle();
                }
            }
            long t1 = System.currentTimeMillis();
            stage("Combining " + n + " shots");
            MultiShot ms = new MultiShot(opt.people == PEOPLE_TRAILS ? MultiShot.TRAILS : MultiShot.CROWD, opt.opacity,
                    Ghost.tint(opt.colour));
            java.io.DataInputStream[] in = new java.io.DataInputStream[n];
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            try {
                for (int i = 0; i < n; i++) {
                    in[i] = new java.io.DataInputStream(new java.io.BufferedInputStream(new FileInputStream(raws[i]), 64 * 1024));
                }
                int[][] frames = new int[n][w * STRIP];
                byte[] line = new byte[w * 3];
                for (int y = 0; y < h; y += STRIP) {
                    if (cancelled) throw new IOException("cancelled");
                    int c = Math.min(STRIP, h - y);
                    for (int i = 0; i < n; i++) {
                        for (int r = 0; r < c; r++) {
                            in[i].readFully(line);
                            for (int x = 0; x < w; x++) {
                                frames[i][r * w + x] = 0xFF000000 | ((line[3 * x] & 255) << 16) | ((line[3 * x + 1] & 255) << 8)
                                        | (line[3 * x + 2] & 255);
                            }
                        }
                    }
                    ms.combine(frames, c, w, rows);
                    out.setPixels(rows, 0, w, 0, y, w, c);
                }
                for (java.io.DataInputStream d : in) if (d != null) d.close();
                long t2 = System.currentTimeMillis();
                stage("Saving copy");
                if (!waitForCard()) throw new IOException("cancelled");
                File last = shots[n - 1];
                File tmp = new File(outDir, outName(last.getName()) + ".TMP");
                File done = new File(outDir, outName(last.getName()) + ".JPG");
                byte[] exif = null;
                try {
                    exif = Jpeg.readExif(new FileInputStream(last));
                } catch (IOException ignored) {
                }
                boolean ok = false;
                try {
                    OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024);
                    try {
                        os.write(0xFF);
                        os.write(0xD8);
                        if (exif != null) os.write(exif);
                        out.compress(Bitmap.CompressFormat.JPEG, 92, new SkipStart(os));
                    } finally {
                        os.close();
                    }
                    ok = true;
                } finally {
                    if (!ok) tmp.delete();
                }
                done.delete();
                if (!tmp.renameTo(done)) throw new IOException("could not rename " + tmp.getName());
                lastTiming = n + " shots " + w + "x" + h + ": read " + (t1 - t0) + " ms, combine " + (t2 - t1) + " ms, save "
                        + (System.currentTimeMillis() - t2) + " ms";
                return done;
            } finally {
                for (java.io.DataInputStream d : in) {
                    if (d != null) {
                        try {
                            d.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                out.recycle();
            }
        } finally {
            for (File f : raws) if (f != null) f.delete();
        }
    }

    private static int countOn(byte[] flags) {
        int n = 0;
        for (byte b : flags) if (b != 0) n++;
        return n;
    }

    private static int round32(float v) {
        return Math.max(32, Math.round(v / 32f) * 32);
    }

    /**
     * For each pixel of the file's own orientation, its index in the upright image (w x h) that the model saw.
     * deg is how far the file was turned to stand upright.
     */
    private static int[] uprightIndex(int w, int h, int deg) {
        int[] idx = new int[w * h];
        if (deg == 0) {
            for (int i = 0; i < idx.length; i++) idx[i] = i;
        } else if (deg == 180) {
            for (int i = 0; i < idx.length; i++) idx[i] = w * h - 1 - i;
        } else {
            int fw = h, fh = w; // the file is turned on its side
            for (int y = 0; y < fh; y++) {
                for (int x = 0; x < fw; x++) {
                    int ux = deg == 90 ? fh - 1 - y : y;
                    int uy = deg == 90 ? x : fw - 1 - x;
                    idx[y * fw + x] = uy * w + ux;
                }
            }
        }
        return idx;
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
     * The photo at copy size as one editable bitmap, using the camera's native decoder. Returns null when
     * there isn't enough memory, so the copy is streamed instead.
     */
    private static Bitmap decodeCopy(File src, int pw, int ph, int sample) {
        long bytes = (long) (pw / sample) * (ph / sample) * 4;
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (free < bytes * 2 + 4 * MB) return null;
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
        } catch (IOException e) {
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
        if (decoded == null || decoded.isMutable()) return decoded;
        Bitmap copy;
        try {
            copy = decoded.copy(Bitmap.Config.ARGB_8888, true);
        } catch (OutOfMemoryError e) {
            copy = null;
        }
        decoded.recycle();
        return copy;
    }

    /** Fast path: ghosts the decoded copy in place and saves it with the camera's native encoder. */
    private String writeBitmap(Bitmap full, File tmp, Effect effect, byte[] exif) throws IOException {
        long t1 = System.currentTimeMillis();
        int w = full.getWidth(), h = full.getHeight();
        int[] rows = new int[w * STRIP];
        for (int y = 0; y < h; y += STRIP) {
            if (cancelled) throw new IOException("cancelled");
            int n = Math.min(STRIP, h - y);
            full.getPixels(rows, 0, w, 0, y, w, n);
            effect.apply(rows, y, n, w, h);
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
        return w + "x" + h + " native: ghost " + (t2 - t1) + " ms, encode " + (t3 - t2) + " ms";
    }

    /** Low-memory path: decodes and encodes in strips so the copy never has to fit in memory at once. */
    private String writeStreaming(File src, File tmp, int sample, Effect effect, byte[] exif) throws IOException {
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
                effect.apply(rows, y, count, w, h);
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
