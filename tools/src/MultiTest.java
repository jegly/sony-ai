import com.ghosts.app.Ghost;
import com.ghosts.app.MultiShot;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Desktop check of the multi-shot looks: MultiTest kind amount colour out.jpg frame1 frame2 ... */
public class MultiTest {
    public static void main(String[] a) throws Exception {
        int kind = Integer.parseInt(a[0]);
        MultiShot ms = new MultiShot(kind, Float.parseFloat(a[1]), Ghost.tint(Integer.parseInt(a[2])));
        int n = a.length - 4;
        BufferedImage[] f = new BufferedImage[n];
        for (int i = 0; i < n; i++) f[i] = ImageIO.read(new File(a[4 + i]));
        int w = f[0].getWidth(), h = f[0].getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[][] rows = new int[n][w * 64];
        int[] o = new int[w * 64];
        long t0 = System.nanoTime();
        for (int y = 0; y < h; y += 64) {
            int c = Math.min(64, h - y);
            for (int i = 0; i < n; i++) f[i].getRGB(0, y, w, c, rows[i], 0, w);
            ms.combine(rows, c, w, o);
            out.setRGB(0, y, w, c, o, 0, w);
        }
        System.out.printf("%d frames %dx%d: %.0f ms%n", n, w, h, (System.nanoTime() - t0) / 1e6);
        ImageIO.write(out, "jpg", new File(a[3]));
    }
}
