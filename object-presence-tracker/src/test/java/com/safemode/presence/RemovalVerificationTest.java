package com.safemode.presence;

import com.safemode.vision.ObjectDetector.Detection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cubre dos correcciones nacidas de una prueba real con camara pegada al objeto: el radio de "cerca" que crece con el
 * tamano del objeto, y la verificacion por imagen para no dar por retirado un objeto que el detector dejo de ver un
 * momento pero cuya zona sigue igual.
 */
class RemovalVerificationTest {

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

    private static final Detection BAG = new Detection("backpack", 0.9f, 100, 100, 200, 160);

    @TempDir
    Path frames;

    private final MutableClock clock = new MutableClock();
    private final PresenceEventStore store = PresenceEventStore.inMemory("verif_" + UUID.randomUUID().toString().replace("-", ""));

    private static BufferedImage scene(boolean bagPresent) {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 600);
        if (bagPresent) {
            g.setColor(new Color(90, 60, 40));
            g.fillRect(BAG.x(), BAG.y(), BAG.width(), BAG.height());
        }
        g.dispose();
        return image;
    }

    private ObjectTracker registeredBag(boolean verify) {
        ObjectTracker tracker = new ObjectTracker(store, clock, frames);
        if (verify) {
            tracker.withRemovalVerification();
        }
        tracker.onFrame(scene(true), List.of(BAG));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(scene(true), List.of(BAG));
        assertEquals(1, store.countEvents("REGISTERED_AT_REST"));
        return tracker;
    }

    private void unseenFrames(ObjectTracker tracker, int count, BufferedImage frame) {
        for (int i = 0; i < count; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(frame, List.of());
        }
    }

    @Test
    void sinLaVerificacionUnObjetoNoVistoSeRetiraALos3Frames() {
        ObjectTracker tracker = registeredBag(false);

        unseenFrames(tracker, 3, scene(true));

        assertEquals(1, store.countEvents("REMOVED"));
    }

    @Test
    void conLaVerificacionSiLaZonaSigueIgualNoSeDaPorRetirado() {
        ObjectTracker tracker = registeredBag(true);

        unseenFrames(tracker, 6, scene(true));

        assertEquals(0, store.countEvents("REMOVED"), "la mochila sigue ahi aunque el detector no la vea");
    }

    @Test
    void conLaVerificacionSiLaZonaCambioSeRetiraALos3Frames() {
        ObjectTracker tracker = registeredBag(true);

        unseenFrames(tracker, 3, scene(false));

        assertEquals(1, store.countEvents("REMOVED"));
    }

    @Test
    void laEsperaTieneTopeYDespuesSeRetira() {
        ObjectTracker tracker = registeredBag(true);

        unseenFrames(tracker, 14, scene(true));
        assertEquals(0, store.countEvents("REMOVED"));

        unseenFrames(tracker, 1, scene(true));
        assertEquals(1, store.countEvents("REMOVED"), "pasado el tope de espera se decide como siempre");
    }

    @Test
    void siElObjetoVuelveAVerseSigueEnReposoYSePuedeRetirarDespues() {
        ObjectTracker tracker = registeredBag(true);

        unseenFrames(tracker, 6, scene(true));
        clock.advance(Duration.ofMillis(500));
        tracker.onFrame(scene(true), List.of(BAG));
        assertEquals(0, store.countEvents("REMOVED"));

        unseenFrames(tracker, 3, scene(false));
        assertEquals(1, store.countEvents("REMOVED"));
    }

    @Test
    void sinImagenSeDecideComoSiempre() {
        ObjectTracker tracker = new ObjectTracker(store, clock).withRemovalVerification();
        tracker.onFrame(null, List.of(BAG));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(BAG));

        unseenFrames(tracker, 3, null);

        assertEquals(1, store.countEvents("REMOVED"));
    }

    @Test
    void unaPersonaAlLadoDeUnObjetoGrandeSeConsideraDuena() {
        // camara pegada: mochila de 500x600 y una persona cuya caja empieza a 127 px del centro de la mochila
        Detection bigBag = new Detection("backpack", 0.9f, 300, 400, 500, 600);
        Detection person = new Detection("person", 0.9f, 677, 60, 600, 1000);
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(bigBag, person));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(bigBag, person));

        PresenceEventStore.OwnerInfo owner = store.lastOwner("REGISTERED_AT_REST");
        assertNotNull(owner, "a 127 px de un objeto de 600 px de alto la persona esta pegada a el");
    }

    @Test
    void unObjetoPequenoConservaElRadioDeSiempre() {
        Detection smallBag = new Detection("backpack", 0.9f, 300, 400, 80, 60);
        Detection person = new Detection("person", 0.9f, 477, 60, 100, 1000);
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(smallBag, person));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(smallBag, person));

        assertNull(store.lastOwner("REGISTERED_AT_REST"), "a 137 px de un objeto de 80 px la persona no esta junto a el");
    }

    @Test
    void elRadioEscaladoNuncaEsMenorQueElMinimo() {
        assertEquals(100, ObjectTracker.scaledProximity(100, 80, 60), 1e-9);
        assertEquals(0.4 * 606, ObjectTracker.scaledProximity(100, 514, 606), 1e-9);
    }
}
