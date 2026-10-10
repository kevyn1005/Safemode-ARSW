package com.safemode.camerafeed;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Recibe los frames de una ventana que el operador comparte desde el navegador (el "compartir pantalla" de
 * {@code getDisplayMedia}): el panel manda una imagen JPEG/PNG por WebSocket (mensaje binario) varias veces por segundo.
 * A diferencia de {@link FrameCapturer}, captura la ventana elegida aunque haya otras ventanas encima.
 * Si el navegador deja de enviar (ventana de la camara minimizada, pestana cerrada), {@link #getLatestFrame()} devuelve
 * {@code null} pasado {@code staleAfter}: asi el tracker no procesa una y otra vez una imagen congelada.
 */
public class BrowserFrameSource implements FrameSource {

    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private final Receiver server;
    private final Clock clock;
    private final Duration staleAfter;
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile BufferedImage latestFrame;
    private volatile long latestAtMillis;

    /**
     * @param port           puerto del WebSocket (0 = uno libre, util en pruebas; ver {@link #boundPort()})
     * @param allowedOrigins origenes de pagina web autorizados (un navegador siempre manda el suyo); los clientes sin
     *                       cabecera Origin (no navegadores) se aceptan
     */
    public BrowserFrameSource(String host, int port, Set<String> allowedOrigins, Duration staleAfter, Clock clock) {
        this.server = new Receiver(new InetSocketAddress(host, port), allowedOrigins);
        this.staleAfter = staleAfter;
        this.clock = clock;
    }

    public BrowserFrameSource(String host, int port, Set<String> allowedOrigins) {
        this(host, port, allowedOrigins, Duration.ofSeconds(3), Clock.systemUTC());
    }

    /** Arranca el servidor y espera a que este escuchando. */
    public void startAndWait() throws InterruptedException {
        server.start();
        started.await();
    }

    /** Puerto en el que realmente escucha (util cuando se pidio el puerto 0). */
    public int boundPort() {
        return server.getPort();
    }

    @Override
    public BufferedImage getLatestFrame() {
        BufferedImage frame = latestFrame;
        if (frame == null || clock.millis() - latestAtMillis > staleAfter.toMillis()) {
            return null;
        }
        return frame;
    }

    @Override
    public void stop() {
        try {
            server.stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void accept(ByteBuffer message) {
        if (message.remaining() > MAX_FRAME_BYTES) {
            return;
        }
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                latestFrame = image;
                latestAtMillis = clock.millis();
            }
        } catch (IOException e) {
            System.err.println("[CAMARA] Frame del navegador ilegible: " + e.getMessage());
        }
    }

    private class Receiver extends WebSocketServer {
        private final Set<String> allowedOrigins;

        Receiver(InetSocketAddress address, Set<String> allowedOrigins) {
            super(address);
            this.allowedOrigins = allowedOrigins;
            setReuseAddr(true);
        }

        // Un navegador deja a CUALQUIER pagina abrir un WebSocket hacia localhost: sin esta revision, una web ajena
        // podria inyectar imagenes falsas al detector.
        @Override
        public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request)
                throws InvalidDataException {
            ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
            String origin = request.hasFieldValue("Origin") ? request.getFieldValue("Origin") : null;
            if (origin != null && !allowedOrigins.contains(origin)) {
                throw new InvalidDataException(403, "Origen no autorizado");
            }
            return builder;
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            System.out.println("[CAMARA] Ventana compartida desde el navegador: conectada");
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("[CAMARA] Ventana compartida desde el navegador: desconectada");
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // solo se esperan frames binarios
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            accept(message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            System.err.println("[CAMARA] Error de WebSocket: " + ex.getMessage());
        }
    }
}
