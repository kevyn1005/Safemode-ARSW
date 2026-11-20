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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Escalado horizontal del gateway (docs/disponibilidad): dos replicas comparten la misma base. Cada una tiene su motor de
 * alertas y sus propios operadores conectados. La base reparte el trabajo (una alerta por evento), pero TODOS los
 * operadores, esten en la replica que esten, deben recibir cada alerta nueva exactamente una vez.
 */
class AlertGatewayReplicasTest {

    private static final OwnerInfo OWNER = new OwnerInfo(1L, "persona con camisa roja", "data/frames/owner_1.png");

    private String dbName;
    private PresenceEventStore events;
    private AlertStore storeA;
    private AlertStore storeB;
    private AlertEngine engineA;
    private AlertEngine engineB;
    private AlertGateway gatewayA;
    private AlertGateway gatewayB;
    private final List<WebSocket> sockets = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws InterruptedException {
        dbName = "replicas_gw_" + UUID.randomUUID().toString().replace("-", "");
        events = PresenceEventStore.inMemory(dbName);
        storeA = AlertStore.inMemory(dbName);
        storeB = AlertStore.inMemory(dbName);
        engineA = new AlertEngine(events, storeA);
        engineB = new AlertEngine(events, storeB);
        gatewayA = new AlertGateway(engineA, storeA, 0, Set.of("http://localhost:8080"));
        gatewayA.startAndWait();
        gatewayB = new AlertGateway(engineB, storeB, 0, Set.of("http://localhost:8080"));
        gatewayB.startAndWait();
    }

    @AfterEach
    void tearDown() {
        sockets.forEach(WebSocket::abort);
        gatewayA.shutdown();
        gatewayB.shutdown();
        engineA.close();
        engineB.close();
        storeA.close();
        storeB.close();
        events.close();
    }

    private long removedBy(String kind) {
        return events.recordRemoved(1, "suitcase", 100, 100, 50, 80, Instant.now(), true, "data/frames/frame_1.png", OWNER,
                new RemovalInfo(kind, 2L, "persona con camisa azul", "data/frames/remover_2.png"));
    }

    private static final class Client {
        final LinkedBlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();

        JsonObject next() throws InterruptedException {
            JsonObject message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "no llego ningun mensaje en 5 s");
            return message;
        }

        /** Siguiente mensaje de este tipo, ignorando los demas (por ejemplo el snapshot inicial). */
        JsonObject nextOfType(String type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                JsonObject message = messages.poll(500, TimeUnit.MILLISECONDS);
                if (message != null && type.equals(message.get("type").getAsString())) {
                    return message;
                }
            }
            throw new AssertionError("no llego ningun mensaje '" + type + "' en 8 s");
        }
    }

    private Client connectTo(AlertGateway gateway) throws Exception {
        Client client = new Client();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + gateway.boundPort()), new WebSocket.Listener() {
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
        sockets.add(socket);
        assertEquals("snapshot", client.next().get("type").getAsString());
        return client;
    }

    @Test
    void unaAlertaCreadaPorLaOtraReplicaLlegaALosOperadoresDeEsta() throws Exception {
        Client operatorOnB = connectTo(gatewayB);

        removedBy("BY_OTHER");
        engineA.processNewEvents();   // la replica A gana: solo ella inserta y avisa a sus listeners

        JsonObject message = operatorOnB.nextOfType("alert");
        assertEquals("POSSIBLE_THEFT", message.getAsJsonObject("alert").get("type").getAsString());
    }

    @Test
    void siLasDosReplicasProcesanElRetiroCadaOperadorRecibeLaAlertaUnaSolaVez() throws Exception {
        Client operatorOnA = connectTo(gatewayA);
        Client operatorOnB = connectTo(gatewayB);

        removedBy("BY_OTHER");
        engineA.processNewEvents();
        engineB.processNewEvents();   // pierde la carrera: la alerta ya existe, no la anuncia su propio motor

        assertEquals("alert", operatorOnA.nextOfType("alert").get("type").getAsString());
        assertEquals("alert", operatorOnB.nextOfType("alert").get("type").getAsString());
        // Tiempo de sobra para que cualquier mensaje repetido apareciera (el barrido de la replica corre cada ~0,5 s)
        Thread.sleep(2500);
        assertNull(findAlertMessage(operatorOnA), "el operador de A recibio la alerta dos veces");
        assertNull(findAlertMessage(operatorOnB), "el operador de B recibio la alerta dos veces");
    }

    @Test
    void lasAlertasAnterioresAlArranqueDeUnaReplicaNoSeReenvianComoNuevas() throws Exception {
        removedBy("BY_OTHER");
        engineA.processNewEvents();

        // Una replica que arranca ahora (por ejemplo tras un despliegue): la alerta vieja va en el snapshot, no como alerta nueva
        AlertStore storeC = AlertStore.inMemory(dbName);
        AlertEngine engineC = new AlertEngine(events, storeC);
        AlertGateway gatewayC = new AlertGateway(engineC, storeC, 0, Set.of("http://localhost:8080"));
        gatewayC.startAndWait();
        try {
            Client operatorOnC = connectTo(gatewayC);
            Thread.sleep(2500);
            assertNull(findAlertMessage(operatorOnC), "una alerta anterior al arranque no debe llegar como 'alert' nueva");
        } finally {
            gatewayC.shutdown();
            engineC.close();
            storeC.close();
        }
    }

    private static JsonObject findAlertMessage(Client client) {
        for (JsonObject message : client.messages) {
            if ("alert".equals(message.get("type").getAsString())) {
                return message;
            }
        }
        return null;
    }

}
