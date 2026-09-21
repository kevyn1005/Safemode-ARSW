package com.safemode.presence;

import com.safemode.presence.PresenceEventStore.OwnerInfo;
import com.safemode.presence.PresenceEventStore.RemovalInfo;
import com.safemode.presence.PresenceEventStore.StoredEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceEventStoreQueryTest {

    private static PresenceEventStore newStore() {
        return PresenceEventStore.inMemory("query_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Test
    void findRemovedAfterDevuelveSoloLosRetirosPosterioresEnOrden() {
        try (PresenceEventStore store = newStore()) {
            Instant now = Instant.now();
            store.recordRegistered(1, "backpack", 10, 20, 30, 40, now, "a.png", null);
            long first = store.recordRemoved(1, "backpack", 10, 20, 30, 40, now, false, "b.png", null,
                    new RemovalInfo("NO_ONE_NEAR", null, null, null));
            long second = store.recordRemoved(2, "handbag", 1, 2, 3, 4, now, true, null, null,
                    new RemovalInfo("BY_OTHER", 5L, "persona con camisa azul", "c.png"));

            assertEquals(List.of(first, second), store.findRemovedAfter(0).stream().map(StoredEvent::id).toList());
            assertEquals(List.of(second), store.findRemovedAfter(first).stream().map(StoredEvent::id).toList());
            assertTrue(store.findRemovedAfter(second).isEmpty());
        }
    }

    @Test
    void findByIdDevuelveTodosLosCamposIncluidosLosNulos() {
        try (PresenceEventStore store = newStore()) {
            Instant now = Instant.parse("2026-03-04T05:06:07Z");
            long id = store.recordRemoved(9, "suitcase", 11, 22, 33, 44, now, true, "frame.png",
                    new OwnerInfo(3L, "persona con camisa roja", "owner.png"),
                    new RemovalInfo("BY_OTHER", 4L, "persona con camisa azul", "remover.png"));
            store.updateRemoverAiDescription(id, "hombre alto");

            StoredEvent event = store.findById(id).orElseThrow();

            assertEquals(9, event.trackedObjectId());
            assertEquals("suitcase", event.className());
            assertEquals("REMOVED", event.eventType());
            assertEquals(now, event.occurredAt());
            assertEquals(11, event.x());
            assertEquals(44, event.height());
            assertEquals(Boolean.TRUE, event.personNearby());
            assertEquals("frame.png", event.framePath());
            assertEquals(3L, event.ownerPersonId());
            assertEquals("persona con camisa roja", event.ownerDescription());
            assertEquals("BY_OTHER", event.removalKind());
            assertEquals(4L, event.removerPersonId());
            assertEquals("hombre alto", event.removerAiDescription());

            long registered = store.recordRegistered(9, "suitcase", 1, 2, 3, 4, now, null, null);
            StoredEvent bare = store.findById(registered).orElseThrow();
            assertNull(bare.personNearby());
            assertNull(bare.ownerPersonId());
            assertNull(bare.removalKind());
            assertNull(bare.removerPersonId());
            assertTrue(store.findById(9999).isEmpty());
        }
    }

    @Test
    void laUrlDeLaBaseVieneDeLaVariableDeEntornoOEsElArchivoLocal() {
        assertEquals("jdbc:h2:file:./datos/presence;AUTO_SERVER=TRUE", PresenceEventStore.jdbcUrlFor(null, "./datos/presence"));
        assertEquals("jdbc:h2:file:./datos/presence;AUTO_SERVER=TRUE", PresenceEventStore.jdbcUrlFor("   ", "./datos/presence"));
        assertEquals("jdbc:h2:tcp://localhost:9092/presence",
                PresenceEventStore.jdbcUrlFor(" jdbc:h2:tcp://localhost:9092/presence ", "./datos/presence"));
    }

    @Test
    void forUrlAbreLaBaseIndicadaYDosConexionesVenLosMismosDatos() {
        String url = "jdbc:h2:mem:byurl_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        try (PresenceEventStore writer = PresenceEventStore.forUrl(url); PresenceEventStore reader = PresenceEventStore.forUrl(url)) {
            long id = writer.recordRegistered(1, "backpack", 0, 0, 1, 1, Instant.now(), null, null);

            assertTrue(reader.findById(id).isPresent());
        }
    }

    @Test
    void unaUrlInvalidaDaUnErrorQueDiceCualEra() {
        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> PresenceEventStore.forUrl("jdbc:h2:tcp://localhost:1/no-existe"));
        assertTrue(error.getMessage().contains("jdbc:h2:tcp://localhost:1/no-existe"));
    }

    @Test
    void maxEventIdEsCeroSinEventosYSeReiniciaAlVaciar() {
        try (PresenceEventStore store = newStore()) {
            assertEquals(0, store.maxEventId());
            store.recordRegistered(1, "backpack", 0, 0, 1, 1, Instant.now(), null, null);
            store.recordRegistered(2, "backpack", 0, 0, 1, 1, Instant.now(), null, null);
            assertEquals(2, store.maxEventId());
            store.clearAllEvents();
            assertEquals(0, store.maxEventId());
        }
    }
}
