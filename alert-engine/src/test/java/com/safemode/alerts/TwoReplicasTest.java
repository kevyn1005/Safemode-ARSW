package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore;
import com.safemode.presence.PresenceEventStore.OwnerInfo;
import com.safemode.presence.PresenceEventStore.RemovalInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dos replicas del gateway (cada una con su motor y su propia conexion) leen la MISMA base: ambas ven el mismo retiro
 * y ambas intentan crear la alerta. La base decide (restriccion UNIQUE): una sola alerta por evento, y la replica que
 * pierde la carrera no debe fallar. Ver docs/disponibilidad.
 */
class TwoReplicasTest {

    private static final OwnerInfo OWNER = new OwnerInfo(1L, "persona con camisa roja", null);

    private String dbName;
    private PresenceEventStore events;
    private AlertStore storeA;
    private AlertStore storeB;

    @BeforeEach
    void setUp() {
        dbName = "replicas_" + UUID.randomUUID().toString().replace("-", "");
        events = PresenceEventStore.inMemory(dbName);
        storeA = AlertStore.inMemory(dbName);   // misma base, otra conexion: es lo que hace cada replica
        storeB = AlertStore.inMemory(dbName);
    }

    @AfterEach
    void tearDown() {
        storeA.close();
        storeB.close();
        events.close();
    }

    private long removed(String kind) {
        return events.recordRemoved(1, "suitcase", 100, 100, 50, 80, Instant.now(), true, "frame.png", OWNER,
                new RemovalInfo(kind, 2L, "persona con camisa azul", "remover.png"));
    }

    @Test
    void dosReplicasQueProcesanLosMismosRetirosCreanUnaSolaAlertaPorEventoYNingunaFalla() throws Exception {
        int total = 150;
        for (int i = 0; i < total; i++) {
            removed("BY_OTHER");
        }
        AlertEngine engineA = new AlertEngine(events, storeA);
        AlertEngine engineB = new AlertEngine(events, storeB);

        AtomicInteger createdByA = new AtomicInteger();
        AtomicInteger createdByB = new AtomicInteger();
        engineA.addListener(a -> createdByA.incrementAndGet());
        engineB.addListener(a -> createdByB.incrementAndGet());

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (AlertEngine engine : List.of(engineA, engineB)) {
                tasks.add(pool.submit(() -> {
                    go.await();
                    engine.processNewEvents();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);   // si una replica lanza por la carrera, el get() propaga la excepcion
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(total, storeA.listAll().size(), "una alerta por evento, ni mas ni menos");
        assertEquals(total, createdByA.get() + createdByB.get(),
                "cada alerta la anuncia exactamente una replica (la que la inserto): asi las notificaciones salen una vez");
        assertTrue(createdByA.get() > 0 || createdByB.get() > 0);
    }
}
