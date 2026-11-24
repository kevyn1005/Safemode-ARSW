package com.safemode.presence;

import com.safemode.vision.ObjectDetector.Detection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemovalModelLoader}: el tracker NUNCA debe fallar por el modelo. Si falta, esta danado, es de juguete y se pidio active, o el modo
 * esta mal escrito, se sigue con la regla de siempre (modo off) y se avisa. Y con ese resultado el tracker decide exactamente como sin modelo.
 */
class RemovalModelLoaderTest {

    private static final String VALID_LOGISTIC = "{\"format\":1,\"kind\":\"logistic\",\"features\":[\"x1_changed\",\"x2_visible\"],"
            + "\"mean\":[0,0],\"std\":[1,1],\"threshold\":0.5,\"logistic\":{\"weights\":[4,-1],\"bias\":-2}}";

    @TempDir
    Path dir;
    @TempDir
    Path frames;

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String toyJson() throws IOException {
        try (InputStream in = RemovalModelLoaderTest.class.getResourceAsStream("/removal-model/toy-logistic.json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertFallsBackToTheRule(RemovalModelLoader.Loaded loaded, String noticePart) {
        assertNull(loaded.classifier());
        assertEquals(RemovalModelMode.OFF, loaded.mode());
        assertFalse(loaded.usesModel());
        assertNotNull(loaded.notice(), "tiene que avisar");
        assertTrue(loaded.notice().contains("regla de siempre"), loaded.notice());
        assertTrue(loaded.notice().contains(noticePart), "se esperaba \"" + noticePart + "\" en: " + loaded.notice());
    }

    // ------------------------------------------------------------------------------------------------ apagado y modo

    @Test
    void apagadoNoSeMiraNiElModelo() throws IOException {
        Path broken = write("roto.json", "esto no es json");
        for (String off : new String[]{null, "", "  ", "off", "OFF"}) {
            RemovalModelLoader.Loaded loaded = RemovalModelLoader.load(off, broken.toString());
            assertNull(loaded.classifier());
            assertEquals(RemovalModelMode.OFF, loaded.mode());
            assertNull(loaded.notice(), "apagado no avisa de nada");
        }
    }

    @Test
    void unModoMalEscritoNoHaceFallarAlTrackerSigueConLaRegla() throws IOException {
        Path valid = write("bueno.json", VALID_LOGISTIC);

        assertFallsBackToTheRule(RemovalModelLoader.load("sombra", valid.toString()), "off, shadow o active");
    }

    // ------------------------------------------------------------------------------------------------ falta o esta danado

    @Test
    void siFaltaElArchivoSigueLaRegla() {
        for (String mode : new String[]{"shadow", "active"}) {
            assertFallsBackToTheRule(RemovalModelLoader.load(mode, dir.resolve("no-existe.json").toString()), "No se pudo cargar el modelo");
        }
    }

    @Test
    void siLaRutaEsUnDirectorioSigueLaRegla() {
        assertFallsBackToTheRule(RemovalModelLoader.load("shadow", dir.toString()), "No se pudo cargar el modelo");
    }

    @Test
    void siLaRutaTieneCaracteresInvalidosSigueLaRegla() {
        assertFallsBackToTheRule(RemovalModelLoader.load("active", "modelo\0roto.json"), "No se pudo cargar el modelo");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "esto no es json",
            "[1, 2, 3]",
            "{}",
            "{\"format\":1,\"kind\":\"logistic\"}",
            "{\"format\":2,\"kind\":\"logistic\"}",
            "{\"format\":1,\"kind\":\"svm\",\"features\":[\"x1_changed\"],\"mean\":[0],\"std\":[1],\"threshold\":0.5}",
            "{\"format\":1,\"kind\":\"logistic\",\"features\":[\"x1_changed\"],\"mean\":[0],\"std\":[0],\"threshold\":0.5,"
                    + "\"logistic\":{\"weights\":[1],\"bias\":0}}",
            "{\"format\":1,\"kind\":\"logistic\",\"features\":[\"x1_changed\"],\"mean\":[0],\"std\":[1],\"threshold\":1.5,"
                    + "\"logistic\":{\"weights\":[1],\"bias\":0}}",
            "{\"format\":1,\"kind\":\"logistic\",\"features\":[\"color\"],\"mean\":[0],\"std\":[1],\"threshold\":0.5,"
                    + "\"logistic\":{\"weights\":[1],\"bias\":0}}",
            "{\"format\":1,\"kind\":\"logistic\",\"features\":[\"x1_changed\"],\"mean\":[0],\"std\":[1],\"threshold\":0.5,"
                    + "\"logistic\":{\"weights\":[NaN],\"bias\":0}}",
            "{\"format\":1,\"kind\":\"logistic\",\"features\":[\"x1_changed\"],\"mean\":[0],\"std\":[1],\"threshold\":0.5,"
                    + "\"logistic\":{\"weights\":[1,2],\"bias\":0}}",
            "{\"format\":1,\"kind\":\"mlp\",\"features\":[\"x1_changed\"],\"mean\":[0],\"std\":[1],\"threshold\":0.5,\"mlp\":{\"w1\":[]}}"
    })
    void siElJsonEstaDanadoOIncompletoSigueLaRegla(String content) throws IOException {
        Path file = write("danado.json", content);
        for (String mode : new String[]{"shadow", "active"}) {
            assertFallsBackToTheRule(RemovalModelLoader.load(mode, file.toString()), "No se pudo cargar el modelo");
        }
    }

    @Test
    void siFaltaElRecursoDelProyectoSigueLaRegla() {
        for (String mode : new String[]{"shadow", "active"}) {
            assertFallsBackToTheRule(RemovalModelLoader.load(mode, null, "/removal-model/no-existe.json"), "src/main/resources");
            assertFallsBackToTheRule(RemovalModelLoader.load(mode, "  ", "/removal-model/no-existe.json"), "src/main/resources");
        }
    }

    // ------------------------------------------------------------------------------------------------ carga buena

    @Test
    void unModeloValidoSeCargaEnElModoPedido() throws IOException {
        Path file = write("bueno.json", VALID_LOGISTIC);

        RemovalModelLoader.Loaded shadow = RemovalModelLoader.load("shadow", file.toString());
        assertEquals(RemovalModelMode.SHADOW, shadow.mode());
        assertEquals(List.of("x1_changed", "x2_visible"), shadow.classifier().features());
        assertTrue(shadow.usesModel());
        assertTrue(shadow.notice().contains("SHADOW"), shadow.notice());
        assertFalse(shadow.notice().contains("JUGUETE"), shadow.notice());

        RemovalModelLoader.Loaded active = RemovalModelLoader.load(" Active ", " " + file + " ");
        assertEquals(RemovalModelMode.ACTIVE, active.mode());
        assertNotNull(active.classifier());
    }

    @Test
    void elRecursoDelProyectoSeLeeDelClasspathSiNoHayVariableDeArchivo() {
        // el modelo REAL vive en src/main/resources/removal-model/model.json; aqui se prueba el camino con un recurso de prueba
        RemovalModelLoader.Loaded loaded = RemovalModelLoader.load("shadow", null, "/removal-model/toy-logistic.json");

        assertEquals(RemovalModelMode.SHADOW, loaded.mode());
        assertNotNull(loaded.classifier());
        assertTrue(loaded.notice().contains("el recurso /removal-model/toy-logistic.json"), loaded.notice());
    }

    @Test
    void elArchivoDeLaVariableTienePrioridadSobreElRecurso() throws IOException {
        Path file = write("otro.json", VALID_LOGISTIC);

        RemovalModelLoader.Loaded loaded = RemovalModelLoader.load("shadow", file.toString(), "/removal-model/toy-logistic.json");

        assertEquals(List.of("x1_changed", "x2_visible"), loaded.classifier().features()); // no el de toy-logistic (4 senales)
        assertFalse(loaded.classifier().isToy());
    }

    // ------------------------------------------------------------------------------------------------ juguete: nunca activo

    @Test
    void unModeloDeJugueteSePuedeVerEnSombraPeroNuncaDecide() throws IOException {
        Path toy = write("juguete.json", toyJson());

        RemovalModelLoader.Loaded shadow = RemovalModelLoader.load("shadow", toy.toString());
        assertEquals(RemovalModelMode.SHADOW, shadow.mode());
        assertTrue(shadow.classifier().isToy());
        assertTrue(shadow.notice().contains("JUGUETE"), shadow.notice());

        assertFallsBackToTheRule(RemovalModelLoader.load("active", toy.toString()), "JUGUETE");
        assertFallsBackToTheRule(RemovalModelLoader.load("active", null, "/removal-model/toy-logistic.json"), "JUGUETE");
    }

    @Test
    void elModeloRealDelProyectoSiExisteNoEsDeJuguete() {
        RemovalModelLoader.Loaded loaded = RemovalModelLoader.load("shadow", null);
        if (loaded.classifier() == null) {
            // todavia no hay modelo entrenado: el cargador cae a la regla con un aviso que dice como arreglarlo
            assertFallsBackToTheRule(loaded, "ml/export_model.py export");
        } else {
            assertFalse(loaded.classifier().isToy(), "src/main/resources/removal-model/model.json no puede ser de juguete");
        }
    }

    // ------------------------------------------------------------------------------------------------ frames_unseen: atajo aprendido

    @Test
    void unModeloFinalQueUsaFramesUnseenSeRechazaEnCualquierModo() throws IOException {
        // frames_unseen aprende el guion de las capturas (los still duran 2-6 s), no la zona del objeto: solo vale en la ablacion de ml/
        Path file = write("atajo.json", "{\"format\":1,\"kind\":\"logistic\",\"features\":[\"x1_changed\",\"frames_unseen\"],"
                + "\"mean\":[0,0],\"std\":[1,1],\"threshold\":0.5,\"logistic\":{\"weights\":[4,1],\"bias\":-2}}");

        for (String mode : new String[]{"shadow", "active"}) {
            assertFallsBackToTheRule(RemovalModelLoader.load(mode, file.toString()), "frames_unseen");
        }
    }

    @Test
    void elJugueteDeLas8SenalesQueLlevaFramesUnseenSigueVisibleEnSombra() {
        // el toy-mlp8 existe para medir el tamano maximo y la ablacion: es de juguete, asi que no decide y se puede ver
        RemovalModelLoader.Loaded loaded = RemovalModelLoader.load("shadow", null, "/removal-model/toy-mlp8.json");

        assertEquals(RemovalModelMode.SHADOW, loaded.mode());
        assertTrue(loaded.classifier().isToy());
        assertTrue(loaded.classifier().usesFeature("frames_unseen"));
        assertFallsBackToTheRule(RemovalModelLoader.load("active", null, "/removal-model/toy-mlp8.json"), "JUGUETE");
    }

    // ------------------------------------------------------------------------------------------------ el tracker sigue igual

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
    private static final Detection PERSON = new Detection("person", 0.9f, 90, 0, 400, 600);

    private static BufferedImage scene(boolean covered) {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 600);
        g.setColor(new Color(90, 60, 40));
        g.fillRect(BAG.x(), BAG.y(), BAG.width(), BAG.height());
        if (covered) {
            g.setColor(new Color(235, 235, 235));
            g.fillRect(PERSON.x(), 0, PERSON.width(), 600);
        }
        g.dispose();
        return image;
    }

    /** Asienta la mochila y la tapa una persona 3 frames: la regla de siempre dice "no se puede comprobar" y da el retiro. */
    private static void runScene(ObjectTracker tracker, MutableClock clock) {
        tracker.onFrame(scene(false), List.of(BAG));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(scene(false), List.of(BAG));
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(scene(true), List.of(PERSON));
        }
    }

    private PresenceEventStore trackerResult(RemovalModelLoader.Loaded loaded) {
        PresenceEventStore store = PresenceEventStore.inMemory("loader_" + UUID.randomUUID().toString().replace("-", ""));
        MutableClock clock = new MutableClock();
        ObjectTracker tracker = new ObjectTracker(store, clock, frames).withRemovalVerification();
        if (loaded != null) {
            tracker.withRemovalClassifier(loaded.classifier(), loaded.mode()); // exactamente como lo hace ObjectPresenceTrackerTest
        }
        runScene(tracker, clock);
        return store;
    }

    @Test
    void conUnModeloFaltanteODanadoElTrackerDecideExactamenteComoSinModelo() throws IOException {
        PresenceEventStore plain = trackerResult(null);
        PresenceEventStore missing = trackerResult(RemovalModelLoader.load("active", dir.resolve("no-existe.json").toString()));
        PresenceEventStore corrupt = trackerResult(RemovalModelLoader.load("active", write("roto.json", "{{{").toString()));

        assertEquals(1, plain.countEvents("REMOVED"));
        for (PresenceEventStore other : List.of(missing, corrupt)) {
            assertEquals(plain.countEvents("REGISTERED_AT_REST"), other.countEvents("REGISTERED_AT_REST"));
            assertEquals(plain.countEvents("REMOVED"), other.countEvents("REMOVED"));
            assertEquals(plain.lastRemoval().kind(), other.lastRemoval().kind());
        }
    }

    @Test
    void unModeloDeJugueteEnSombraNoCambiaNingunaDecisionYEnActiveTampoco() throws IOException {
        Path toy = write("juguete.json", toyJson());
        PresenceEventStore plain = trackerResult(null);
        PresenceEventStore shadow = trackerResult(RemovalModelLoader.load("shadow", toy.toString()));
        PresenceEventStore active = trackerResult(RemovalModelLoader.load("active", toy.toString())); // se rechaza: decide la regla

        for (PresenceEventStore other : List.of(shadow, active)) {
            assertEquals(plain.countEvents("REMOVED"), other.countEvents("REMOVED"));
            assertEquals(plain.lastRemoval().kind(), other.lastRemoval().kind());
        }
    }
}
