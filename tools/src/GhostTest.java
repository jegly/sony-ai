import com.ghosts.app.Ghost;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Desktop check of the ghost effect: GhostTest photo mask.raw mw mh glow out.jpg */
public class GhostTest {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        byte[] mask = Files.readAllBytes(new File(a[1]).toPath());
        int mw = Integer.parseInt(a[2]), mh = Integer.parseInt(a[3]), glow = Integer.parseInt(a[4]);
        int w = img.getWidth(), h = img.getHeight();
        Ghost g = new Ghost(mask, mw, mh, glow);
        int[] rows = new int[w * 64];
        long t0 = System.nanoTime();
        for (int y = 0; y < h; y += 64) {
            int n = Math.min(64, h - y);
            img.getRGB(0, y, w, n, rows, 0, w);
            g.apply(rows, y, n, w, h);
            img.setRGB(0, y, w, n, rows, 0, w);
        }
        System.out.printf("%dx%d, coverage %.3f, %.0f ms%n", w, h, g.coverage(), (System.nanoTime() - t0) / 1e6);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(img, 0, 0, null);
        ImageIO.write(out, "jpg", new File(a[5]));
    }
}
