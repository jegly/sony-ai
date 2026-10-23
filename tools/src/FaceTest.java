import com.ghosts.app.FaceMesh;
import com.ghosts.app.Ghost;
import com.ghosts.app.Mesh;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Desktop check of the Mesh look with face meshes, running the C engine through engine/yseg_test:
 * FaceTest yseg_test models_dir facemesh.bin photo mw mh out.jpg colour
 */
public class FaceTest {
    static String exe, dir;

    public static void main(String[] a) throws Exception {
        exe = a[0];
        dir = a[1];
        FaceMesh fm = new FaceMesh(new FileInputStream(a[2]));
        BufferedImage img = ImageIO.read(new File(a[3]));
        int mw = Integer.parseInt(a[4]), mh = Integer.parseInt(a[5]);
        int w = img.getWidth(), h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        // people: the model on the small copy
        BufferedImage small = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB);
        small.createGraphics().drawImage(img.getScaledInstance(mw, mh, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        byte[] rgb = toRgb(small.getRGB(0, 0, mw, mh, null, 0, mw));
        List<float[]> boxes = new ArrayList<float[]>();
        byte[] mask = run("yolo11n-seg.bin", rgb, mw, mh, boxes, 0);
        byte[] m = new byte[mw * mh];
        System.arraycopy(mask, mask.length - mw * mh, m, 0, mw * mh);
        boolean facesOnly = a.length > 8 && a[8].equals("faces");
        Mesh mesh = facesOnly ? new Mesh(new byte[mw * mh], mw, mh, 0, 2, Ghost.tint(Integer.parseInt(a[7])), 7)
                : new Mesh(m, mw, mh, 0.85f, 2, Ghost.tint(Integer.parseInt(a[7])), 7);
        float sx = (float) w / mw;
        if (facesOnly) {
            mesh.setFaceDim(0.85f * 0.5f);
            List<FaceMesh.Face> faces = new ArrayList<FaceMesh.Face>();
            List<double[]> tiles = FaceMesh.tiles(w, h);
            for (double[] cm : tiles) {
                float[] det = floats(run("face_detector.bin", FaceMesh.warp(src, w, h, cm, 128, 128), 128, 128, null, FaceMesh.DET_FLOATS));
                FaceMesh.Face f = FaceMesh.detect(det, 0.6f);
                if (f != null) faces.add(FaceMesh.toSource(f, cm));
            }
            List<FaceMesh.Face> kept = FaceMesh.merge(faces, 6);
            System.out.println(tiles.size() + " squares, " + faces.size() + " hits, " + kept.size() + " faces");
            for (FaceMesh.Face fs : kept) {
                double[] lm = FaceMesh.landmarkCrop(fs);
                float[] out = floats(run("face_landmarks.bin", FaceMesh.warp(src, w, h, lm, 256, 256), 256, 256, null, FaceMesh.LM_FLOATS));
                System.out.printf("  face %.0fx%.0f at %.0f,%.0f presence %.2f%n", fs.w, fs.h, fs.cx, fs.cy, FaceMesh.presence(out));
                if (FaceMesh.presence(out) < 0.5f) continue;
                float[] pts = FaceMesh.landmarks(out, lm);
                for (int i = 0; i < pts.length; i++) pts[i] /= sx;
                mesh.addFace(pts, fm);
            }
            boxes.clear();
        }
        for (float[] b : boxes) {
            float x1 = b[0] * sx, y1 = b[1] * sx, x2 = b[2] * sx, y2 = b[3] * sx;
            float side = Math.min(x2 - x1, y2 - y1) * 0.9f;
            double[] cm = FaceMesh.squareCrop((x1 + x2) / 2 - side / 2, y1 - side * 0.05, side, FaceMesh.DET_SIZE);
            float[] det = floats(run("face_detector.bin", FaceMesh.warp(src, w, h, cm, 128, 128), 128, 128, null, FaceMesh.DET_FLOATS));
            FaceMesh.Face f = FaceMesh.detect(det, 0.5f);
            System.out.println("person box " + (int) x1 + "," + (int) y1 + " " + (int) (x2 - x1) + "x" + (int) (y2 - y1)
                    + (f == null ? ": no face" : String.format(": face %.2f", f.score)));
            if (f == null) continue;
            FaceMesh.Face fs = FaceMesh.toSource(f, cm);
            double[] lm = FaceMesh.landmarkCrop(fs);
            float[] out = floats(run("face_landmarks.bin", FaceMesh.warp(src, w, h, lm, 256, 256), 256, 256, null, FaceMesh.LM_FLOATS));
            System.out.printf("  presence %.2f%n", FaceMesh.presence(out));
            float[] pts = FaceMesh.landmarks(out, lm);
            for (int i = 0; i < pts.length; i++) pts[i] /= sx; // to mask pixels
            mesh.addFace(pts, fm);
        }
        int[] rows = new int[w * 64];
        for (int y = 0; y < h; y += 64) {
            int n = Math.min(64, h - y);
            img.getRGB(0, y, w, n, rows, 0, w);
            mesh.apply(rows, y, n, w, h);
            img.setRGB(0, y, w, n, rows, 0, w);
        }
        ImageIO.write(img, "jpg", new File(a[6]));
    }

    static byte[] toRgb(int[] px) {
        byte[] o = new byte[px.length * 3];
        for (int i = 0; i < px.length; i++) {
            o[3 * i] = (byte) (px[i] >> 16);
            o[3 * i + 1] = (byte) (px[i] >> 8);
            o[3 * i + 2] = (byte) px[i];
        }
        return o;
    }

    static float[] floats(byte[] b) {
        float[] f = new float[b.length / 4];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f);
        return f;
    }

    /** Runs a model with yseg_test; returns its output file (outputs as floats, then the people mask). */
    static byte[] run(String model, byte[] rgb, int w, int h, List<float[]> boxes, int floats) throws Exception {
        File in = File.createTempFile("ftin", ".rgb"), out = File.createTempFile("ftout", ".bin");
        Files.write(in.toPath(), rgb);
        Process p = new ProcessBuilder(exe, dir + "/" + model, in.getPath(), "" + w, "" + h, "1", out.getPath()).start();
        for (String line : new String(p.getInputStream().readAllBytes()).split("\n")) {
            if (line.startsWith("box ") && boxes != null) {
                String[] t = line.split(" ");
                boxes.add(new float[] {Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3]), Float.parseFloat(t[4])});
            }
        }
        p.waitFor();
        byte[] all = Files.readAllBytes(out.toPath());
        in.delete();
        out.delete();
        if (floats > 0) {
            byte[] o = new byte[floats * 4];
            System.arraycopy(all, 0, o, 0, o.length);
            return o;
        }
        return all;
    }
}
