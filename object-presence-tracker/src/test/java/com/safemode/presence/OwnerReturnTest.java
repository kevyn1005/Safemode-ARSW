package com.safemode.presence;

import com.safemode.vision.ObjectDetector.Detection;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El dueno que sale de cuadro mas de unos frames se olvida: al volver recibe un id nuevo y su retiro sale como "de otra persona".
 * Si quien retira se viste parecido al dueno, el retiro queda marcado ("puede ser el dueno que volvio") para que la alerta no sea un
 * posible robo con la severidad maxima; si hay alguien distinto, o no se puede comparar la ropa, sigue siendo un posible robo.
 */
class OwnerReturnTest {

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

    private static final Color BEIGE = new Color(210, 180, 150);
    private static final Color SIMILAR = new Color(190, 165, 140);      // a ~27 de BEIGE
    private static final Color WHITE_SHIRT = new Color(245, 245, 245);  // a ~120 de BEIGE
    private static final Detection BAG = new Detection("backpack", 0.9f, 100, 300, 80, 60);
    private static final Detection PERSON = new Detection("person", 0.9f, 180, 100, 200, 400);
    private static final Detection OTHER = new Detection("person", 0.9f, 0, 100, 120, 400);   // tambien junto a la mochila

    @TempDir
    Path frames;

    private final MutableClock clock = new MutableClock();
    private final PresenceEventStore store = PresenceEventStore.inMemory("ret_" + UUID.randomUUID().toString().replace("-", ""));
    private ObjectTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ObjectTracker(store, clock, frames);
    }

    /** Escena de 1000x600 con fondo claro: PERSON vestida del primer color y OTHER del segundo (null = no hay nadie ahi). */
    private static BufferedImage scene(Color person, Color other) {
        BufferedImage image = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 210, 215));
        g.fillRect(0, 0, 1000, 600);
        if (person != null) {
            g.setColor(person);
            g.fillRect(PERSON.x(), PERSON.y(), PERSON.width(), PERSON.height());
        }
        if (other != null) {
            g.setColor(other);
            g.fillRect(OTHER.x(), OTHER.y(), OTHER.width(), OTHER.height());
        }
        g.dispose();
        return image;
    }

    private void frame(BufferedImage image, Detection... detections) {
        clock.advance(Duration.ofMillis(500));
        tracker.onFrame(image, List.of(detections));
    }

    /** El dueno (id 1, ropa beige) deja la mochila, se registra con el al lado y se va de cuadro mas tiempo del que se le recuerda. */
    private void ownerLeavesAndIsForgotten(BufferedImage emptyScene) {
        tracker.onFrame(emptyScene == null ? null : scene(BEIGE, null), List.of(BAG, PERSON));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(emptyScene == null ? null : scene(BEIGE, null), List.of(BAG, PERSON));
        PresenceEventStore.OwnerInfo owner = store.lastOwner("REGISTERED_AT_REST");
        assertNotNull(owner);
        assertEquals(1L, owner.personId());
        for (int i = 0; i < 7; i++) {
            frame(emptyScene, BAG);   // la mochila sigue ahi y el dueno no esta: despues de 6 frames se le olvida
        }
    }

    private PresenceEventStore.RemovalInfo someoneTakesTheBag(Color clothes) {
        frame(scene(clothes, null), BAG, PERSON);   // llega una persona (id nuevo) junto a la mochila
        frame(scene(clothes, null), PERSON);        // y se la lleva: el detector deja de verla
        frame(scene(clothes, null), PERSON);
        frame(scene(clothes, null), PERSON);
        PresenceEventStore.RemovalInfo removal = store.lastRemoval();
        assertNotNull(removal);
        assertEquals("BY_OTHER", removal.kind(), "el dueno ya fue olvidado: quien llega es otra persona para el seguimiento");
        return removal;
    }

    @Test
    void elDuenoQueVuelveConLaMismaRopaQuedaMarcadoComoPosibleDuenoQueVolvio() {
        ownerLeavesAndIsForgotten(scene(null, null));

        PresenceEventStore.RemovalInfo removal = someoneTakesTheBag(BEIGE);

        assertEquals(2L, removal.personId());
        assertTrue(removal.looksLikeOwner());
    }

    @Test
    void conRopaParecidaPeroNoIgualTambienSeParece() {
        ownerLeavesAndIsForgotten(scene(null, null));

        assertTrue(someoneTakesTheBag(SIMILAR).looksLikeOwner());
    }

    @Test
    void quienLlegaConOtraRopaSigueSiendoUnPosibleRobo() {
        ownerLeavesAndIsForgotten(scene(null, null));

        assertFalse(someoneTakesTheBag(WHITE_SHIRT).looksLikeOwner());
    }

    @Test
    void siHayOtraPersonaDistintaJuntoAlObjetoNoSeBajaLaAlerta() {
        ownerLeavesAndIsForgotten(scene(null, null));

        frame(scene(BEIGE, WHITE_SHIRT), BAG, PERSON, OTHER);   // llega alguien vestido como el dueno y otra persona distinta
        frame(scene(BEIGE, WHITE_SHIRT), PERSON, OTHER);        // y la mochila desaparece estando las dos junto a ella
        frame(scene(BEIGE, WHITE_SHIRT), PERSON, OTHER);
        frame(scene(BEIGE, WHITE_SHIRT), PERSON, OTHER);

        PresenceEventStore.RemovalInfo removal = store.lastRemoval();
        assertNotNull(removal);
        assertEquals("BY_OTHER", removal.kind());
        assertFalse(removal.looksLikeOwner(), "basta una persona distinta junto al objeto para no tratarlo como el dueno que volvio");
    }

    @Test
    void sinImagenNoSePuedeCompararLaRopaYSigueSiendoUnPosibleRobo() {
        ownerLeavesAndIsForgotten(null);

        frame(null, BAG, PERSON);
        frame(null, PERSON);
        frame(null, PERSON);
        frame(null, PERSON);

        PresenceEventStore.RemovalInfo removal = store.lastRemoval();
        assertNotNull(removal);
        assertEquals("BY_OTHER", removal.kind());
        assertFalse(removal.looksLikeOwner());
    }

    @Test
    void elDuenoQueSigueEnCuadroNoEsUnRetiroPorOtraPersona() {
        tracker.onFrame(scene(BEIGE, null), List.of(BAG, PERSON));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(scene(BEIGE, null), List.of(BAG, PERSON));
        frame(scene(BEIGE, null), PERSON);
        frame(scene(BEIGE, null), PERSON);
        frame(scene(BEIGE, null), PERSON);

        PresenceEventStore.RemovalInfo removal = store.lastRemoval();
        assertNotNull(removal);
        assertEquals("BY_OWNER", removal.kind());
        assertFalse(removal.looksLikeOwner());
    }

    @Test
    void elLogDiceConQuienSeComparoYQueResultado() {
        String log = captureLog(() -> {
            ownerLeavesAndIsForgotten(scene(null, null));
            someoneTakesTheBag(BEIGE);
        });

        assertTrue(log.contains("Retiro por otra persona, comparada con el dueno #1: persona #2 color a 0 (maximo 60) => "
                + "se parece: puede ser el dueno que volvio"), log);
    }

    @Test
    void elLogDiceQueNoSeParecioCuandoLaRopaEsDistinta() {
        String log = captureLog(() -> {
            ownerLeavesAndIsForgotten(scene(null, null));
            someoneTakesTheBag(WHITE_SHIRT);
        });

        assertTrue(log.contains("persona #2 color a 120 (maximo 60) => se ve distinta: posible robo"), log);
    }

    @Test
    void elLogExplicaPorQueUnaPersonaQueEntraRecibeUnIdNuevo() {
        Detection far = new Detection("person", 0.9f, 700, 100, 200, 400);
        String log = captureLog(() -> {
            tracker.onFrame(null, List.of(PERSON));
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of(far));
        });

        assertTrue(log.contains("Persona #2 nueva (el candidato mas cercano era #1: IoU 0.00, a 520 px de su centro con limite de 240 px, "
                + "0 frame(s) sin verse)"), log);
    }

    private static String captureLog(Runnable action) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
