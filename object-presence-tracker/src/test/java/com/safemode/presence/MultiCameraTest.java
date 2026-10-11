package com.safemode.presence;

import com.safemode.presence.PresenceEventStore.StoredEvent;
import com.safemode.vision.ObjectDetector.Detection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Soporte de varias camaras: camera_id en los eventos, nombres editables y un seguimiento independiente por camara. */
class MultiCameraTest {

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static PresenceEventStore newStore() {
        return PresenceEventStore.inMemory("camaras_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Detection suitcase() {
        return new Detection("suitcase", 0.9f, 100, 100, 80, 60);
    }

    private static BufferedImage blank() {
        return new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
    }

    /** Deja una maleta quieta hasta que se registre y luego la hace desaparecer hasta que se de por retirada. */
    private static void registrarYRetirar(ObjectTracker tracker, MutableClock clock) {
        tracker.onFrame(blank(), List.of(suitcase()));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(blank(), List.of(suitcase()));
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(blank(), List.of());
        }
    }

    // ---------------------------------------------------------------- camera_id en los eventos

    @Test
    void sinIndicarCamaraLosEventosSonDeLaCamara1() {
        try (PresenceEventStore store = newStore()) {
            long id = store.recordRegistered(1, "backpack", 1, 2, 3, 4, Instant.now(), null, null);
            assertEquals(1, store.findById(id).orElseThrow().cameraId());
        }
    }

    @Test
    void elEventoGuardaLaCamaraIndicadaYLaDevuelveAlLeerlo() {
        try (PresenceEventStore store = newStore()) {
            long registered = store.recordRegistered(2, 7, "backpack", 1, 2, 3, 4, Instant.now(), null, null);
            long removed = store.recordRemoved(2, 7, "backpack", 1, 2, 3, 4, Instant.now(), false, null, null,
                    new PresenceEventStore.RemovalInfo("NO_ONE_NEAR", null, null, null));

            assertEquals(2, store.findById(registered).orElseThrow().cameraId());
            StoredEvent event = store.findRemovedAfter(0).get(0);
            assertEquals(removed, event.id());
            assertEquals(2, event.cameraId());
        }
    }

    // ---------------------------------------------------------------- nombres de camara

    @Test
    void lasCamarasSeLlamanCamNHastaQueElUsuarioLasRenombre() {
        try (PresenceEventStore store = newStore()) {
            assertEquals("CAM 1", store.cameraName(1));
            assertEquals("CAM 2", store.cameraName(2));
            assertEquals(PresenceEventStore.MAX_CAMERAS, store.cameraNames().size());

            store.renameCamera(2, "  Cancha norte ");

            assertEquals("Cancha norte", store.cameraName(2));
            assertEquals("CAM 1", store.cameraName(1), "renombrar una camara no toca a las demas");
        }
    }

    @Test
    void unNombreVacioDevuelveLaCamaraASuNombrePorDefecto() {
        try (PresenceEventStore store = newStore()) {
            store.renameCamera(1, "Entrada");
            store.renameCamera(1, "   ");
            assertEquals("CAM 1", store.cameraName(1));
        }
    }

    @Test
    void elNombreSeLimpiaYSeValidaElRango() {
        try (PresenceEventStore store = newStore()) {
            store.renameCamera(1, "Cancha\nnorte\t1");
            assertEquals("Cancha norte 1", store.cameraName(1), "los caracteres de control se reemplazan por espacios");

            assertThrows(IllegalArgumentException.class, () -> store.renameCamera(0, "x"));
            assertThrows(IllegalArgumentException.class, () -> store.renameCamera(PresenceEventStore.MAX_CAMERAS + 1, "x"));
            assertThrows(IllegalArgumentException.class, () -> store.renameCamera(1, "x".repeat(41)));
            assertEquals("Cancha norte 1", store.cameraName(1), "un nombre invalido no cambia el anterior");
        }
    }

    @Test
    void vaciarLosEventosNoBorraLosNombresDeLasCamaras() {
        try (PresenceEventStore store = newStore()) {
            store.renameCamera(2, "Cancha sur");
            store.recordRegistered(1, "backpack", 1, 2, 3, 4, Instant.now(), null, null);

            store.clearAllEvents();

            assertEquals("Cancha sur", store.cameraName(2));
        }
    }

    // ---------------------------------------------------------------- un seguimiento por camara

    @Test
    void elTrackerDeLaCamara2GuardaSusEventosConLaCamara2YFotosConPrefijo(@TempDir Path photos) throws Exception {
        MutableClock clock = new MutableClock();
        try (PresenceEventStore store = newStore()) {
            ObjectTracker tracker = new ObjectTracker(store, clock, photos).withCameraId(2);

            registrarYRetirar(tracker, clock);

            List<StoredEvent> removed = store.findRemovedAfter(0);
            assertEquals(1, removed.size());
            assertEquals(2, removed.get(0).cameraId());
            try (var files = Files.list(photos)) {
                List<String> names = files.map(f -> f.getFileName().toString()).toList();
                assertFalse(names.isEmpty());
                assertTrue(names.stream().allMatch(n -> n.startsWith("cam2_obj")), "fotos de la camara 2: " + names);
            }
        }
    }

    @Test
    void laCamara1NoCambiaElNombreDeSusFotos(@TempDir Path photos) throws Exception {
        MutableClock clock = new MutableClock();
        try (PresenceEventStore store = newStore()) {
            registrarYRetirar(new ObjectTracker(store, clock, photos), clock);

            try (var files = Files.list(photos)) {
                assertTrue(files.map(f -> f.getFileName().toString()).allMatch(n -> n.startsWith("obj")),
                        "compatibilidad: sin camara indicada las fotos se llaman como siempre");
            }
        }
    }

    @Test
    void dosCamarasMismosIdsDeObjetoPeroEventosYEstadoSeparados(@TempDir Path photos) {
        MutableClock clock = new MutableClock();
        try (PresenceEventStore store = newStore()) {
            ObjectTracker cam1 = new ObjectTracker(store, clock, photos).withCameraId(1);
            ObjectTracker cam2 = new ObjectTracker(store, clock, photos).withCameraId(2);

            // la misma maleta aparece en las dos camaras: cada seguimiento le da el id 1 y la registra por su cuenta
            cam1.onFrame(blank(), List.of(suitcase()));
            cam2.onFrame(blank(), List.of(suitcase()));
            clock.advance(Duration.ofSeconds(4));
            cam1.onFrame(blank(), List.of(suitcase()));
            cam2.onFrame(blank(), List.of(suitcase()));
            assertEquals(2, store.countEvents("REGISTERED_AT_REST"));

            // solo se retira de la camara 2: la camara 1 no debe enterarse
            for (int i = 0; i < 3; i++) {
                clock.advance(Duration.ofMillis(500));
                cam1.onFrame(blank(), List.of(suitcase()));
                cam2.onFrame(blank(), List.of());
            }

            List<StoredEvent> removed = store.findRemovedAfter(0);
            assertEquals(1, removed.size(), "el retiro de una camara no genera un retiro en la otra");
            assertEquals(2, removed.get(0).cameraId());
            assertEquals(1, removed.get(0).trackedObjectId(), "ambas camaras numeran sus objetos desde 1");
        }
    }

    @Test
    void elIdDeCamaraDebeSerPositivo() {
        try (PresenceEventStore store = newStore()) {
            ObjectTracker tracker = new ObjectTracker(store);
            assertThrows(IllegalArgumentException.class, () -> tracker.withCameraId(0));
        }
    }
}
