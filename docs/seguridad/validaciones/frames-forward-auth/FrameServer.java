// Receptor REAL de frames del tracker (BrowserFrameSource) en 127.0.0.1:18091, con el origen del "panel" de la prueba.
// Imprime el color de cada frame que LLEGA hasta el receptor, para ver quien pasa por Caddy.
import com.safemode.camerafeed.BrowserFrameSource;
import java.awt.image.BufferedImage;
import java.util.Set;

public class FrameServer {
    static String color(BufferedImage i) {
        int rgb = i.getRGB(10, 10), r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        if (r > 180 && g < 90 && b < 90) return "ROJO (atacante)";
        if (g > 180 && r < 90 && b < 90) return "VERDE (operador legitimo)";
        if (b > 180 && r < 90 && g < 90) return "AZUL (atacante sin proteccion)";
        return "otro " + Integer.toHexString(rgb & 0xFFFFFF);
    }

    public static void main(String[] args) throws Exception {
        BrowserFrameSource source = new BrowserFrameSource("localhost", 18091, Set.of("http://localhost:18080"));
        source.startAndWait();
        System.out.println("receptor listo en ws://localhost:18091");
        String last = null;
        long end = System.currentTimeMillis() + Long.parseLong(args[0]) * 1000;
        while (System.currentTimeMillis() < end) {
            BufferedImage img = source.getLatestFrame();
            String now = img == null ? null : color(img);
            if (now != null && !now.equals(last)) System.out.println("FRAME RECIBIDO EN EL RECEPTOR: " + now);
            last = now;
            Thread.sleep(80);
        }
        System.exit(0);
    }
}
