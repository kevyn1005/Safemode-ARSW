package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore;
import com.safemode.presence.PresenceEventStore.OwnerInfo;
import com.safemode.presence.PresenceEventStore.RemovalInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertEngineTest {

    private static final OwnerInfo OWNER = new OwnerInfo(1L, "persona con camisa roja", null);

    private PresenceEventStore events;
    private AlertStore alerts;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        String name = "alerts_" + UUID.randomUUID().toString().replace("-", "");
        events = PresenceEventStore.inMemory(name);
        alerts = AlertStore.inMemory(name);
        engine = new AlertEngine(events, alerts);
    }

    @AfterEach
    void tearDown() {
        engine.close();
        alerts.close();
        events.close();
    }

    private long removed(String kind) {
        return events.recordRemoved(1, "suitcase", 100, 100, 50, 80, Instant.now(), true, "frame.png", OWNER,
                new RemovalInfo(kind, 2L, "persona con camisa azul", "remover.png"));
    }

    @Test
    void creaUnaAlertaPorCadaRetiroSospechosoYNingunaPorElRetiroNormal() {
        events.recordRegistered(1, "suitcase", 100, 100, 50, 80, Instant.now(), "frame.png", OWNER);
        removed("BY_OWNER");
        removed("BY_OTHER");
        removed("NO_ONE_NEAR");

        List<Alert> created = engine.processNewEvents();

        assertEquals(2, created.size());
        assertEquals(2, alerts.listAll().size());
        assertTrue(created.stream().anyMatch(a -> a.type() == AlertType.POSSIBLE_THEFT));
        assertTrue(created.stream().anyMatch(a -> a.type() == AlertType.OBJECT_VANISHED));
    }

    @Test
    void noRepiteAlertasAlProcesarDosVeces() {
        removed("BY_OTHER");
        assertEquals(1, engine.processNewEvents().size());
        assertTrue(engine.processNewEvents().isEmpty());
        assertEquals(1, alerts.listAll().size());
    }

    @Test
    void unMotorNuevoNoDuplicaLasAlertasYaGuardadas() {
        removed("BY_OTHER");
        engine.processNewEvents();

        try (AlertEngine restarted = new AlertEngine(events, alerts)) {
            assertTrue(restarted.processNewEvents().isEmpty());
        }
        assertEquals(1, alerts.listAll().size());
    }

    @Test
    void avisaALosListenersYUnListenerQueFallaNoImpideAlOtro() {
        List<Alert> received = new ArrayList<>();
        engine.addListener(alert -> {
            throw new IllegalStateException("listener roto");
        });
        engine.addListener(received::add);
        removed("BY_OTHER");

        engine.processNewEvents();

        assertEquals(1, received.size());
        assertEquals(Severity.HIGH, received.get(0).severity());
    }

    @Test
    void siSeVaciaLaTablaDeEventosLosNuevosRetirosGeneranAlerta() {
        removed("BY_OTHER");
        removed("BY_OTHER");
        assertEquals(2, engine.processNewEvents().size());

        events.clearAllEvents();
        removed("NO_ONE_NEAR");

        List<Alert> created = engine.processNewEvents();
        assertEquals(1, created.size());
        assertEquals(AlertType.OBJECT_VANISHED, created.get(0).type());
    }

    @Test
    void laAlertaGuardaLaFotoYSeMarcaComoRevisada() {
        removed("BY_OTHER");
        Alert alert = engine.processNewEvents().get(0);

        assertEquals("frame.png", alert.framePath());
        assertFalse(alert.acknowledged());
        assertEquals(1, alerts.listPending().size());

        assertTrue(alerts.acknowledge(alert.id()));
        assertTrue(alerts.listPending().isEmpty());
        assertTrue(alerts.findById(alert.id()).orElseThrow().acknowledged());
        assertFalse(alerts.acknowledge(9999));
    }

    @Test
    void laEvidenciaTraeLosDatosActualesDelEvento() {
        long eventId = removed("BY_OTHER");
        Alert alert = engine.processNewEvents().get(0);

        events.updateRemoverAiDescription(eventId, "hombre de camisa azul");

        assertEquals("hombre de camisa azul", engine.evidenceOf(alert).orElseThrow().removerAiDescription());
    }

    @Test
    void elHiloEnSegundoPlanoProcesaLosEventos() throws InterruptedException {
        List<Alert> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        engine.addListener(received::add);
        removed("BY_OTHER");

        engine.start(Duration.ofMillis(50));
        long deadline = System.currentTimeMillis() + 5000;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        engine.stop();

        assertEquals(1, received.size());
    }
}
