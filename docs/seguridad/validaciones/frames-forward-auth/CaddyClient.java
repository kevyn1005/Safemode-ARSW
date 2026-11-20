import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

public class CaddyClient {
    static byte[] jpeg(Color c) throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics(); g.setColor(c); g.fillRect(0, 0, 64, 48); g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(img, "jpg", out); return out.toByteArray();
    }

    static void probe(String label, String path, String origin, String cookie, Color color) throws Exception {
        WebSocket.Builder b = HttpClient.newHttpClient().newWebSocketBuilder();
        if (origin != null) b.header("Origin", origin);
        if (cookie != null) b.header("Cookie", cookie);
        String res;
        try {
            WebSocket ws = b.buildAsync(URI.create("ws://localhost:18080" + path), new WebSocket.Listener() { }).get(8, TimeUnit.SECONDS);
            ws.sendBinary(ByteBuffer.wrap(jpeg(color)), true).get(5, TimeUnit.SECONDS);
            res = "CONECTO (101) y mando un frame";
            Thread.sleep(700);
            ws.abort();
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            res = c instanceof WebSocketHandshakeException h ? "BLOQUEADO por Caddy, HTTP " + h.getResponse().statusCode() : "fallo: " + c.getClass().getSimpleName();
        }
        System.out.printf("%-62s -> %s%n", label, res);
        Thread.sleep(3500); // deja pasar los 3 s tras los que el receptor descarta el frame anterior
    }


    static void http(String label, String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:18080" + path));
        if (cookie != null) b.header("Cookie", cookie);
        String res;
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
            res = "HTTP " + r.statusCode() + (r.statusCode() == 200 ? " llego a Grafana: " + r.body() : " bloqueado por Caddy");
        } catch (Exception e) {
            res = "fallo: " + e.getClass().getSimpleName();
        }
        System.out.printf("%-62s -> %s%n", label, res);
    }

    public static void main(String[] a) throws Exception {
        String panel = "http://localhost:18080";
        probe("A) atacante: sin Origin, sin cookie            [/frames]", "/frames", null, null, Color.RED);
        probe("B) atacante: Origin falsificado, sin cookie    [/frames]", "/frames", panel, null, Color.RED);
        probe("C) atacante: Origin falso + cookie inventada   [/frames]", "/frames", panel, "safemode_session=inventada", Color.RED);
        probe("D) operador: Origin del panel + cookie valida  [/frames]", "/frames", panel, "safemode_session=valida", Color.GREEN);
        probe("E) atacante SIN forward_auth (como hoy)        [/frames-open]", "/frames-open", null, null, Color.BLUE);
        probe("F) atacante contra el gateway REAL             [/frames-real]", "/frames-real", panel, "safemode_session=inventada", Color.RED);
        http("H) atacante: sin cookie                          [/grafana/]", "/grafana/", null);
        http("I) atacante: cookie inventada                    [/grafana/api/health]", "/grafana/api/health", "safemode_session=inventada");
        http("J) operador: cookie valida                       [/grafana/]", "/grafana/", "safemode_session=valida");
        http("K) atacante SIN forward_auth (como estaba)       [/grafana-open/]", "/grafana-open/", null);
        System.exit(0);
    }
}
