import com.ghosts.app.Ghost;
import com.ghosts.app.Wireframe;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Desktop check of the wireframe: WireTest photo mask.raw mw mh out.jpg dim:glow:colour ... (saves out_N.jpg) */
public class WireTest {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        byte[] mask = Files.readAllBytes(new File(a[1]).toPath());
        int mw = Integer.parseInt(a[2]), mh = Integer.parseInt(a[3]);
        int w = src.getWidth(), h = src.getHeight();
        String out = a[4].replace(".jpg", "");
        for (int v = 5; v < a.length; v++) {
            String[] p = a[v].split(":");
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.getGraphics().drawImage(src, 0, 0, null);
            long t0 = System.nanoTime();
            Wireframe wf = new Wireframe(mask, mw, mh, Float.parseFloat(p[0]), Integer.parseInt(p[1]), Ghost.tint(Integer.parseInt(p[2])));
            int[] rows = new int[w * 64];
            for (int y = 0; y < h; y += 64) {
                int n = Math.min(64, h - y);
                img.getRGB(0, y, w, n, rows, 0, w);
                wf.apply(rows, y, n, w, h);
                img.setRGB(0, y, w, n, rows, 0, w);
            }
            System.out.printf("%s: %.0f ms%n", a[v], (System.nanoTime() - t0) / 1e6);
            ImageIO.write(img, "jpg", new File(out + "_" + (v - 4) + ".jpg"));
        }
    }
}
