package com.safemode.presence;

import org.h2.tools.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La base se reinicia (el contenedor de PostgreSQL o el servidor H2 del compose) y la conexion que guardaba el almacen queda
 * muerta: antes el gateway fallaba hasta que alguien lo reiniciaba (ADR 0005, limite documentado). Estas pruebas apagan y
 * encienden un servidor H2 real (TCP, con archivo) y comprueban que el almacen se recupera solo y conserva los datos.
 */
class ReconnectingConnectionTest {

    @TempDir
    Path dir;

    private Server server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        server = newServer();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private Server newServer() throws SQLException {
        return Server.createTcpServer("-tcpPort", Integer.toString(port), "-baseDir", dir.toString(), "-ifNotExists").start();
    }

    private String url() {
        return "jdbc:h2:tcp://localhost:" + port + "/reconexion";
    }

    /** Apaga el servidor (corta las conexiones abiertas) y lo vuelve a encender sobre el mismo archivo. */
    private void restartServer() throws SQLException {
        server.stop();
        server = newServer();
    }

    @Test
    void mientrasLaConexionEsValidaSeUsaLaMismaSinReabrir() throws Exception {
        try (ReconnectingConnection connection = new ReconnectingConnection(url())) {
            Connection first = connection.get();
            assertSame(first, connection.get());
        }
    }

    @Test
    void trasReiniciarLaBaseDevuelveUnaConexionNuevaYLosDatosSiguenAhi() throws Exception {
        try (ReconnectingConnection connection = new ReconnectingConnection(url(), 50)) {
            try (Statement st = connection.get().createStatement()) {
                st.execute("CREATE TABLE prueba (id INT PRIMARY KEY, nombre VARCHAR(20))");
                st.execute("INSERT INTO prueba VALUES (1, 'antes del reinicio')");
            }
            Connection old = connection.get();

            restartServer();

            Connection fresh = connection.get();
            assertNotSame(old, fresh, "la conexion muerta se reemplaza");
            try (Statement st = fresh.createStatement(); ResultSet rs = st.executeQuery("SELECT nombre FROM prueba WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("antes del reinicio", rs.getString(1));
            }
        }
    }

    @Test
    void conLaBaseCaidaFallaSinColgarseYLaEsperaEntreIntentosNoVuelveAPagarLaConexion() throws Exception {
        try (ReconnectingConnection connection = new ReconnectingConnection(url(), 60_000)) {
            connection.get();
            server.stop();

            SQLException first = assertThrows(SQLException.class, connection::get);
            // dentro de la espera, el mismo error al instante: no se intenta conectar otra vez
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                SQLException again = assertThrows(SQLException.class, connection::get);
                assertSame(first, again);
            });
        }
    }

    @Test
    void cuandoLaBaseVuelveYPasaLaEsperaSeRecuperaSola() throws Exception {
        try (ReconnectingConnection connection = new ReconnectingConnection(url(), 50)) {
            connection.get();
            server.stop();
            assertThrows(SQLException.class, connection::get);

            server = newServer();
            Thread.sleep(120); // mas que la espera entre intentos (50 ms)

            try (Statement st = connection.get().createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    void despuesDeCerrarNoSeReabre() throws Exception {
        ReconnectingConnection connection = new ReconnectingConnection(url());
        connection.close();
        assertThrows(SQLException.class, connection::get);
    }

    /** Lo que importa del producto: el almacen de eventos sobrevive al reinicio de su base. */
    @Test
    void elAlmacenDeEventosSeRecuperaDelReinicioDeLaBase() throws Exception {
        try (PresenceEventStore store = PresenceEventStore.forUrl(url())) {
            long before = store.recordRegistered(1, "backpack", 10, 20, 30, 40, Instant.now(), "a.png", null);

            restartServer();

            // la primera consulta puede caer dentro de la espera entre intentos (1 s): se reintenta como lo hace el motor de alertas
            List<PresenceEventStore.StoredEvent> recent = untilItAnswers(() -> store.findRecent(5));
            assertEquals(List.of(before), recent.stream().map(PresenceEventStore.StoredEvent::id).toList());

            long after = store.recordRegistered(2, "suitcase", 1, 2, 3, 4, Instant.now(), "b.png", null);
            assertTrue(after > before, "tras recuperarse tambien se puede escribir");
        }
    }

    private static <T> T untilItAnswers(java.util.concurrent.Callable<T> call) throws Exception {
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
        throw new AssertionError("el almacen no se recupero en 10 s", last);
    }
}
