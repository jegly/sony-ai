import com.ghosts.app.Diffusion;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Desktop check of the diffusion filter: DiffTest photo mw mh out.jpg setting... (saves out_N.jpg) */
public class DiffTest {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int mw = Integer.parseInt(a[1]), mh = Integer.parseInt(a[2]);
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage small = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = small.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, mw, mh, null);
        int[] preview = small.getRGB(0, 0, mw, mh, null, 0, mw);
        String out = a[3].replace(".jpg", "");
        for (int v = 4; v < a.length; v++) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.getGraphics().drawImage(src, 0, 0, null);
            long t0 = System.nanoTime();
            Diffusion d = new Diffusion(preview, mw, mh, Integer.parseInt(a[v]));
            int[] rows = new int[w * 64];
            for (int y = 0; y < h; y += 64) {
                int n = Math.min(64, h - y);
                img.getRGB(0, y, w, n, rows, 0, w);
                d.apply(rows, y, n, w, h);
                img.setRGB(0, y, w, n, rows, 0, w);
            }
            System.out.printf("%s: %.0f ms%n", Diffusion.NAMES[Integer.parseInt(a[v])], (System.nanoTime() - t0) / 1e6);
            ImageIO.write(img, "jpg", new File(out + "_" + a[v] + ".jpg"));
        }
    }
}
