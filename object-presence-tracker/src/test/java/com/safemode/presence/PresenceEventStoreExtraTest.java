package com.safemode.presence;

import com.safemode.presence.PresenceEventStore.OwnerInfo;
import com.safemode.presence.PresenceEventStore.RemovalInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PresenceEventStoreExtraTest {

    private static PresenceEventStore newStore() {
        return PresenceEventStore.inMemory("extra_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Test
    void lasCamarasTienenNombrePorDefectoYSePuedenRenombrar() {
        try (PresenceEventStore store = newStore()) {
            assertEquals("CAM 1", store.cameraName(1));
            assertEquals("CAM 9", store.cameraName(9));
            assertEquals(PresenceEventStore.MAX_CAMERAS, store.cameraNames().size());

            store.renameCamera(2, "  Cancha sur ");
            assertEquals("Cancha sur", store.cameraName(2));
            assertEquals("CAM 1", store.cameraName(1));

            store.renameCamera(2, "Cancha norte");
            assertEquals("Cancha norte", store.cameraNames().get(2));

            store.renameCamera(2, "   ");
            assertEquals("CAM 2", store.cameraName(2));

            store.renameCamera(3, "Ca\nncha");
            assertEquals("Ca ncha", store.cameraName(3));

            store.renameCamera(4, null);
            assertEquals("CAM 4", store.cameraName(4));

            store.renameCamera(5, "x".repeat(40));
            assertEquals(40, store.cameraName(5).length());
        }
    }

    @Test
    void renombrarRechazaIdsFueraDeRangoYNombresLargos() {
        try (PresenceEventStore store = newStore()) {
            assertThrows(IllegalArgumentException.class, () -> store.renameCamera(0, "x"));
            assertThrows(IllegalArgumentException.class,
                    () -> store.renameCamera(PresenceEventStore.MAX_CAMERAS + 1, "x"));
            assertThrows(IllegalArgumentException.class, () -> store.renameCamera(1, "x".repeat(41)));
            assertEquals("CAM 1", store.cameraName(1));
        }
    }

    @Test
    void sinEventosLosMetodosDeConsultaDevuelvenValoresVacios() {
        try (PresenceEventStore store = newStore()) {
            assertNull(store.lastRemoval());
            assertNull(store.lastOwner("REMOVED"));
            assertNull(store.lastPersonNearby("REMOVED"));
            assertNull(store.lastFramePath("REMOVED"));
            assertNull(store.lastRemoverAiDescription());
            assertNull(store.lastOwnerAiDescription("REMOVED"));
            assertEquals(-1, store.lastTrackedObjectId("REMOVED"));
            assertEquals(0, store.countEvents("REMOVED"));
        }
    }

    @Test
    void losDatosDelUltimoEventoSeLeenPorTipo() {
        try (PresenceEventStore store = newStore()) {
            Instant now = Instant.now();
            OwnerInfo owner = new OwnerInfo(3L, "persona con camisa roja", "owner.png");
            RemovalInfo removal = new RemovalInfo("BY_OTHER", 4L, "persona con camisa azul", "remover.png");
            store.recordRegistered(7, "backpack", 1, 2, 3, 4, now, "reg.png", owner);
            store.recordRemoved(7, "backpack", 1, 2, 3, 4, now, true, "rem.png", owner, removal);

            assertEquals(1, store.countEvents("REGISTERED_AT_REST"));
            assertEquals(1, store.countEvents("REMOVED"));
            assertEquals(7, store.lastTrackedObjectId("REMOVED"));
            assertEquals("rem.png", store.lastFramePath("REMOVED"));
            assertEquals("reg.png", store.lastFramePath("REGISTERED_AT_REST"));
            assertEquals(Boolean.TRUE, store.lastPersonNearby("REMOVED"));
            assertNull(store.lastPersonNearby("REGISTERED_AT_REST"));
            assertEquals(owner, store.lastOwner("REGISTERED_AT_REST"));
            assertEquals(removal, store.lastRemoval());
        }
    }

    @Test
    void elDuenoPuedeEstarIncompletoOFaltar() {
        try (PresenceEventStore store = newStore()) {
            Instant now = Instant.now();
            store.recordRegistered(1, "backpack", 0, 0, 1, 1, now, null, null);
            assertNull(store.lastOwner("REGISTERED_AT_REST"));

            store.recordRegistered(2, "backpack", 0, 0, 1, 1, now, null, new OwnerInfo(null, "sin id", null));
            assertEquals(new OwnerInfo(null, "sin id", null), store.lastOwner("REGISTERED_AT_REST"));
        }
    }

    @Test
    void lasDescripcionesPorIaSeGuardanDespuesDelEvento() {
        try (PresenceEventStore store = newStore()) {
            Instant now = Instant.now();
            long registered = store.recordRegistered(1, "backpack", 0, 0, 1, 1, now, null, null);
            long removed = store.recordRemoved(1, "backpack", 0, 0, 1, 1, now, false, null, null,
                    new RemovalInfo("NO_ONE_NEAR", null, null, null));

            store.updateOwnerAiDescription(registered, "hombre alto");
            store.updateRemoverAiDescription(removed, "mujer baja");

            assertEquals("hombre alto", store.lastOwnerAiDescription("REGISTERED_AT_REST"));
            assertEquals("hombre alto", store.findById(registered).orElseThrow().ownerAiDescription());
            assertEquals("mujer baja", store.lastRemoverAiDescription());
        }
    }

    @Test
    void abreUnaBaseEnArchivoYConservaLosDatos(@TempDir Path tmp) {
        String path = tmp.resolve("presence").toString();
        long id;
        try (PresenceEventStore store = new PresenceEventStore(path)) {
            id = store.recordRegistered(1, "backpack", 0, 0, 1, 1, Instant.now(), null, null);
        }
        try (PresenceEventStore again = new PresenceEventStore(path)) {
            assertTrue(again.findById(id).isPresent());
        }
    }

    @Test
    void fromEnvironmentUsaElArchivoLocalSiNoHayVariable(@TempDir Path tmp) {
        assumeTrue(System.getenv(PresenceEventStore.DB_URL_ENV) == null, "SAFEMODE_DB_URL esta definida");
        try (PresenceEventStore store = PresenceEventStore.fromEnvironment(tmp.resolve("env-presence").toString())) {
            assertEquals(0, store.maxEventId());
        }
    }

    @Test
    void siLaConexionSeCierraTodosLosMetodosFallanConUnaExcepcionClara() {
        PresenceEventStore store = newStore();
        store.close();
        Instant now = Instant.now();

        List<Executable> calls = List.of(
                () -> store.recordRegistered(1, "backpack", 0, 0, 1, 1, now, null, null),
                () -> store.recordRemoved(1, "backpack", 0, 0, 1, 1, now, false, null, null, null),
                () -> store.findRemovedAfter(0),
                () -> store.findById(1),
                () -> store.findRecent(5),
                () -> store.maxEventId(),
                () -> store.cameraNames(),
                () -> store.cameraName(1),
                () -> store.renameCamera(1, "x"),
                () -> store.renameCamera(1, ""),
                () -> store.updateOwnerAiDescription(1, "x"),
                () -> store.updateRemoverAiDescription(1, "x"),
                () -> store.clearAllEvents(),
                () -> store.lastRemoval(),
                () -> store.lastPersonNearby("REMOVED"),
                () -> store.lastFramePath("REMOVED"),
                () -> store.lastTrackedObjectId("REMOVED"),
                () -> store.lastOwner("REMOVED"),
                () -> store.countEvents("REMOVED"),
                () -> store.lastRemoverAiDescription(),
                () -> store.lastOwnerAiDescription("REMOVED"));

        for (Executable call : calls) {
            assertThrows(IllegalStateException.class, call);
        }
    }
}