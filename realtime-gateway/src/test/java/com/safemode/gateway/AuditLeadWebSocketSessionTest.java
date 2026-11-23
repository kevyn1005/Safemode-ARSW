package com.safemode.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.safemode.alerts.AlertEngine;
import com.safemode.alerts.AlertStore;
import com.safemode.presence.PresenceEventStore;
import com.safemode.presence.PresenceEventStore.OwnerInfo;
import com.safemode.presence.PresenceEventStore.RemovalInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validacion de la pista 2 de la auditoria de seguridad (run-1): "el WebSocket sigue abierto y autorizado despues de
 * que la sesion que lo abrio se cierra o vence".
 *
 * <p>El WebSocket revisa la cookie de sesion UNA vez, al conectar. Estas pruebas miden que pasa con un socket que ya
 * estaba abierto cuando la sesion termina. Todo es en memoria y en loopback, con un verificador de Google falso.
 *
 * <p>ARREGLADA: {@link AlertGateway} ahora guarda en cada conexion la cookie con la que se abrio y la revalida antes de
 * cada mensaje y cada envio; ademas un barrido cada 2 s cierra (codigo 1008) los sockets sin sesion. Antes de ese arreglo
 * las pruebas {@code pista2_*} fallaban en esta maquina (llegaban alertas y se podia hacer {@code ack} tras cerrar sesion);
 * ahora son pruebas de regresion normales. Las de "base" ejercitan el WebSocket con autenticacion, que no tenia pruebas.
 */
class AuditLeadWebSocketSessionTest {

    private static final OwnerInfo OWNER = new OwnerInfo(1L, "persona con camisa roja", "data/frames/owner_1.png");
    private static final String ORIGIN = "http://localhost:8080";

    /** Reloj que se puede adelantar, para vencer la sesion sin esperar 12 horas. */
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration amount) { now = now.plus(amount); }
    }

    private PresenceEventStore events;
    private AlertStore alerts;
    private AlertEngine engine;
    private AlertGateway gateway;
    private AuthService auth;
    private final MutableClock clock = new MutableClock();
    private final List<WebSocket> sockets = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws InterruptedException {
        String name = "audit_ws_" + UUID.randomUUID().toString().replace("-", "");
        events = PresenceEventStore.inMemory(name);
        alerts = AlertStore.inMemory(name);
        engine = new AlertEngine(events, alerts);
        auth = new AuthService("client-id-falso", Set.of("a@example.com"), token -> Optional.empty(), clock);
        gateway = new AlertGateway(engine, alerts, 0, Set.of(ORIGIN)).withAuth(auth);
        gateway.startAndWait();
    }

    @AfterEach
    void tearDown() {
        sockets.forEach(WebSocket::abort);
        gateway.shutdown();
        engine.close();
        alerts.close();
        events.close();
    }

    private static void log(String line) {
        System.out.println("[AUDITORIA] " + line);
    }

    private long removedBy(String kind) {
        return events.recordRemoved(1, "suitcase", 100, 100, 50, 80, Instant.now(), true, "data/frames/frame_1.png", OWNER,
                new RemovalInfo(kind, 2L, "persona con camisa azul", "data/frames/remover_2.png"));
    }

    private String newSessionCookie() {
        return AuthService.COOKIE_NAME + "=" + auth.createSession(new AuthService.User("a@example.com", "A"));
    }

    private static final class Client {
        final LinkedBlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
        volatile Integer closeCode;
        WebSocket socket;

        JsonObject next() throws InterruptedException {
            JsonObject message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "no llego ningun mensaje en 5 s");
            return message;
        }
    }

    private Client connect(String cookie) throws Exception {
        Client client = new Client();
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", ORIGIN);
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        client.socket = builder.buildAsync(URI.create("ws://localhost:" + gateway.boundPort()), new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    client.messages.add(JsonParser.parseString(buffer.toString()).getAsJsonObject());
                    buffer.setLength(0);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                client.closeCode = statusCode;
                return null;
            }
        }).get(5, TimeUnit.SECONDS);
        sockets.add(client.socket);
        return client;
    }

    // ------------------------------------------------------------------ base (siempre corren)

    @Test
    void sinCookieElWebSocketSeRechazaConAutenticacionActivada() {
        ExecutionException error = assertThrows(ExecutionException.class, () -> connect(null));
        assertTrue(error.getCause() instanceof WebSocketHandshakeException, "se esperaba un rechazo del handshake");
        assertRechazado((WebSocketHandshakeException) error.getCause());
    }

    /**
     * El codigo lanza InvalidDataException(401), pero la libreria Java-WebSocket 1.5.6 contesta siempre "404 WebSocket
     * Upgrade Failure" a un handshake rechazado: lo que importa es que NO se abre (no es 101) y que es un error (>= 400).
     */
    private static void assertRechazado(WebSocketHandshakeException error) {
        int status = error.getResponse().statusCode();
        log("el handshake rechazado se contesta con HTTP " + status);
        assertTrue(status >= 400, "el handshake debia ser rechazado y fue HTTP " + status);
    }

    @Test
    void conUnaSesionValidaElWebSocketAbreYEntregaElSnapshot() throws Exception {
        Client client = connect(newSessionCookie());
        assertEquals("snapshot", client.next().get("type").getAsString());
    }

    @Test
    void unaCookieCerradaYaNoAbreSocketsNuevos() {
        String cookie = newSessionCookie();
        auth.logout(cookie);
        ExecutionException error = assertThrows(ExecutionException.class, () -> connect(cookie));
        assertRechazado((WebSocketHandshakeException) error.getCause());
    }

    // ------------------------------------------------------------------ pista 2 (se encienden con -Daudit.leads=true)

    @Test
    @Timeout(60)
    void pista2_trasCerrarSesionNoDebeLlegarNingunaAlertaAlSocketYaAbierto() throws Exception {
        String cookie = newSessionCookie();
        Client client = connect(cookie);
        assertEquals("snapshot", client.next().get("type").getAsString());

        auth.logout(cookie);
        removedBy("BY_OTHER");
        engine.processNewEvents();

        JsonObject observed = client.messages.poll(3, TimeUnit.SECONDS);
        log("pista 2 / alerta nueva tras cerrar sesion: "
                + (observed == null ? "no llego nada" : "LLEGO un mensaje de tipo '" + observed.get("type").getAsString() + "'")
                + "; el servidor cerro el socket: " + (client.closeCode != null ? "si (codigo " + client.closeCode + ")" : "no"));
        assertNull(observed, "el socket abierto sigue recibiendo alertas despues de cerrar la sesion");
    }

    @Test
    @Timeout(60)
    void pista2_trasCerrarSesionNoDebePoderMarcarAlertasComoRevisadas() throws Exception {
        removedBy("BY_OTHER");
        long alertId = engine.processNewEvents().get(0).id();
        String cookie = newSessionCookie();
        Client client = connect(cookie);
        client.next(); // snapshot con la alerta pendiente

        auth.logout(cookie);
        client.socket.sendText("{\"action\":\"ack\",\"id\":" + alertId + "}", true);
        client.messages.poll(2, TimeUnit.SECONDS); // da tiempo a que el servidor procese el comando

        boolean revisada = alerts.listPending().isEmpty();
        log("pista 2 / ack enviado despues de cerrar sesion: la alerta " + (revisada ? "QUEDO marcada como revisada" : "sigue pendiente"));
        assertFalse(revisada, "un socket sin sesion pudo marcar una alerta como revisada");
    }

    @Test
    @Timeout(60)
    void pista2_elSocketSeCierraSoloTrasCerrarSesionAunqueNoLleguenAlertas() throws Exception {
        String cookie = newSessionCookie();
        Client client = connect(cookie);
        client.next();

        auth.logout(cookie);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8); // el barrido corre cada 2 s
        while (client.closeCode == null && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        log("pista 2 / cierre del socket sin alertas tras cerrar sesion: codigo " + client.closeCode);
        assertEquals(Integer.valueOf(1008), client.closeCode, "el servidor debia cerrar el socket sin sesion (1008)");
    }

    @Test
    @Timeout(60)
    void pista2_cerrarLaSesionDeUnoNoAfectaALosSocketsDeOtraSesionVigente() throws Exception {
        String closed = newSessionCookie();
        String active = newSessionCookie();
        Client closedClient = connect(closed);
        Client activeClient = connect(active);
        closedClient.next();
        activeClient.next();

        auth.logout(closed);
        removedBy("BY_OTHER");
        engine.processNewEvents();

        assertEquals("alert", activeClient.next().get("type").getAsString(), "la sesion vigente debe seguir recibiendo");
        assertNull(closedClient.messages.poll(1, TimeUnit.SECONDS), "la sesion cerrada no debe recibir nada");
    }

    @Test
    @Timeout(60)
    void pista2_trasVencerLaSesionNoDebeLlegarNingunaAlertaAlSocketYaAbierto() throws Exception {
        String cookie = newSessionCookie();
        Client client = connect(cookie);
        assertEquals("snapshot", client.next().get("type").getAsString());

        clock.advance(AuthService.SESSION_TTL.plusMinutes(1));
        assertTrue(auth.userFor(cookie).isEmpty(), "control: la sesion ya debe estar vencida para el servicio de sesiones");
        removedBy("BY_OTHER");
        engine.processNewEvents();

        JsonObject observed = client.messages.poll(3, TimeUnit.SECONDS);
        log("pista 2 / alerta nueva tras vencer la sesion (12 h + 1 min): "
                + (observed == null ? "no llego nada" : "LLEGO un mensaje de tipo '" + observed.get("type").getAsString() + "'"));
        assertNull(observed, "el socket abierto sigue recibiendo alertas despues de vencer la sesion");
    }
}
