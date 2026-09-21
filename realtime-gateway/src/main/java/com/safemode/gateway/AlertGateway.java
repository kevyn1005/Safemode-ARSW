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

    private final AlertEngine engine;
    private final AlertStore alerts;
    private final Set<String> allowedOrigins;
    private final CountDownLatch started = new CountDownLatch(1);
    // Ultimo JSON enviado de cada alerta reciente, para detectar cuando cambia su evidencia.
    private final Map<Long, String> lastSent = new LinkedHashMap<>();
    private ScheduledExecutorService refresher;

    /**
     * @param port           puerto del WebSocket (0 = uno libre, util en pruebas; ver {@link #boundPort()})
     * @param allowedOrigins origenes de pagina web autorizados a conectarse (un navegador siempre manda el suyo);
     *                       los clientes sin cabecera Origin (no navegadores) se aceptan
     */
    public AlertGateway(AlertEngine engine, AlertStore alerts, int port, Set<String> allowedOrigins) {
        super(new InetSocketAddress("localhost", port));
        this.engine = engine;
        this.alerts = alerts;
        this.allowedOrigins = allowedOrigins;
        setReuseAddr(true);
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
        refresher.scheduleWithFixedDelay(this::refreshRecent, 2, 2, TimeUnit.SECONDS);
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
            throw new InvalidDataException(403, "Origen no autorizado");
        }
        return builder;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        List<JsonObject> pending = new ArrayList<>();
        for (Alert alert : alerts.listPending()) {
            pending.add(AlertJson.toJson(alert, engine.evidenceOf(alert)));
        }
        conn.send(AlertJson.snapshot(pending).toString());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
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
    }

    private void broadcastAlert(String type, Alert alert) {
        JsonObject json = AlertJson.toJson(alert, engine.evidenceOf(alert));
        synchronized (lastSent) {
            lastSent.put(alert.id(), json.toString());
            if (lastSent.size() > RECENT_ALERTS_TRACKED) {
                lastSent.remove(lastSent.keySet().iterator().next());
            }
        }
        broadcast(AlertJson.envelope(type, json).toString());
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
                    String current = AlertJson.toJson(alert, engine.evidenceOf(alert)).toString();
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
