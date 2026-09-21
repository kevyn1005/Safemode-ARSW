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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
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

class AlertGatewayTest {

    private static final OwnerInfo OWNER = new OwnerInfo(1L, "persona con camisa roja", "data/frames/owner_1.png");

    private PresenceEventStore events;
    private AlertStore alerts;
    private AlertEngine engine;
    private AlertGateway gateway;
    private final List<WebSocket> sockets = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws InterruptedException {
        String name = "gateway_" + UUID.randomUUID().toString().replace("-", "");
        events = PresenceEventStore.inMemory(name);
        alerts = AlertStore.inMemory(name);
        engine = new AlertEngine(events, alerts);
        gateway = new AlertGateway(engine, alerts, 0, Set.of("http://localhost:8080"));
        gateway.startAndWait();
    }

    @AfterEach
    void tearDown() {
        sockets.forEach(socket -> socket.abort());
        gateway.shutdown();
        engine.close();
        alerts.close();
        events.close();
    }

    private long removedBy(String kind) {
        return events.recordRemoved(1, "suitcase", 100, 100, 50, 80, Instant.now(), true, "data/frames/frame_1.png", OWNER,
                new RemovalInfo(kind, 2L, "persona con camisa azul", "data/frames/remover_2.png"));
    }

    /** Un cliente WebSocket que guarda cada mensaje de texto que recibe. */
    private static final class Client {
        final LinkedBlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
        WebSocket socket;

        JsonObject next() throws InterruptedException {
            JsonObject message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "no llego ningun mensaje en 5 s");
            return message;
        }
    }

    private Client connect(String origin) throws Exception {
        Client client = new Client();
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (origin != null) {
            builder.header("Origin", origin);
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
        }).get(5, TimeUnit.SECONDS);
        sockets.add(client.socket);
        return client;
    }

    @Test
    void alConectarseRecibeLasAlertasPendientes() throws Exception {
        removedBy("BY_OTHER");
        engine.processNewEvents();

        Client client = connect(null);
        JsonObject snapshot = client.next();

        assertEquals("snapshot", snapshot.get("type").getAsString());
        assertEquals(1, snapshot.getAsJsonArray("alerts").size());
        JsonObject alert = snapshot.getAsJsonArray("alerts").get(0).getAsJsonObject();
        assertEquals("POSSIBLE_THEFT", alert.get("type").getAsString());
        assertEquals("/photo/frame_1.png", alert.get("framePhoto").getAsString());
        assertEquals("/photo/remover_2.png", alert.get("removerPhoto").getAsString());
        assertEquals("/photo/owner_1.png", alert.get("ownerPhoto").getAsString());
    }

    @Test
    void unaAlertaNuevaLlegaAlClienteConectadoSinQueLaPida() throws Exception {
        Client client = connect(null);
        assertEquals(0, client.next().getAsJsonArray("alerts").size());

        removedBy("BY_OTHER");
        engine.processNewEvents();

        JsonObject message = client.next();
        assertEquals("alert", message.get("type").getAsString());
        JsonObject alert = message.getAsJsonObject("alert");
        assertEquals("HIGH", alert.get("severity").getAsString());
        assertEquals("BY_OTHER", alert.get("removalKind").getAsString());
        assertFalse(alert.get("acknowledged").getAsBoolean());
    }

    @Test
    void laAlertaSeEntregaATodosLosClientesConectados() throws Exception {
        Client first = connect(null);
        Client second = connect(null);
        first.next();
        second.next();

        removedBy("NO_ONE_NEAR");
        engine.processNewEvents();

        assertEquals("OBJECT_VANISHED", first.next().getAsJsonObject("alert").get("type").getAsString());
        assertEquals("OBJECT_VANISHED", second.next().getAsJsonObject("alert").get("type").getAsString());
    }

    @Test
    void marcarComoRevisadaSeGuardaYSeAvisaATodos() throws Exception {
        removedBy("BY_OTHER");
        long alertId = engine.processNewEvents().get(0).id();
        Client client = connect(null);
        client.next();

        client.socket.sendText("{\"action\":\"ack\",\"id\":" + alertId + "}", true);

        JsonObject update = client.next();
        assertEquals("update", update.get("type").getAsString());
        assertTrue(update.getAsJsonObject("alert").get("acknowledged").getAsBoolean());
        assertTrue(alerts.listPending().isEmpty());
    }

    @Test
    void cuandoLlegaLaDescripcionPorIaSeEnviaUnaActualizacion() throws Exception {
        long eventId = removedBy("BY_OTHER");
        Client client = connect(null);
        client.next();
        engine.processNewEvents();
        assertEquals("alert", client.next().get("type").getAsString());

        gateway.refreshRecent();
        assertTrue(client.messages.isEmpty(), "sin cambios no debe reenviar nada");

        events.updateRemoverAiDescription(eventId, "hombre de camisa azul");
        gateway.refreshRecent();

        JsonObject update = client.next();
        assertEquals("update", update.get("type").getAsString());
        assertEquals("hombre de camisa azul", update.getAsJsonObject("alert").get("removerAiDescription").getAsString());
    }

    @Test
    void unMensajeInvalidoSeIgnoraYLaConexionSigueViva() throws Exception {
        Client client = connect(null);
        client.next();

        client.socket.sendText("esto no es json", true);
        client.socket.sendText("{\"action\":\"ack\",\"id\":\"no-es-numero\"}", true);
        removedBy("BY_OTHER");
        engine.processNewEvents();

        assertEquals("alert", client.next().get("type").getAsString());
    }

    @Test
    void rechazaUnaPaginaConOrigenNoAutorizado() {
        ExecutionException error = assertThrows(ExecutionException.class, () -> connect("http://sitio-ajeno.example"));
        assertNotNull(error.getCause());
    }

    @Test
    void aceptaLaPaginaDelDashboard() throws Exception {
        Client client = connect("http://localhost:8080");
        assertEquals("snapshot", client.next().get("type").getAsString());
    }

    @Test
    void laUrlDeLaFotoNoExponeLaRutaDelDisco() {
        assertNull(AlertJson.photoUrl(null));
        assertNull(AlertJson.photoUrl("  "));
        assertEquals("/photo/a.png", AlertJson.photoUrl("C:/Users/Home/secreto/a.png"));
        assertEquals("/photo/a.png", AlertJson.photoUrl("object-presence-tracker\\data\\frames\\a.png"),
                "una ruta de Windows guardada por el tracker se lee bien tambien desde Linux");
    }
}
