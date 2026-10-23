import com.ghosts.app.Ghost;
import com.ghosts.app.Skeleton;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Desktop check of the neon skeletons: SkelTest yseg_test pose.bin photo mw mh out.jpg colour dim */
public class SkelTest {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[2]));
        int mw = Integer.parseInt(a[3]), mh = Integer.parseInt(a[4]);
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage small = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB);
        small.createGraphics().drawImage(img.getScaledInstance(mw, mh, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        int[] px = small.getRGB(0, 0, mw, mh, null, 0, mw);
        byte[] rgb = new byte[px.length * 3];
        for (int i = 0; i < px.length; i++) {
            rgb[3 * i] = (byte) (px[i] >> 16);
            rgb[3 * i + 1] = (byte) (px[i] >> 8);
            rgb[3 * i + 2] = (byte) px[i];
        }
        File in = File.createTempFile("skin", ".rgb");
        Files.write(in.toPath(), rgb);
        Process p = new ProcessBuilder(a[0], a[1], in.getPath(), "" + mw, "" + mh, "1", "/dev/null").start();
        List<float[]> poses = new ArrayList<float[]>();
        for (String line : new String(p.getInputStream().readAllBytes()).split("\n")) {
            if (!line.startsWith("pose ")) continue;
            String[] t = line.split(" ");
            float[] v = new float[56];
            for (int k = 0; k < 56; k++) v[k] = Float.parseFloat(t[k + 1]);
            poses.add(v);
        }
        p.waitFor();
        in.delete();
        Skeleton sk = new Skeleton(Float.parseFloat(a[7]), 2, Ghost.tint(Integer.parseInt(a[6])));
        for (float[] v : poses) {
            float[] kp = new float[51];
            for (int k = 0; k < 17; k++) {
                kp[3 * k] = v[5 + 3 * k] / mw;
                kp[3 * k + 1] = v[6 + 3 * k] / mh;
                kp[3 * k + 2] = v[7 + 3 * k];
            }
            sk.addPerson(kp, (v[3] - v[1]) / mh, (float) w / h);
        }
        System.out.println(poses.size() + " people");
        int[] rows = new int[w * 64];
        for (int y = 0; y < h; y += 64) {
            int n = Math.min(64, h - y);
            img.getRGB(0, y, w, n, rows, 0, w);
            sk.apply(rows, y, n, w, h);
            img.setRGB(0, y, w, n, rows, 0, w);
        }
        ImageIO.write(img, "jpg", new File(a[5]));
    }
}
