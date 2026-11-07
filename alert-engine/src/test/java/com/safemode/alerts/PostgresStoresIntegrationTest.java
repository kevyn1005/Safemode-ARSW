package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore;
import com.safemode.presence.PresenceEventStore.OwnerInfo;
import com.safemode.presence.PresenceEventStore.RemovalInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los almacenes de eventos y alertas sobre PostgreSQL de verdad (ADR 0005). Solo corre si SAFEMODE_TEST_PG_URL apunta a una
 * base (el compose de persistencia publica una en 127.0.0.1:5433); sin la variable se omite, asi el build normal no depende
 * de Docker. Cada prueba usa su propio esquema y lo borra al terminar.
 *
 * <pre>
 *   $env:SAFEMODE_TEST_PG_URL = "jdbc:postgresql://localhost:5433/safemode?user=safemode&amp;password=lab-postgres"
 *   mvn -pl alert-engine -am test -Dtest=PostgresStoresIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class PostgresStoresIntegrationTest {

    private static final OwnerInfo OWNER = new OwnerInfo(1L, "persona con camisa roja", null);

    private String baseUrl;
    private String schema;
    private String url;
    private PresenceEventStore events;
    private AlertStore alerts;

    @BeforeEach
    void setUp() throws SQLException {
        baseUrl = System.getenv("SAFEMODE_TEST_PG_URL");
        Assumptions.assumeTrue(baseUrl != null && !baseUrl.isBlank(), "SAFEMODE_TEST_PG_URL no esta definida: se omite");
        schema = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(baseUrl); Statement st = admin.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        events = PresenceEventStore.forUrl(url);
        alerts = AlertStore.forUrl(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (events != null) {
            events.close();
        }
        if (alerts != null) {
            alerts.close();
        }
        if (baseUrl != null && schema != null) {
            try (Connection admin = DriverManager.getConnection(baseUrl); Statement st = admin.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void theWholePipelineWorksOnPostgreSQLAndTheUniqueConstraintKeepsOneAlertPerEvent() {
        try (AlertEngine engine = new AlertEngine(events, alerts)) {
            events.recordRegistered(1, "suitcase", 100, 100, 50, 80, Instant.now(), "frame.png", OWNER);
            events.recordRemoved(1, "suitcase", 100, 100, 50, 80, Instant.now(), true, "frame.png", OWNER,
                    new RemovalInfo("BY_OTHER", 2L, "persona con camisa azul", "remover.png"));

            List<Alert> created = engine.processNewEvents();
            assertEquals(1, created.size());
            assertEquals(AlertType.POSSIBLE_THEFT, created.get(0).type());

            // HU-14: reprocesar el mismo evento (o llegar por otro camino) no duplica la alerta: lo impone UNIQUE(event_id, event_occurred_at)
            Alert first = created.get(0);
            Optional<Alert> again = alerts.insertIfAbsent(first.eventId(), first.eventOccurredAt(), first.type(), first.severity(),
                    first.className(), first.message(), first.framePath(), Instant.now());
            assertTrue(again.isEmpty());
            assertEquals(1, alerts.listAll().size());

            // HU-16: revisar una alerta la saca de las pendientes
            assertEquals(1, alerts.listPending().size());
            assertTrue(alerts.acknowledge(first.id()));
            assertEquals(0, alerts.listPending().size());
        }
    }

    @Test
    void instantsRoundTripExactlyWhateverTheClientTimeZone() {
        // microsegundos: es la precision de PostgreSQL; con TIMESTAMP sin zona el instante se corria horas segun la zona del cliente
        Instant when = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long id = events.recordRemoved(1, "backpack", 10, 10, 20, 20, when, false, null, null,
                new RemovalInfo("NO_ONE_NEAR", null, null, null));
        assertEquals(when, events.findById(id).orElseThrow().occurredAt());
    }

    @Test
    void cameraNamesUseUpsertAndGeneratedIdsRestartAfterTruncate() {
        events.renameCamera(2, "Bodega");
        events.renameCamera(2, "Bodega norte");
        assertEquals("Bodega norte", events.cameraName(2));

        long first = events.recordRegistered(1, "handbag", 1, 1, 5, 5, Instant.now(), null, null);
        long second = events.recordRegistered(1, "handbag", 1, 1, 5, 5, Instant.now(), null, null);
        assertEquals(first + 1, second);
        events.clearAllEvents();
        assertEquals(0, events.maxEventId());
    }
}
