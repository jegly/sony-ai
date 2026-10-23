import com.ghosts.app.Ghost;
import com.ghosts.app.Scene;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Desktop check of the scene effects: SceneTest photo mask.raw mw mh out.jpg kind:amount:colour ... (saves out_N.jpg) */
public class SceneTest {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        byte[] mask = Files.readAllBytes(new File(a[1]).toPath());
        int mw = Integer.parseInt(a[2]), mh = Integer.parseInt(a[3]);
        int w = src.getWidth(), h = src.getHeight();
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
            Scene s = new Scene(Integer.parseInt(p[0]), mask, mw, mh, preview, Float.parseFloat(p[1]), Ghost.tint(Integer.parseInt(p[2])), 11);
            int[] rows = new int[w * 64];
            for (int y = 0; y < h; y += 64) {
                int n = Math.min(64, h - y);
                img.getRGB(0, y, w, n, rows, 0, w);
                s.apply(rows, y, n, w, h);
                img.setRGB(0, y, w, n, rows, 0, w);
            }
            System.out.printf("%s: %.0f ms%n", a[v], (System.nanoTime() - t0) / 1e6);
            ImageIO.write(img, "jpg", new File(out + "_" + (v - 4) + ".jpg"));
        }
    }
}
