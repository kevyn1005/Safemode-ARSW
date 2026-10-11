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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quien entra justo despues de que otra persona sale de cuadro no debe heredar su id: si el dueno se va y un desconocido
 * se lleva el objeto, tiene que salir como retiro por otra persona y no como retiro del dueno.
 */
class PersonIdentityTest {

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
    private static final Color WHITE_SHIRT = new Color(245, 245, 245);
    private static final Detection BAG = new Detection("backpack", 0.9f, 100, 300, 80, 60);
    private static final Detection PERSON = new Detection("person", 0.9f, 180, 100, 200, 400);

    @TempDir
    Path frames;

    private final MutableClock clock = new MutableClock();
    private final PresenceEventStore store = PresenceEventStore.inMemory("ident_" + UUID.randomUUID().toString().replace("-", ""));
    private ObjectTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ObjectTracker(store, clock, frames);
    }

    /** Escena de 1000x600: fondo claro y una persona (caja de PERSON) vestida del color dado, o nadie. */
    private static BufferedImage scene(Color clothes) {
        BufferedImage image = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 210, 215));
        g.fillRect(0, 0, 1000, 600);
        if (clothes != null) {
            g.setColor(clothes);
            g.fillRect(PERSON.x(), PERSON.y(), PERSON.width(), PERSON.height());
        }
        g.dispose();
        return image;
    }

    private void frame(BufferedImage image, Detection... detections) {
        clock.advance(Duration.ofMillis(500));
        tracker.onFrame(image, List.of(detections));
    }

    /** El dueno (id 1, sudadera beige) deja la mochila y esta junto a ella cuando se registra en reposo. */
    private void ownerLeavesTheBag() {
        tracker.onFrame(scene(BEIGE), List.of(BAG, PERSON));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(scene(BEIGE), List.of(BAG, PERSON));
        PresenceEventStore.OwnerInfo owner = store.lastOwner("REGISTERED_AT_REST");
        assertNotNull(owner, "la persona pegada a la mochila es su dueno");
        assertEquals(1L, owner.personId());
    }

    @Test
    void quienEntraConOtraRopaDespuesDeQueElDuenoSalgaNoHeredaSuId() {
        ownerLeavesTheBag();

        frame(scene(null));                       // el dueno sale de cuadro
        frame(scene(WHITE_SHIRT), PERSON);        // entra otra persona, con camisa blanca, en el mismo sitio
        frame(scene(WHITE_SHIRT), PERSON);        // y se lleva la mochila: el detector deja de verla
        frame(scene(WHITE_SHIRT), PERSON);
        frame(scene(WHITE_SHIRT), PERSON);

        PresenceEventStore.RemovalInfo removal = store.lastRemoval();
        assertNotNull(removal);
        assertEquals("BY_OTHER", removal.kind(), "quien se la llevo no es el dueno aunque haya entrado en su sitio");
        assertEquals(2L, removal.personId());
    }

    @Test
    void elDuenoQueVuelveTrasUnFrameSinVerseConservaSuId() {
        ownerLeavesTheBag();

        frame(scene(null));                       // el detector lo pierde un frame
        frame(scene(BEIGE), PERSON);              // vuelve con la misma sudadera
        frame(scene(BEIGE), PERSON);              // y se lleva su mochila
        frame(scene(BEIGE), PERSON);
        frame(scene(BEIGE), PERSON);

        PresenceEventStore.RemovalInfo removal = store.lastRemoval();
        assertNotNull(removal);
        assertEquals("BY_OWNER", removal.kind());
        assertEquals(1L, removal.personId());
    }

    @Test
    void sinImagenElSeguimientoDePersonasNoCambia() {
        ObjectTracker noImage = new ObjectTracker(store, clock, frames);
        noImage.onFrame(null, List.of(BAG, PERSON));
        clock.advance(Duration.ofSeconds(4));
        noImage.onFrame(null, List.of(BAG, PERSON));
        clock.advance(Duration.ofMillis(500));
        noImage.onFrame(null, List.of());
        clock.advance(Duration.ofMillis(500));
        noImage.onFrame(null, List.of(PERSON));

        assertEquals(1L, store.lastOwner("REGISTERED_AT_REST").personId());
    }

    /** Lo que el tracker imprime mientras se ejecuta el codigo dado: el log de sus decisiones. */
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

    @Test
    void elLogExplicaPorQueQuienEntraConOtraRopaRecibeUnIdNuevo() {
        String log = captureLog(() -> {
            ownerLeavesTheBag();
            frame(scene(null));
            frame(scene(WHITE_SHIRT), PERSON);
        });

        assertTrue(log.contains("Persona #2 nueva (descarto a #1 por la ropa (color a 120 de distancia, maximo 80), llevaba 1 frame(s) sin verse)"), log);
    }

    @Test
    void elLogDiceCuandoUnaPersonaVuelveConLaMismaRopaYConservaSuId() {
        String log = captureLog(() -> {
            ownerLeavesTheBag();
            frame(scene(null));
            frame(scene(BEIGE), PERSON);
        });

        assertTrue(log.contains("Persona #1 vuelve a verse tras 1 frame(s) y conserva su id (color a 0 de distancia, maximo 80)"), log);
    }

    @Test
    void elLogAnotaCuandoSeOlvidaAUnaPersona() {
        String log = captureLog(() -> {
            ownerLeavesTheBag();
            for (int i = 0; i < 6; i++) {
                frame(scene(null));
            }
        });

        assertTrue(log.contains("Persona #1 olvidada tras 6 frames sin verla: si vuelve sera otra persona"), log);
    }

    @Test
    void conDuenoElLogDiceEnCuantosFramesEstuvoJuntoAlObjeto() {
        String log = captureLog(this::ownerLeavesTheBag);

        assertTrue(log.contains("Objeto #1: dueno = persona #1 (junto al objeto en 2 de 2 frames mientras se asentaba)"), log);
    }

    @Test
    void sinDuenoElLogDiceCuantoDistabaLaPersonaMasCercana() {
        Detection farBag = new Detection("backpack", 0.9f, 700, 300, 80, 60);
        String log = captureLog(() -> {
            tracker.onFrame(scene(BEIGE), List.of(farBag, PERSON));
            clock.advance(Duration.ofSeconds(4));
            tracker.onFrame(scene(BEIGE), List.of(farBag, PERSON));
        });

        assertTrue(log.contains("Objeto #1 queda SIN dueno: la persona mas cercana estuvo a 360 px del objeto y \"junto al objeto\" es hasta 100 px (2 frames)"), log);
        assertNull(store.lastOwner("REGISTERED_AT_REST"));
    }

    @Test
    void sinNingunaPersonaElLogDiceQueNoSeVioANadie() {
        String log = captureLog(() -> {
            tracker.onFrame(scene(null), List.of(BAG));
            clock.advance(Duration.ofSeconds(4));
            tracker.onFrame(scene(null), List.of(BAG));
        });

        assertTrue(log.contains("Objeto #1 queda SIN dueno: no se vio a ninguna persona mientras se asentaba (2 frames)"), log);
    }

    @Test
    void elLogDelRetiroDiceDeDondeSalioCadaPersonaCercana() {
        String log = captureLog(() -> {
            ownerLeavesTheBag();
            frame(scene(null));
            frame(scene(WHITE_SHIRT), PERSON);
            frame(scene(WHITE_SHIRT), PERSON);
            frame(scene(WHITE_SHIRT), PERSON);
        });

        assertTrue(log.contains("Retiro del objeto #1: dueno=#1; junto al objeto: en el ultimo frame quieto"), log);
        assertTrue(log.contains("=> BY_OTHER"), log);
    }

    @Test
    void laFirmaSeparaRopaDistintaYAgrupaLaMisma() {
        PersonAppearance beige = PersonAppearance.of(scene(BEIGE), PERSON.x(), PERSON.y(), PERSON.width(), PERSON.height());
        PersonAppearance beigeAgain = PersonAppearance.of(scene(BEIGE), PERSON.x(), PERSON.y(), PERSON.width(), PERSON.height());
        PersonAppearance white = PersonAppearance.of(scene(WHITE_SHIRT), PERSON.x(), PERSON.y(), PERSON.width(), PERSON.height());

        assertNotNull(beige);
        assertEquals(true, beige.looksLike(beigeAgain));
        assertEquals(false, beige.looksLike(white));
        assertEquals(null, PersonAppearance.of(null, 0, 0, 100, 100));
    }
}
