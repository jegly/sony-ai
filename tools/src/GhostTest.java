import com.ghosts.app.Ghost;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/**
 * Desktop check of the ghost looks: GhostTest photo mask.raw mw mh out.jpg [look:glow:opacity ...]
 * e.g. 0:2:1.0 1:2:0.85 2:2:0.7 ; each variant is saved as out_N.jpg
 */
public class GhostTest {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        byte[] mask = Files.readAllBytes(new File(a[1]).toPath());
        int mw = Integer.parseInt(a[2]), mh = Integer.parseInt(a[3]);
        int w = src.getWidth(), h = src.getHeight();
        // the small copy the camera makes for the model, used by the Dreamy bloom
        BufferedImage small = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = small.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, mw, mh, null);
        int[] preview = small.getRGB(0, 0, mw, mh, null, 0, mw);
        String out = a[4].replace(".jpg", "");
        for (int v = 5; v < a.length; v++) {
            String[] p = a[v].split(":");
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.getGraphics().drawImage(src, 0, 0, null);
            long t0 = System.nanoTime();
            Ghost g = new Ghost(mask, mw, mh, Integer.parseInt(p[1]), Integer.parseInt(p[0]), Float.parseFloat(p[2]), preview);
            int[] rows = new int[w * 64];
            for (int y = 0; y < h; y += 64) {
                int n = Math.min(64, h - y);
                img.getRGB(0, y, w, n, rows, 0, w);
                g.apply(rows, y, n, w, h);
                img.setRGB(0, y, w, n, rows, 0, w);
            }
            System.out.printf("%s: %.0f ms%n", a[v], (System.nanoTime() - t0) / 1e6);
            ImageIO.write(img, "jpg", new File(out + "_" + (v - 4) + ".jpg"));
        }
    }
}
