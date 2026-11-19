package com.safemode.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.safemode.alerts.Alert;
import com.safemode.alerts.AlertEngine;
import com.safemode.alerts.AlertStore;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;
import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.time.Instant;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * Servidor WebSocket: mantiene una conexion abierta con cada dashboard y le empuja las alertas en cuanto el motor las
 * crea, sin que el navegador tenga que preguntar. Protocolo (JSON):
 * <ul>
 *   <li>servidor a cliente: {@code snapshot} al conectar (alertas pendientes), {@code alert} por cada alerta nueva y
 *       {@code update} cuando cambia una ya enviada (llega la descripcion por IA, o se marca como revisada);</li>
 *   <li>cliente a servidor: {@code {"action":"ack","id":N}} para marcar una alerta como revisada.</li>
 * </ul>
 */
public class AlertGateway extends WebSocketServer {

    private static final int RECENT_ALERTS_TRACKED = 50;
    private static final int CLOSE_POLICY_VIOLATION = 1008; // codigo estandar de cierre de WebSocket: "no cumple la politica"

    private final AlertEngine engine;
    private final AlertStore alerts;
    private final Set<String> allowedOrigins;
    private final CountDownLatch started = new CountDownLatch(1);
    // Ultimo JSON enviado de cada alerta reciente, para detectar cuando cambia su evidencia.
    private final Map<Long, String> lastSent = new LinkedHashMap<>();
    private final Counter wsConnections = Observability.REGISTRY.counter("safemode.ws.connections");
    private final Counter wsSent = Observability.REGISTRY.counter("safemode.ws.messages.sent");
    private final Timer deliveryLatency = Timer.builder("safemode.alert.delivery")
            .description("Segundos desde el retiro hasta que la alerta se empuja por WebSocket")
            .serviceLevelObjectives(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10))
            .register(Observability.REGISTRY);
    private ScheduledExecutorService refresher;
    private volatile AuthService auth;   // null = sin sesion (como antes); con valor, el WebSocket exige la cookie de sesion

    /** Exige sesion de Google para abrir el WebSocket. Se llama antes de {@link #startAndWait()}. */
    public AlertGateway withAuth(AuthService authService) {
        this.auth = authService;
        return this;
    }


    /**
     * @param port           puerto del WebSocket (0 = uno libre, util en pruebas; ver {@link #boundPort()})
     * @param allowedOrigins origenes de pagina web autorizados a conectarse (un navegador siempre manda el suyo);
     *                       los clientes sin cabecera Origin (no navegadores) se aceptan
     */
    public AlertGateway(AlertEngine engine, AlertStore alerts, int port, Set<String> allowedOrigins) {
        this(engine, alerts, "localhost", port, allowedOrigins);
    }

    /** Igual, pero en la direccion dada: dentro de un contenedor hay que escuchar en 0.0.0.0 para que el puerto publicado llegue. */
    public AlertGateway(AlertEngine engine, AlertStore alerts, String host, int port, Set<String> allowedOrigins) {
        super(new InetSocketAddress(host, port));
        this.engine = engine;
        this.alerts = alerts;
        this.allowedOrigins = allowedOrigins;
        setReuseAddr(true);
        Gauge.builder("safemode.ws.sessions", this, s -> s.getConnections().size()).register(Observability.REGISTRY);
        for (String r : new String[]{"origin", "auth"}) Observability.REGISTRY.counter("safemode.ws.rejected", "reason", r);
        engine.addListener(this::publishNew);
    }

    /** Arranca el servidor y espera a que este escuchando. */
    public void startAndWait() throws InterruptedException {
        start();
        started.await();
        refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-refresh");
            thread.setDaemon(true);
            return thread;
        });
        refresher.scheduleWithFixedDelay(() -> {
            try {
                closeRevokedConnections(); // una excepcion aqui cancelaria todas las ejecuciones siguientes
            } catch (RuntimeException e) {
                System.err.println("[GATEWAY] No se pudieron revisar las sesiones de los sockets: " + e.getClass().getSimpleName());
            }
            refreshRecent();
        }, 2, 2, TimeUnit.SECONDS);
    }

    /** Puerto en el que realmente escucha (util cuando se pidio el puerto 0). */
    public int boundPort() {
        return getPort();
    }

    @Override
    public void onStart() {
        started.countDown();
    }

    // Un navegador deja a CUALQUIER pagina abrir un WebSocket hacia localhost: sin esta revision, una web ajena que
    // el vigilante tenga abierta podria leer las alertas y marcarlas como revisadas.
    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request)
            throws InvalidDataException {
        ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        String origin = request.hasFieldValue("Origin") ? request.getFieldValue("Origin") : null;
        if (origin != null && !allowedOrigins.contains(origin)) {
            Observability.REGISTRY.counter("safemode.ws.rejected", "reason", "origin").increment();
            throw new InvalidDataException(403, "Origen no autorizado");
        }
        AuthService authService = auth;
        if (authService != null) {
            String cookie = request.hasFieldValue("Cookie") ? request.getFieldValue("Cookie") : null;
            Observability.REGISTRY.counter("safemode.ws.rejected", "reason", "auth").increment();
            if (authService.userFor(cookie).isEmpty()) {
                throw new InvalidDataException(401, "Sesion requerida");
            }
            conn.setAttachment(cookie); // con que sesion se abrio: se vuelve a comprobar en cada mensaje y cada envio
        }
        return builder;
    }

    // Auditoria de seguridad (pista 2): la sesion solo se revisaba al abrir el socket, asi que cerrar sesion o vencer los 12 h
    // no cortaba un socket ya abierto: seguia recibiendo alertas y podia marcarlas como revisadas.
    private boolean stillAuthorized(WebSocket conn) {
        AuthService authService = auth;
        if (authService == null) {
            return true; // servidor sin autenticacion (pruebas): no hay sesion que revisar
        }
        String cookie = conn.getAttachment();
        return cookie != null && authService.userFor(cookie).isPresent();
    }

    private void closeUnauthorized(WebSocket conn) {
        try {
            conn.close(CLOSE_POLICY_VIOLATION, "Sesion terminada");
        } catch (RuntimeException e) {
            System.err.println("[GATEWAY] No se pudo cerrar un socket sin sesion: " + e.getClass().getSimpleName());
        }
    }

    /** Cierra los sockets cuya sesion ya termino (cerro sesion o vencio), aunque no llegue ninguna alerta. */
    void closeRevokedConnections() {
        for (WebSocket conn : getConnections()) {
            if (!stillAuthorized(conn)) {
                closeUnauthorized(conn);
            }
        }
    }

    /** Como {@code broadcast}, pero solo a los sockets con sesion vigente; los demas se cierran en vez de recibir el mensaje. */
    private void pushToAuthorized(String text) {
        for (WebSocket conn : getConnections()) {
            if (!stillAuthorized(conn)) {
                closeUnauthorized(conn);
                continue;
            }
            try {
                conn.send(text);
            } catch (RuntimeException e) {
                // la conexion se cerro justo ahora: la libreria la saca de la lista
            }
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        List<JsonObject> pending = new ArrayList<>();
        for (Alert alert : alerts.listPending()) {
            pending.add(AlertJson.toJson(alert, engine.evidenceOf(alert), engine::cameraName));
        }
        conn.send(AlertJson.snapshot(pending, engine.cameraNames()).toString());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (!stillAuthorized(conn)) {
            closeUnauthorized(conn); // sesion terminada: ni se lee el comando
            return;
        }
        try {
            JsonObject request = JsonParser.parseString(message).getAsJsonObject();
            if ("ack".equals(request.get("action").getAsString())) {
                long id = request.get("id").getAsLong();
                if (alerts.acknowledge(id)) {
                    alerts.findById(id).ifPresent(alert -> broadcastAlert("update", alert));
                }
            }
        } catch (RuntimeException e) {
            System.err.println("[GATEWAY] Mensaje ignorado (no es un comando valido): " + e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        // nada que limpiar: la lista de conexiones la lleva la libreria
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[GATEWAY] Error de WebSocket: " + ex.getMessage());
    }

    private void publishNew(Alert alert) {
        broadcastAlert("alert", alert);
        engine.evidenceOf(alert).ifPresent(e ->
                deliveryLatency.record(Duration.between(e.occurredAt(), Instant.now())));
    }

    private void broadcastAlert(String type, Alert alert) {
        JsonObject json = AlertJson.toJson(alert, engine.evidenceOf(alert), engine::cameraName);
        synchronized (lastSent) {
            lastSent.put(alert.id(), json.toString());
            if (lastSent.size() > RECENT_ALERTS_TRACKED) {
                lastSent.remove(lastSent.keySet().iterator().next());
            }
        }
        pushToAuthorized(AlertJson.envelope(type, json).toString());
        wsSent.increment(getConnections().size());
    }

    /** Reenvia las alertas recientes cuya evidencia cambio (por ejemplo cuando llega la descripcion por IA). */
    void refreshRecent() {
        try {
            List<Long> ids;
            synchronized (lastSent) {
                ids = new ArrayList<>(lastSent.keySet());
            }
            for (long id : ids) {
                alerts.findById(id).ifPresent(alert -> {
                    String current = AlertJson.toJson(alert, engine.evidenceOf(alert), engine::cameraName).toString();
                    boolean changed;
                    synchronized (lastSent) {
                        changed = !current.equals(lastSent.get(id));
                    }
                    if (changed) {
                        broadcastAlert("update", alert);
                    }
                });
            }
        } catch (RuntimeException e) {
            System.err.println("[GATEWAY] No se pudieron refrescar las alertas: " + e.getMessage());
        }
    }

    /** Avisa a todos los paneles de los nombres vigentes de las camaras (tras renombrar una). */
    public void broadcastCameras() {
        pushToAuthorized(AlertJson.camerasMessage(engine.cameraNames()).toString());
        // las alertas ya enviadas llevan el nombre viejo: se reenvian para que el panel las muestre con el nuevo
        refreshRecent();
    }

    /** Detiene el servidor y libera el puerto. */
    public void shutdown() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
