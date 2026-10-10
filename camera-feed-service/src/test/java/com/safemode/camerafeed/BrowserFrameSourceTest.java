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

    @Test
    void ignoraBasuraQueNoEsUnaImagen() throws Exception {
        WebSocketClient client = connect(Map.of("Origin", PANEL_ORIGIN));
        client.send(new byte[]{1, 2, 3, 4});
        Thread.sleep(300);
        assertNull(source.getLatestFrame());
        client.closeBlocking();
    }
}
