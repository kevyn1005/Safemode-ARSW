package com.safemode.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.safemode.alerts.AlertEngine;
import com.safemode.alerts.AlertStore;
import com.safemode.presence.PresenceEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sesiones compartidas (SAFEMODE_SESSION_STORE=database, docs/disponibilidad): con varias replicas del gateway detras de un
 * balanceador, el operador que inicio sesion en una debe ser reconocido por las demas, y cerrar la sesion en una debe valer
 * en todas. Cada "replica" aqui es un AuthService con su propio almacen sobre la MISMA base.
 */
class SharedSessionsTest {

    private static final String ORIGIN = "http://localhost:8080";
    private static final AuthService.User ANA = new AuthService.User("ana@example.com", "Ana");

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-19T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration amount) { now = now.plus(amount); }
    }

    private String url;
    private final MutableClock clock = new MutableClock();
    private JdbcSessionStore storeA;
    private JdbcSessionStore storeB;
    private AuthService authA;
    private AuthService authB;
    private final List<AutoCloseable> toClose = new CopyOnWriteArrayList<>();
    private final List<WebSocket> sockets = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:sessions_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        storeA = JdbcSessionStore.forUrl(url);
        storeB = JdbcSessionStore.forUrl(url);
        authA = new AuthService("cid", Set.of("ana@example.com"), token -> Optional.empty(), clock, storeA);
        authB = new AuthService("cid", Set.of("ana@example.com"), token -> Optional.empty(), clock, storeB);
    }

    @AfterEach
    void tearDown() throws Exception {
        sockets.forEach(WebSocket::abort);
        for (AutoCloseable c : toClose) {
            c.close();
        }
        storeA.close();
        storeB.close();
    }

    private static String cookie(String id) {
        return AuthService.COOKIE_NAME + "=" + id;
    }

    @Test
    void laSesionIniciadaEnUnaReplicaLaReconoceLaOtra() {
        String id = authA.createSession(ANA);

        Optional<AuthService.User> user = authB.userFor(cookie(id));

        assertTrue(user.isPresent(), "la otra replica debia reconocer la sesion");
        assertEquals("ana@example.com", user.get().email());
        assertEquals("Ana", user.get().name());
    }

    @Test
    void cerrarSesionEnUnaReplicaLaCierraEnTodas() {
        String id = authA.createSession(ANA);
        assertTrue(authB.userFor(cookie(id)).isPresent());

        authA.logout(cookie(id));

        assertTrue(authB.userFor(cookie(id)).isEmpty(), "la sesion cerrada en A no debe valer en B");
    }

    @Test
    void laSesionVenceEnTodasLasReplicasConElTiempo() {
        String id = authA.createSession(ANA);
        clock.advance(AuthService.SESSION_TTL.plusSeconds(1));

        assertTrue(authB.userFor(cookie(id)).isEmpty());
        assertTrue(authA.userFor(cookie(id)).isEmpty());
    }

    @Test
    void unaCookieInventadaNoEsUnaSesion() {
        assertTrue(authB.userFor(cookie("no-existe-en-ninguna-replica")).isEmpty());
        assertTrue(authB.userFor(null).isEmpty());
    }

    @Test
    void labaseNoGuardaElIdentificadorDeLaSesionSoloSuHuella() throws Exception {
        String id = authA.createSession(ANA);

        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM operator_session")) {
            assertTrue(rs.next());
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                row.append(rs.getString(i)).append('|');
            }
            assertFalse(row.toString().contains(id), "el id de sesion (el valor de la cookie) no debe quedar en la base: " + row);
            assertEquals(64, rs.getString("id_hash").length(), "SHA-256 en hexadecimal");
        }
    }

    @Test
    void sinBaseDisponibleLaSesionNoSeAcepta() {
        String id = authA.createSession(ANA);
        storeB.close();   // la base "se cae" para la replica B: debe fallar cerrado, sin lanzar excepcion

        assertTrue(authB.userFor(cookie(id)).isEmpty());
    }

    @Test
    void lasSesionesVencidasSeLimpianAlCrearOtra() throws Exception {
        authA.createSession(ANA);
        clock.advance(AuthService.SESSION_TTL.plusSeconds(1));
        authA.createSession(ANA);   // aqui se purgan las vencidas

        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM operator_session")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    // ------------------------------------------------------------------ el WebSocket de la otra replica

    private static final class Client {
        final LinkedBlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
        volatile Integer closeCode;

        JsonObject next() throws InterruptedException {
            JsonObject message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "no llego ningun mensaje en 5 s");
            return message;
        }
    }

    private Client connect(AlertGateway gateway, String cookie) throws Exception {
        Client client = new Client();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", ORIGIN).header("Cookie", cookie)
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

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        client.closeCode = statusCode;
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return client;
    }

    @Test
    void elSocketAbiertoEnUnaReplicaSeCierraCuandoLaSesionSeCierraEnLaOtra() throws Exception {
        String dbName = "gw_sessions_" + UUID.randomUUID().toString().replace("-", "");
        PresenceEventStore events = PresenceEventStore.inMemory(dbName);
        AlertStore alerts = AlertStore.inMemory(dbName);
        AlertEngine engine = new AlertEngine(events, alerts);
        AlertGateway gatewayB = new AlertGateway(engine, alerts, 0, Set.of(ORIGIN)).withAuth(authB);
        gatewayB.startAndWait();
        toClose.add(() -> { gatewayB.shutdown(); engine.close(); alerts.close(); events.close(); });

        String id = authA.createSession(ANA);               // el operador inicio sesion contra la replica A...
        Client client = connect(gatewayB, cookie(id));      // ...y el balanceador lo manda a la B: la reconoce
        assertEquals("snapshot", client.next().get("type").getAsString());
        assertNull(client.closeCode);

        authA.logout(cookie(id));                           // cierra sesion en A (otra pestana, otro operador de guardia...)

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (client.closeCode == null && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(1008, client.closeCode, "B debia cerrar el socket de una sesion que ya no vale (barrido de 2 s)");
    }
}
