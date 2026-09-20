package com.safemode.presence;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas de la logica de ObjectTracker con detecciones simuladas: no
 * necesitan camara ni pantalla (a diferencia de ObjectPresenceTrackerTest,
 * que si depende de Robot/Xvfb). El reloj se controla a mano con
 * MutableClock para no depender de Thread.sleep real.
 *
 * En estas pruebas se le pasa "null" como frame a onFrame: no hay una
 * imagen real de camara, y ObjectTracker esta preparado para eso (ver
 * guardaUnaImagenDelFrameCuandoRegistraElObjeto para el caso con imagen).
 */
class ObjectTrackerTest {

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static Detection suitcase(int x, int y) {
        return new Detection("suitcase", 0.9f, x, y, 80, 60);
    }

    private static Detection person(int x, int y) {
        return new Detection("person", 0.9f, x, y, 50, 120);
    }

    @Test
    void registraElObjetoCuandoQuedaQuietoElTiempoSuficiente() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("registra-en-reposo");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(suitcase(100, 100)));
        assertEquals(0, store.countEvents("REGISTERED_AT_REST"),
                "no debe registrarse apenas aparece, antes de cumplir el umbral de reposo");

        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(suitcase(105, 95))); // dentro de la tolerancia de movimiento

        assertEquals(1, store.countEvents("REGISTERED_AT_REST"),
                "debe registrarse una vez superado el umbral de reposo (3s) quieto");
    }

    @Test
    void noRegistraUnObjetoQueSigueMoviendose() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("nunca-quieto");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        int x = 100;
        for (int i = 0; i < 6; i++) {
            tracker.onFrame(null, List.of(suitcase(x, 100)));
            clock.advance(Duration.ofSeconds(1));
            x += 60; // se mueve mas alla de la tolerancia en cada frame
        }

        assertEquals(0, store.countEvents("REGISTERED_AT_REST"),
                "un objeto que nunca deja de moverse no deberia quedar en reposo");
    }

    @Test
    void marcaRetiradoUnObjetoEnReposoQueDesapareceConUnaPersonaCerca() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("retiro-con-persona");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(suitcase(100, 100)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(suitcase(100, 100)));
        assertEquals(1, store.countEvents("REGISTERED_AT_REST"));

        // la maleta desaparece de la escena; hay una persona justo al lado
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of(person(120, 95)));
        }

        assertEquals(1, store.countEvents("REMOVED"),
                "debe registrarse el retiro tras varios frames sin ver el objeto");
        assertEquals(Boolean.TRUE, store.lastPersonNearby("REMOVED"),
                "debe quedar anotado que habia una persona cerca al momento del retiro");
    }

    @Test
    void marcaRetiradoSinPersonaCercaSiNadieEstabaAlLado() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("retiro-sin-persona");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(suitcase(100, 100)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(suitcase(100, 100)));

        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of()); // escena vacia, nadie cerca
        }

        assertEquals(1, store.countEvents("REMOVED"));
        assertEquals(Boolean.FALSE, store.lastPersonNearby("REMOVED"));
    }

    @Test
    void unObjetoEnReposoQueSeMueveBruscamenteSeMarcaRetiradoDeInmediato() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("movimiento-brusco");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(suitcase(100, 100)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(suitcase(100, 100)));
        assertEquals(1, store.countEvents("REGISTERED_AT_REST"));

        clock.advance(Duration.ofMillis(500));
        tracker.onFrame(null, List.of(suitcase(400, 100))); // salto grande: alguien la levanto y la movio

        assertEquals(1, store.countEvents("REMOVED"),
                "un movimiento brusco estando en reposo debe tratarse como un retiro inmediato");
    }

    @Test
    void objetosDeClasesDistintasNoSeConfundenEntreSi() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("clases-distintas");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        // una maleta y una mochila en la misma posicion aproximada, en el mismo frame
        tracker.onFrame(null, List.of(
                new Detection("suitcase", 0.9f, 100, 100, 80, 60),
                new Detection("backpack", 0.9f, 105, 105, 70, 90)
        ));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(
                new Detection("suitcase", 0.9f, 100, 100, 80, 60),
                new Detection("backpack", 0.9f, 105, 105, 70, 90)
        ));

        assertEquals(2, store.countEvents("REGISTERED_AT_REST"),
                "cada clase debe rastrearse por separado, aunque las cajas se solapen");
    }

    @Test
    void guardaUnaImagenDelFrameCuandoRegistraElObjeto(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("guarda-imagen");
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir);

        BufferedImage frame = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);

        tracker.onFrame(frame, List.of(suitcase(100, 100)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(frame, List.of(suitcase(100, 100)));

        String framePath = store.lastFramePath("REGISTERED_AT_REST");
        assertNotNull(framePath, "debe guardar la ruta de la imagen junto con el evento");
        assertTrue(Files.exists(Path.of(framePath)), "el archivo de la imagen debe existir en disco");
    }
}
