package com.safemode.camerafeed;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BrowserFrameSourceTest {

    private static final String PANEL_ORIGIN = "http://localhost:8080";

    /** Reloj que solo avanza cuando la prueba lo manda. */
    private static class ManualClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final ManualClock clock = new ManualClock();
    private BrowserFrameSource source;

    @BeforeEach
    void start() throws Exception {
        source = new BrowserFrameSource("localhost", 0, Set.of(PANEL_ORIGIN), Duration.ofSeconds(3), clock);
        source.startAndWait();
    }

    @AfterEach
    void stop() {
        source.stop();
    }

    private WebSocketClient connect(Map<String, String> headers) throws Exception {
        WebSocketClient client = new WebSocketClient(new URI("ws://localhost:" + source.boundPort()), headers) {
            @Override public void onOpen(ServerHandshake handshake) { }
            @Override public void onMessage(String message) { }
            @Override public void onClose(int code, String reason, boolean remote) { }
            @Override public void onError(Exception ex) { }
        };
        client.connectBlocking(5, TimeUnit.SECONDS);
        return client;
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private void waitForFrame() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (source.getLatestFrame() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void sinFramesDevuelveNull() {
        assertNull(source.getLatestFrame());
    }

    @Test
    void guardaElFrameQueMandaElNavegador() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", PANEL_ORIGIN));
        assertTrue(client.isOpen());
        client.send(jpeg(320, 200));
        waitForFrame();
        BufferedImage frame = source.getLatestFrame();
        assertNotNull(frame);
        assertEquals(320, frame.getWidth());
        assertEquals(200, frame.getHeight());
        client.closeBlocking();
    }

    @Test
    void unFrameViejoSeDescartaParaNoReprocesarUnaImagenCongelada() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", PANEL_ORIGIN));
        client.send(jpeg(64, 64));
        waitForFrame();
        assertNotNull(source.getLatestFrame());
        clock.advance(Duration.ofSeconds(4));
        assertNull(source.getLatestFrame());
        client.closeBlocking();
    }

    @Test
    void rechazaUnaPaginaDeOtroOrigen() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", "http://evil.example"));
        assertFalse(client.isOpen());
    }

    /** Un mensaje del navegador con varias camaras: un byte con la camara y despues la imagen. */
    private static byte[] forCamera(int cameraId, byte[] image) {
        byte[] message = new byte[image.length + 1];
        message[0] = (byte) cameraId;
        System.arraycopy(image, 0, message, 1, image.length);
        return message;
    }

    private void waitForFrame(int cameraId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (source.getLatestFrame(cameraId) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void cadaCamaraGuardaSuPropioFotograma() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", PANEL_ORIGIN));
        client.send(forCamera(1, jpeg(320, 200)));
        client.send(forCamera(2, jpeg(160, 120)));
        waitForFrame(1);
        waitForFrame(2);

        assertEquals(320, source.getLatestFrame(1).getWidth());
        assertEquals(160, source.getLatestFrame(2).getWidth());
        assertEquals(320, source.getLatestFrame().getWidth(), "getLatestFrame() sin argumento es la camara 1");
        assertNull(source.getLatestFrame(3));
        client.closeBlocking();
    }

    @Test
    void elFormatoDeAntesSinByteDeCamaraSigueSiendoLaCamara1() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", PANEL_ORIGIN));
        client.send(jpeg(100, 50));
        waitForFrame(1);

        assertEquals(100, source.getLatestFrame(1).getWidth());
        assertNull(source.getLatestFrame(2));
        client.closeBlocking();
    }

    @Test
    void forCameraDaLaVistaDeEsaCamara() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", PANEL_ORIGIN));
        client.send(forCamera(2, jpeg(80, 40)));
        waitForFrame(2);

        FrameSource camera2 = source.forCamera(2);
        assertEquals(80, camera2.getLatestFrame().getWidth());
        assertNull(source.forCamera(1).getLatestFrame());
        camera2.stop(); // no debe detener el servidor compartido
        client.send(forCamera(2, jpeg(90, 40)));
        Thread.sleep(300);
        assertEquals(90, source.getLatestFrame(2).getWidth());
        assertThrows(IllegalArgumentException.class, () -> source.forCamera(0));
        assertThrows(IllegalArgumentException.class, () -> source.forCamera(BrowserFrameSource.MAX_CAMERAS + 1));
        client.closeBlocking();
    }

    @Test
    void cadaCamaraSeVuelveViejaPorSeparado() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", PANEL_ORIGIN));
        client.send(forCamera(1, jpeg(64, 64)));
        waitForFrame(1);
        clock.advance(Duration.ofSeconds(2));
        client.send(forCamera(2, jpeg(64, 64)));
        waitForFrame(2);
        clock.advance(Duration.ofSeconds(2)); // la camara 1 lleva 4 s sin enviar; la 2, solo 2 s

        assertNull(source.getLatestFrame(1));
        assertNotNull(source.getLatestFrame(2));
        client.closeBlocking();
    }

    @Test
    void unaCamaraFueraDeRangoSeIgnora() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", PANEL_ORIGIN));
        client.send(forCamera(BrowserFrameSource.MAX_CAMERAS + 1, jpeg(64, 64)));
        Thread.sleep(300);

        for (int camera = 1; camera <= BrowserFrameSource.MAX_CAMERAS + 1; camera++) {
            assertNull(source.getLatestFrame(camera));
        }
        client.closeBlocking();
    }

    @Test
    void ignoraBasuraQueNoEsUnaImagen() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", PANEL_ORIGIN));
        client.send(new byte[]{1, 2, 3, 4});
        Thread.sleep(300);
        assertNull(source.getLatestFrame());
        client.closeBlocking();
    }
}
