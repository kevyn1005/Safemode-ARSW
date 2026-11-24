package com.safemode.presence;

import com.safemode.vision.ObjectDetector.Detection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El modelo de verificacion de retiro dentro del {@link ObjectTracker}: OFF es como siempre, SHADOW solo muestra al modelo (no cambia
 * ninguna alerta) y ACTIVE deja que decida el modelo. Los clasificadores de estas pruebas se arman a mano (no son el modelo entrenado).
 */
class RemovalModelModeTest {

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
    // una persona (caja x 90-490) que tapa por completo la zona de la mochila (x 100-300)
    private static final Detection PERSON = new Detection("person", 0.9f, 90, 0, 400, 600);

    @TempDir
    Path frames;
    @TempDir
    Path dataset;

    private final MutableClock clock = new MutableClock();

    private static PresenceEventStore newStore() {
        return PresenceEventStore.inMemory("modelmode_" + UUID.randomUUID().toString().replace("-", ""));
    }

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

    /** La escena con la persona delante: tapa la zona de la mochila (con o sin mochila detras). */
    private static BufferedImage covered(boolean bagBehind) {
        BufferedImage image = scene(bagBehind);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(235, 235, 235));
        g.fillRect(PERSON.x(), 0, PERSON.width(), 600);
        g.dispose();
        return image;
    }

    /** p = sigmoide(8 * x1 - 4): 0.018 con la zona igual, 0.98 con la zona cambiada; umbral 0.5. */
    private static RemovalClassifier byZoneChange() {
        return RemovalClassifier.logistic(List.of("x1_changed"), new double[]{0}, new double[]{1}, new double[]{8}, -4, 0.5);
    }

    private ObjectTracker tracker(PresenceEventStore store, RemovalClassifier classifier, RemovalModelMode mode) {
        ObjectTracker tracker = new ObjectTracker(store, clock, frames).withRemovalVerification();
        if (classifier != null) {
            tracker.withRemovalClassifier(classifier, mode);
        }
        return tracker;
    }

    private void settle(ObjectTracker tracker) {
        tracker.onFrame(scene(true), List.of(BAG));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(scene(true), List.of(BAG));
    }

    private void frame(ObjectTracker tracker, BufferedImage image, List<Detection> detections) {
        clock.advance(Duration.ofMillis(500));
        tracker.onFrame(image, detections);
    }

    private void unseen(ObjectTracker tracker, int count, BufferedImage image) {
        for (int i = 0; i < count; i++) {
            frame(tracker, image, List.of());
        }
    }

    private void covering(ObjectTracker tracker, int count, boolean bagBehind) {
        for (int i = 0; i < count; i++) {
            frame(tracker, covered(bagBehind), List.of(PERSON));
        }
    }

    private static String captureConsole(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------ OFF

    @Test
    void offEsExactamenteComoAntes() {
        PresenceEventStore plain = newStore();
        PresenceEventStore off = newStore();
        for (PresenceEventStore store : List.of(plain, off)) {
            ObjectTracker tracker = tracker(store, store == off ? byZoneChange() : null, RemovalModelMode.OFF);
            settle(tracker);
            covering(tracker, 3, true); // la regla de siempre: tapado casi todo -> no se puede comprobar -> retiro
        }

        assertEquals(1, plain.countEvents("REMOVED"));
        assertEquals(plain.countEvents("REMOVED"), off.countEvents("REMOVED"));
        assertEquals(plain.lastRemoval().kind(), off.lastRemoval().kind());
    }

    @Test
    void unModoQueNoEsOffExigeUnClasificador() {
        ObjectTracker tracker = new ObjectTracker(newStore(), clock, frames);

        assertThrows(IllegalArgumentException.class, () -> tracker.withRemovalClassifier(null, RemovalModelMode.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> tracker.withRemovalClassifier(null, RemovalModelMode.SHADOW));
        tracker.withRemovalClassifier(null, RemovalModelMode.OFF); // esto si: no hay nada que activar
    }

    // ------------------------------------------------------------------------------------------------ SHADOW

    @Test
    void shadowMuestraAlModeloPeroNoCambiaNingunaDecision() {
        PresenceEventStore plain = newStore();
        PresenceEventStore shadow = newStore();
        StringBuilder console = new StringBuilder();
        for (PresenceEventStore store : List.of(plain, shadow)) {
            ObjectTracker tracker = tracker(store, store == shadow ? byZoneChange() : null, RemovalModelMode.SHADOW);
            console.append(captureConsole(() -> {
                settle(tracker);
                covering(tracker, 3, true); // la regla da el retiro a los 3 frames; el modelo (p = 0.018) esperaria
            }));
        }

        assertEquals(1, plain.countEvents("REMOVED"));
        assertEquals(plain.countEvents("REMOVED"), shadow.countEvents("REMOVED"), "en sombra decide la regla, igual que sin modelo");
        assertEquals(plain.lastRemoval().kind(), shadow.lastRemoval().kind());
        String text = console.toString();
        assertTrue(text.contains("[MODELO] #1 k=1 p=0.018 -> espera"), text);
        assertTrue(text.contains("| regla: REMOVE"), "junto a lo que decidio la regla: " + text);
    }

    @Test
    void shadowSinImagenNoImprimeNiCambiaNada() {
        PresenceEventStore store = newStore();
        ObjectTracker tracker = new ObjectTracker(store, clock).withRemovalVerification()
                .withRemovalClassifier(byZoneChange(), RemovalModelMode.SHADOW);

        String console = captureConsole(() -> {
            tracker.onFrame(null, List.of(BAG));
            clock.advance(Duration.ofSeconds(4));
            tracker.onFrame(null, List.of(BAG));
            for (int i = 0; i < 3; i++) {
                clock.advance(Duration.ofMillis(500));
                tracker.onFrame(null, List.of());
            }
        });

        assertEquals(1, store.countEvents("REMOVED"), "sin imagen decide la regla de siempre");
        assertFalse(console.contains("[MODELO]"), console);
    }

    @Test
    void elRegistroDeMuestrasAnotaLaProbabilidadDelModeloTambienEnLaCola() throws IOException {
        RemovalSampleLog log = RemovalSampleLog.open(dataset, "A", "s1", "removed", "prueba").withClassifier(byZoneChange());
        ObjectTracker tracker = new ObjectTracker(newStore(), clock, frames).withRemovalVerification().withRemovalSampleLog(log);

        settle(tracker);
        unseen(tracker, 4, scene(false)); // la regla da el retiro en el frame 2; la cola anota el 3 y el 4
        log.close();

        List<String> lines = Files.readAllLines(dataset.resolve("samples.csv"), StandardCharsets.UTF_8);
        String[] header = lines.get(0).split(",", -1);
        int p = List.of(header).indexOf("model_p");
        int action = List.of(header).indexOf("model_action");
        List<String> actions = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            assertEquals("0.982014", cells[p], "x1 = 1 -> sigmoide(4)");
            actions.add(cells[action]);
        }
        assertEquals(List.of("WAIT", "REMOVE", "AFTER", "AFTER"), actions,
                "k=1 solo se anota; en k=2 el modelo supera el umbral por primera vez; despues, AFTER");
    }

    @Test
    void sinModeloLasColumnasDelModeloQuedanVacias() throws IOException {
        RemovalSampleLog log = RemovalSampleLog.open(dataset, "A", "s1", "removed", "prueba");
        ObjectTracker tracker = new ObjectTracker(newStore(), clock, frames).withRemovalVerification().withRemovalSampleLog(log);

        settle(tracker);
        unseen(tracker, 2, scene(false));
        log.close();

        String[] header = Files.readAllLines(dataset.resolve("samples.csv")).get(0).split(",", -1);
        String[] row = Files.readAllLines(dataset.resolve("samples.csv")).get(1).split(",", -1);
        assertEquals("", row[List.of(header).indexOf("model_p")]);
        assertEquals("", row[List.of(header).indexOf("model_action")]);
    }

    // ------------------------------------------------------------------------------------------------ ACTIVE

    @Test
    void activeDaElRetiroCuandoLaZonaCambioDesdeElSegundoFrame() {
        PresenceEventStore store = newStore();
        ObjectTracker tracker = tracker(store, byZoneChange(), RemovalModelMode.ACTIVE);

        settle(tracker);
        unseen(tracker, 1, scene(false));
        assertEquals(0, store.countEvents("REMOVED"), "el primer frame sin verlo solo se anota");
        unseen(tracker, 1, scene(false));

        assertEquals(1, store.countEvents("REMOVED"), "el modelo da el retiro en el frame 2");
    }

    @Test
    void activeEsperaMientrasUnaPersonaTapaLaZonaYDecideCuandoVuelveAVerse() {
        // la misma historia con la regla de siempre y con el modelo: alguien se para delante y despues se lleva la mochila
        PresenceEventStore classic = newStore();
        ObjectTracker classicTracker = tracker(classic, null, RemovalModelMode.OFF);
        settle(classicTracker);
        covering(classicTracker, 3, true);
        assertEquals(1, classic.countEvents("REMOVED"), "la regla da el retiro a los 3 frames aunque no puede ver la zona");

        PresenceEventStore model = newStore();
        ObjectTracker modelTracker = tracker(model, byZoneChange(), RemovalModelMode.ACTIVE);
        settle(modelTracker);
        covering(modelTracker, 6, true);
        assertEquals(0, model.countEvents("REMOVED"), "mientras la persona tapa la zona el modelo no decide");

        // la persona se va y la zona ya no tiene la mochila: ahora si se ve y el modelo da el retiro
        unseen(modelTracker, 1, scene(false));
        assertEquals(1, model.countEvents("REMOVED"));
    }

    @Test
    void activeNoDaRetiroSiAlIrseLaPersonaLaMochilaSigueAhi() {
        PresenceEventStore store = newStore();
        ObjectTracker tracker = tracker(store, byZoneChange(), RemovalModelMode.ACTIVE);

        settle(tracker);
        covering(tracker, 4, true); // la persona tapa, con la mochila detras
        unseen(tracker, 8, scene(true)); // se va: la zona se ve igual que en reposo aunque el detector siga sin verla

        assertEquals(0, store.countEvents("REMOVED"), "falsa alarma evitada: la mochila nunca se fue");
    }

    @Test
    void activeConservaElTopeDeFramesSinAlerta() {
        PresenceEventStore store = newStore();
        ObjectTracker tracker = tracker(store, byZoneChange(), RemovalModelMode.ACTIVE);

        settle(tracker);
        unseen(tracker, 15, scene(true));
        assertEquals(0, store.countEvents("REMOVED"), "pasado el tope con la zona igual el fallo es del detector");

        // ya no se sigue el objeto: aunque la zona cambie despues, no se declara un retiro
        unseen(tracker, 3, scene(false));
        assertEquals(0, store.countEvents("REMOVED"));
    }

    @Test
    void activeSinImagenDecideComoSiempre() {
        PresenceEventStore store = newStore();
        ObjectTracker tracker = new ObjectTracker(store, clock).withRemovalVerification()
                .withRemovalClassifier(byZoneChange(), RemovalModelMode.ACTIVE);

        tracker.onFrame(null, List.of(BAG));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(BAG));
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of());
        }

        assertEquals(1, store.countEvents("REMOVED"), "sin frame no se puede medir la zona: decide la regla de siempre");
    }

    @Test
    void laRachaDelModeloEmpiezaDeCeroCuandoElObjetoVuelveAVerse() {
        // p = sigmoide(1.5 * covered_frames - 4): da el retiro (p > 0.5) cuando la zona lleva 3 frames seguidos tapada
        RemovalClassifier byCoveredFrames = RemovalClassifier.logistic(List.of("covered_frames"), new double[]{0}, new double[]{1},
                new double[]{1.5}, -4, 0.5);
        PresenceEventStore store = newStore();
        ObjectTracker tracker = tracker(store, byCoveredFrames, RemovalModelMode.ACTIVE);

        settle(tracker);
        covering(tracker, 2, true); // tapada 2 frames
        frame(tracker, scene(true), List.of(BAG)); // el detector la vuelve a ver: la racha termina
        covering(tracker, 2, true); // tapada otros 2 frames: si no se reiniciara la cuenta ya van 4 seguidos
        assertEquals(0, store.countEvents("REMOVED"), "son dos rachas de 2 frames, no una de 4");

        covering(tracker, 1, true); // ahora si: 3 frames seguidos en la misma racha
        assertEquals(1, store.countEvents("REMOVED"));
    }
}
