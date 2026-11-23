package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore;
import com.safemode.presence.PresenceEventStore.RemovalInfo;
import org.h2.tools.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * El motor de alertas sobrevive al reinicio de su base (ADR 0005: antes el gateway fallaba hasta que alguien lo reiniciaba).
 * Se usa un servidor H2 real (TCP, con archivo) que se apaga y se vuelve a encender, como el contenedor de PostgreSQL.
 */
class DatabaseRestartTest {

    @TempDir
    Path dir;

    private Server server;
    private int port;
    private PresenceEventStore events;
    private AlertStore alerts;
    private AlertEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        server = newServer();
        String url = "jdbc:h2:tcp://localhost:" + port + "/reinicio";
        events = PresenceEventStore.forUrl(url);
        alerts = AlertStore.forUrl(url);
        engine = new AlertEngine(events, alerts);
    }

    @AfterEach
    void tearDown() {
        engine.close();
        alerts.close();
        events.close();
        server.stop();
    }

    private Server newServer() throws SQLException {
        return Server.createTcpServer("-tcpPort", Integer.toString(port), "-baseDir", dir.toString(), "-ifNotExists").start();
    }

    private void theftEvent() {
        events.recordRemoved(1, "suitcase", 100, 100, 50, 80, Instant.now(), true, "frame.png", null,
                new RemovalInfo("BY_OTHER", 2L, "persona con camisa azul", "remover.png"));
    }

    @Test
    void trasReiniciarLaBaseElMotorSigueCreandoAlertasSinReiniciarElGateway() throws Exception {
        theftEvent();
        assertEquals(1, engine.processNewEvents().size());

        server.stop();
        // con la base caida el ciclo del motor falla (lo atrapa y lo reintenta en el siguiente): no puede dar por buena la consulta
        assertThrows(IllegalStateException.class, () -> engine.processNewEvents());

        server = newServer();
        // un retiro que llega justo despues de volver la base (puede caer dentro de la espera entre intentos: se reintenta)
        untilItAnswers(() -> {
            theftEvent();
            return null;
        });
        List<Alert> created = untilItAnswers(() -> engine.processNewEvents());

        assertEquals(1, created.size(), "la alerta del evento nuevo se crea, y la del evento viejo no se duplica");
        assertEquals(2, alerts.listAll().size());
    }

    private static <T> T untilItAnswers(Callable<T> call) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        IllegalStateException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return call.call();
            } catch (IllegalStateException e) {
                last = e;
                Thread.sleep(200);
            }
        }
        throw new AssertionError("el motor no se recupero en 10 s", last);
    }
}
