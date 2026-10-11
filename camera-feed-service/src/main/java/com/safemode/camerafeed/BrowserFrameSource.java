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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Recibe los frames de una ventana que el operador comparte desde el navegador (el "compartir pantalla" de
 * {@code getDisplayMedia}): el panel manda una imagen JPEG/PNG por WebSocket (mensaje binario) varias veces por segundo.
 * A diferencia de {@link FrameCapturer}, captura la ventana elegida aunque haya otras ventanas encima.
 * Si el navegador deja de enviar (ventana de la camara minimizada, pestana cerrada), {@link #getLatestFrame()} devuelve
 * {@code null} pasado {@code staleAfter}: asi el tracker no procesa una y otra vez una imagen congelada.
 * <p>
 * Varias camaras: cada mensaje puede empezar con UN byte con el numero de camara (1 a {@link #MAX_CAMERAS}) seguido de la
 * imagen; un mensaje que empieza directamente por una imagen (JPEG {@code 0xFF}, PNG {@code 0x89}) es de la camara 1, que es
 * el formato de antes. {@link #forCamera(int)} da la vista de una camara como {@link FrameSource}.
 */
public class BrowserFrameSource implements FrameSource {

    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    /** Cantidad maxima de camaras (ids de 1 a este valor); igual que PresenceEventStore.MAX_CAMERAS. */
    public static final int MAX_CAMERAS = 8;

    private final Receiver server;
    private final Clock clock;
    private final Duration staleAfter;
    private final CountDownLatch started = new CountDownLatch(1);
    private final Map<Integer, Latest> latest = new ConcurrentHashMap<>();

    /** El ultimo fotograma de una camara y cuando llego. */
    private record Latest(BufferedImage image, long atMillis) {}

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

    /** El fotograma mas reciente de la camara 1 (el formato de una sola camara). */
    @Override
    public BufferedImage getLatestFrame() {
        return getLatestFrame(1);
    }

    /** El fotograma mas reciente de esa camara, o {@code null} si no hay o ya es demasiado viejo. */
    public BufferedImage getLatestFrame(int cameraId) {
        Latest frame = latest.get(cameraId);
        if (frame == null || clock.millis() - frame.atMillis() > staleAfter.toMillis()) {
            return null;
        }
        return frame.image();
    }

    /** La vista de una camara como fuente de fotogramas; detenerla no detiene el servidor (es de todas las camaras). */
    public FrameSource forCamera(int cameraId) {
        if (cameraId < 1 || cameraId > MAX_CAMERAS) {
            throw new IllegalArgumentException("Camara fuera de rango: " + cameraId);
        }
        return new FrameSource() {
            @Override
            public BufferedImage getLatestFrame() {
                return BrowserFrameSource.this.getLatestFrame(cameraId);
            }

            @Override
            public void stop() {
                // el servidor es compartido: lo detiene quien lo creo
            }
        };
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
        if (message.remaining() < 2 || message.remaining() > MAX_FRAME_BYTES) {
            return;
        }
        // un byte de 1 a 99 al inicio es el numero de camara; una imagen empieza por 0xFF (JPEG) o 0x89 (PNG): camara 1
        int cameraId = 1;
        int first = message.get(message.position()) & 0xFF;
        if (first >= 1 && first <= 99) {
            cameraId = first;
            message.get();
        }
        if (cameraId > MAX_CAMERAS) {
            return;
        }
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                latest.put(cameraId, new Latest(image, clock.millis()));
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
