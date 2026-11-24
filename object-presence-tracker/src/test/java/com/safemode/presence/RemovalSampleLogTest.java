package com.safemode.presence;

import com.safemode.vision.ObjectDetector.Detection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El registro de muestras del modelo de retiro ({@link RemovalSampleLog}): escribe lo que ve el seguimiento sin cambiar ninguna
 * de sus decisiones, y un fallo del registro nunca frena el seguimiento. Todo con escenas sinteticas, sin camara.
 */
class RemovalSampleLogTest {

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
    @TempDir
    Path dataset;

    private final MutableClock clock = new MutableClock();
    private final PresenceEventStore store = newStore();

    private static PresenceEventStore newStore() {
        return PresenceEventStore.inMemory("samplelog_" + UUID.randomUUID().toString().replace("-", ""));
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

    private RemovalSampleLog log(String label) {
        return RemovalSampleLog.open(dataset, "A", "s1", label, "prueba");
    }

    private ObjectTracker tracker(PresenceEventStore forStore, RemovalSampleLog log, boolean verify) {
        ObjectTracker tracker = new ObjectTracker(forStore, clock, frames);
        if (verify) {
            tracker.withRemovalVerification();
        }
        if (log != null) {
            tracker.withRemovalSampleLog(log);
        }
        return tracker;
    }

    /** La mochila aparece y se queda quieta 4 s: queda registrada en reposo. */
    private void settle(ObjectTracker tracker) {
        tracker.onFrame(scene(true), List.of(BAG));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(scene(true), List.of(BAG));
    }

    private void unseen(ObjectTracker tracker, int count, BufferedImage frame) {
        for (int i = 0; i < count; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(frame, List.of());
        }
    }

    private void seenAgain(ObjectTracker tracker) {
        clock.advance(Duration.ofMillis(500));
        tracker.onFrame(scene(true), List.of(BAG));
    }

    private List<Map<String, String>> table(String file) throws IOException {
        List<String> lines = Files.readAllLines(dataset.resolve(file), StandardCharsets.UTF_8);
        String[] header = lines.get(0).split(",", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            assertEquals(header.length, cells.length, "fila con columnas de mas o de menos: " + line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) {
                row.put(header[i], cells[i]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> column(List<Map<String, String>> rows, String name) {
        return rows.stream().map(r -> r.get(name)).toList();
    }

    @Test
    void unEventoQueTerminaCuandoElDetectorVuelveAVerElObjetoQuedaComoSeenAgain() throws IOException {
        RemovalSampleLog log = log("still");
        ObjectTracker tracker = tracker(store, log, true);

        settle(tracker);
        unseen(tracker, 2, scene(true));
        seenAgain(tracker);
        log.close();

        List<Map<String, String>> samples = table("samples.csv");
        assertEquals(List.of("1", "2"), column(samples, "frames_unseen"));
        assertEquals(List.of("WAIT", "WAIT"), column(samples, "rule_action"));
        assertEquals("0.000000", samples.get(0).get("x1_changed"), "la zona sigue igual");
        assertEquals("1.000000", samples.get(0).get("x2_visible"));
        assertEquals("200", samples.get(0).get("box_w"));
        assertEquals("0", samples.get(0).get("persons_in_frame"));
        assertEquals("s1_c1_o1_r1", samples.get(0).get("event_id"));

        List<Map<String, String>> events = table("events.csv");
        assertEquals(1, events.size());
        assertEquals("SEEN_AGAIN", events.get(0).get("outcome_rule"));
        assertEquals("still", events.get(0).get("label"));
        assertEquals("run", events.get(0).get("label_source"));
        assertEquals("2", events.get(0).get("frames_unseen_at_end"));
        assertEquals("", events.get(0).get("tail_end"));
        assertEquals(0, store.countEvents("REMOVED"));
    }

    @Test
    void unRetiroClaroSeDeclaraAlSegundoFrameYLaColaSigueHastaElTopeSinCambiarNada() throws IOException {
        RemovalSampleLog log = log("removed");
        ObjectTracker tracker = tracker(store, log, true);

        settle(tracker);
        unseen(tracker, ObjectTracker.UNSEEN_CAP_FRAMES, scene(false));
        log.close();

        assertEquals(1, store.countEvents("REMOVED"), "la cola solo anota: el retiro se declara una sola vez");
        List<Map<String, String>> samples = table("samples.csv");
        assertEquals(15, samples.size());
        assertEquals("WAIT", samples.get(0).get("rule_action"));
        assertEquals("REMOVE_EARLY", samples.get(1).get("rule_action"));
        assertEquals(List.of("TAIL"), column(samples.subList(2, 15), "rule_action").stream().distinct().toList());
        assertEquals("15", samples.get(14).get("frames_unseen"));
        assertEquals("1.000000", samples.get(0).get("x1_changed"), "la mochila ya no esta: cambio toda la zona");

        Map<String, String> event = table("events.csv").get(0);
        assertEquals("REMOVED", event.get("outcome_rule"));
        assertEquals("removed", event.get("label"));
        assertEquals("2", event.get("frames_unseen_at_end"), "la regla decidio en el frame 2");
        assertEquals("15", event.get("max_frames_unseen"), "pero la cola llego hasta el tope");
        assertEquals("CAP", event.get("tail_end"));
    }

    @Test
    void siElObjetoVuelveEnLaColaLaColaTerminaEnReseen() throws IOException {
        RemovalSampleLog log = log("still");
        ObjectTracker tracker = tracker(store, log, true);

        settle(tracker);
        unseen(tracker, 4, scene(false)); // la regla da el retiro en el frame 2; la cola anota el 3 y el 4
        seenAgain(tracker); // el detector vuelve a ver la mochila en su sitio
        log.close();

        Map<String, String> event = table("events.csv").get(0);
        assertEquals("REMOVED", event.get("outcome_rule"));
        assertEquals("4", event.get("max_frames_unseen"));
        assertEquals("RESEEN", event.get("tail_end"));
        assertEquals(4, table("samples.csv").size(), "el frame en que se ve otra vez no es un frame sin ver");
    }

    @Test
    void alCerrarSeAnotanLosEventosAbiertos() throws IOException {
        RemovalSampleLog withTail = log("removed");
        ObjectTracker tracker = tracker(store, withTail, true);
        settle(tracker);
        unseen(tracker, 3, scene(false));
        withTail.close();

        Map<String, String> tail = table("events.csv").get(0);
        assertEquals("REMOVED", tail.get("outcome_rule"));
        assertEquals("RUN_END", tail.get("tail_end"));
        assertEquals("3", tail.get("max_frames_unseen"));

        // sin decision de la regla: el evento queda como abierto y no cuenta (lo descarta el resumen)
        RemovalSampleLog undecided = RemovalSampleLog.open(dataset, "A", "s2", "still", "prueba");
        ObjectTracker other = tracker(newStore(), undecided, true);
        settle(other);
        unseen(other, 1, scene(true));
        undecided.close();

        Map<String, String> open = table("events.csv").get(1);
        assertEquals("OPEN_AT_END", open.get("outcome_rule"));
        assertEquals("1", open.get("frames_unseen_at_end"));
    }

    @Test
    void conLaZonaIgualElTopeDeLaReglaQuedaComoDropCap() throws IOException {
        RemovalSampleLog log = log("still");
        ObjectTracker tracker = tracker(store, log, true);

        settle(tracker);
        unseen(tracker, 15, scene(true));
        log.close();

        assertEquals(0, store.countEvents("REMOVED"));
        List<String> actions = column(table("samples.csv"), "rule_action");
        assertEquals(15, actions.size());
        assertEquals("WAIT", actions.get(0));
        assertEquals("WAIT", actions.get(1));
        assertEquals(List.of("STILL"), actions.subList(2, 14).stream().distinct().toList());
        assertEquals("DROP_CAP", actions.get(14));
        Map<String, String> event = table("events.csv").get(0);
        assertEquals("DROPPED_CAP", event.get("outcome_rule"));
        assertEquals("15", event.get("frames_unseen_at_end"));
        assertEquals("CAP", event.get("tail_end"));
    }

    @Test
    void enUnaCorridaRemovedUnEventoQueSeVioDeNuevoSeReetiquetaComoStill() throws IOException {
        RemovalSampleLog log = log("removed");
        ObjectTracker tracker = tracker(store, log, true);

        settle(tracker);
        unseen(tracker, 2, scene(true)); // el detector parpadea...
        seenAgain(tracker); // ...y la mochila seguia ahi
        unseen(tracker, 2, scene(false)); // ahora si se la llevan
        log.close();

        List<Map<String, String>> events = table("events.csv");
        assertEquals(2, events.size());
        Map<String, String> flicker = events.get(0);
        assertEquals("s1_c1_o1_r1", flicker.get("event_id"));
        assertEquals("SEEN_AGAIN", flicker.get("outcome_rule"));
        assertEquals("removed", flicker.get("run_label"));
        assertEquals("still", flicker.get("label"));
        assertEquals("auto_seen_again", flicker.get("label_source"));
        Map<String, String> removal = events.get(1);
        assertEquals("s1_c1_o1_r2", removal.get("event_id"));
        assertEquals("REMOVED", removal.get("outcome_rule"));
        assertEquals("removed", removal.get("label"));
        assertEquals("run", removal.get("label_source"));
    }

    @Test
    void elRegistroNoCambiaNingunaDecisionDelSeguimiento() throws IOException {
        // la misma historia con y sin registro: una mochila que parpadea, se queda tapada hasta el tope y otra que se retira
        PresenceEventStore plainStore = newStore();
        PresenceEventStore loggedStore = newStore();
        RemovalSampleLog log = log("still");

        for (PresenceEventStore s : List.of(plainStore, loggedStore)) {
            ObjectTracker tracker = tracker(s, s == loggedStore ? log : null, true);
            settle(tracker);
            unseen(tracker, 6, scene(true));
            seenAgain(tracker);
            unseen(tracker, 15, scene(true));
            // la mochila "vuelve" como objeto nuevo y se asienta otra vez
            tracker.onFrame(scene(true), List.of(BAG));
            clock.advance(Duration.ofSeconds(4));
            tracker.onFrame(scene(true), List.of(BAG));
            unseen(tracker, 3, scene(false));
        }
        log.close();

        assertEquals(plainStore.countEvents("REGISTERED_AT_REST"), loggedStore.countEvents("REGISTERED_AT_REST"));
        assertEquals(plainStore.countEvents("REMOVED"), loggedStore.countEvents("REMOVED"));
        assertEquals(plainStore.lastRemoval().kind(), loggedStore.lastRemoval().kind());
        assertTrue(plainStore.countEvents("REMOVED") >= 1, "la historia debia terminar en un retiro");
        assertFalse(table("samples.csv").isEmpty(), "y el registro si anoto frames");
    }

    @Test
    void unRegistroQueFallaNoFrenaAlSeguimiento() {
        Writer broken = new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("disco lleno (simulado)");
            }

            @Override
            public void flush() throws IOException {
                throw new IOException("disco lleno (simulado)");
            }

            @Override
            public void close() {
            }
        };
        RemovalSampleLog log = new RemovalSampleLog(dataset, "A", "s1", "still", "prueba", broken, broken, broken);
        ObjectTracker tracker = tracker(store, log, true);

        settle(tracker);
        unseen(tracker, 3, scene(false));
        unseen(tracker, 2, scene(false)); // despues de desactivarse sigue sin molestar

        assertEquals(1, store.countEvents("REGISTERED_AT_REST"));
        assertEquals(1, store.countEvents("REMOVED"), "el retiro se declara igual que sin registro");
        assertTrue(log.isDisabled());
        log.close();
    }

    @Test
    void sinVerificacionLasFilasSalenSinSenalesPeroElSeguimientoDecideComoSiempre() throws IOException {
        RemovalSampleLog log = log("removed");
        ObjectTracker tracker = tracker(store, log, false);

        settle(tracker);
        unseen(tracker, 3, scene(true));
        log.close();

        assertEquals(1, store.countEvents("REMOVED"), "sin verificacion un objeto no visto se retira a los 3 frames");
        List<Map<String, String>> samples = table("samples.csv");
        assertEquals(3, samples.size());
        assertEquals("", samples.get(0).get("x1_changed"));
        assertEquals("", samples.get(2).get("x4_mean_abs_diff"));
        assertEquals("", samples.get(2).get("x5_frame_changed"));
        assertEquals("REMOVE", samples.get(2).get("rule_action"));
        assertFalse(log.isDisabled());
    }

    @Test
    void sinImagenElRegistroNoFallaYLasFilasSalenSinSenales() throws IOException {
        RemovalSampleLog log = log("removed");
        ObjectTracker tracker = tracker(store, log, true);

        tracker.onFrame(null, List.of(BAG));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(BAG));
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of());
        }
        log.close();

        assertEquals(1, store.countEvents("REMOVED"));
        assertEquals(List.of("", "", ""), column(table("samples.csv"), "x1_changed"));
        assertEquals(List.of("", "", ""), column(table("samples.csv"), "x5_frame_changed"), "sin imagen tampoco hay cambio de cuadro");
        assertFalse(log.isDisabled());
    }

    /** Ejecuta la accion capturando la consola (para comprobar los avisos) y devuelve lo que se imprimio. */
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

    @Test
    void unRetiroDejaElRestoDelCuadroIgualYUnaVentanaQueCambiaLoCambiaTodo() throws IOException {
        // 1) se llevan la mochila: cambia solo su zona
        RemovalSampleLog removal = RemovalSampleLog.open(dataset, "A", "retiro", "removed", "prueba");
        ObjectTracker removalTracker = tracker(store, removal, true);
        settle(removalTracker);
        String removalConsole = captureConsole(() -> unseen(removalTracker, 2, scene(false)));
        removal.close();

        // 2) la mochila sigue ahi pero cambia lo que hay en toda la pantalla (como cuando una ventana se mueve o se tapa)
        RemovalSampleLog window = RemovalSampleLog.open(dataset, "A", "ventana", "still", "prueba");
        ObjectTracker windowTracker = tracker(newStore(), window, true);
        settle(windowTracker);
        BufferedImage otherWindow = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = otherWindow.createGraphics();
        // gris medio: a mas de 45 niveles de brillo tanto de la mochila (66) como del fondo blanco (255)
        g.setColor(new Color(160, 160, 160));
        g.fillRect(0, 0, 800, 600);
        g.dispose();
        String windowConsole = captureConsole(() -> unseen(windowTracker, 2, otherWindow));
        window.close();

        List<Map<String, String>> rows = table("samples.csv");
        Map<String, String> removalRow = rows.stream().filter(r -> r.get("session").equals("retiro")).findFirst().orElseThrow();
        Map<String, String> windowRow = rows.stream().filter(r -> r.get("session").equals("ventana")).findFirst().orElseThrow();
        assertEquals("1.000000", removalRow.get("x1_changed"));
        assertEquals("0.000000", removalRow.get("x5_frame_changed"), "al retirar la mochila solo cambia su zona");
        assertEquals("1.000000", windowRow.get("x1_changed"), "la zona de la mochila tambien cambio...");
        assertEquals("1.000000", windowRow.get("x5_frame_changed"), "...pero aqui cambio todo lo demas: no es un retiro");
        assertFalse(removalConsole.contains("AVISO"), removalConsole);
        assertTrue(windowConsole.contains("AVISO: cambio TODO el cuadro"), windowConsole);
    }

    @Test
    void unaPersonaQueCruzaNoCuentaComoCambioDelCuadro() throws IOException {
        RemovalSampleLog log = log("still");
        ObjectTracker tracker = tracker(store, log, true);
        settle(tracker);
        // una persona (caja x 400-700) pinta de gris esa parte del cuadro, lejos de la mochila (x 100-300)
        BufferedImage frame = scene(true);
        Graphics2D g = frame.createGraphics();
        g.setColor(new Color(120, 120, 120));
        g.fillRect(400, 0, 300, 600);
        g.dispose();
        Detection person = new Detection("person", 0.9f, 400, 0, 300, 600);

        for (int i = 0; i < 2; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(frame, List.of(person));
        }
        log.close();

        Map<String, String> row = table("samples.csv").get(0);
        assertEquals("1", row.get("persons_in_frame"));
        assertEquals("0.000000", row.get("x1_changed"), "la zona de la mochila sigue igual");
        assertEquals("0.000000", row.get("x5_frame_changed"), "lo que cubre la persona no se compara");
    }

    @Test
    void guardaRecortesChicosDeLaZona() throws IOException {
        RemovalSampleLog log = log("removed");
        ObjectTracker tracker = tracker(store, log, true);

        settle(tracker);
        unseen(tracker, 3, scene(false));
        log.close();

        Path crops = dataset.resolve("crops");
        for (String suffix : List.of("rest", "2", "3", "last")) {
            assertTrue(Files.exists(crops.resolve("s1_c1_o1_r1_" + suffix + ".png")), "falta el recorte " + suffix);
        }
        BufferedImage rest = ImageIO.read(crops.resolve("s1_c1_o1_r1_rest.png").toFile());
        BufferedImage last = ImageIO.read(crops.resolve("s1_c1_o1_r1_last.png").toFile());
        assertEquals(200, rest.getWidth());
        assertEquals(160, rest.getHeight());
        assertEquals(new Color(90, 60, 40).getRGB(), rest.getRGB(100, 80), "en reposo se veia la mochila");
        assertEquals(Color.WHITE.getRGB(), last.getRGB(100, 80), "al final la zona esta vacia");
    }

    @Test
    void elRecorteNuncaPasaDelLadoMaximo() {
        BufferedImage frame = scene(true);

        BufferedImage big = RemovalSampleLog.cropScaled(frame, 0, 0, 500, 400);
        assertEquals(320, big.getWidth());
        assertEquals(256, big.getHeight());
        BufferedImage clipped = RemovalSampleLog.cropScaled(frame, 700, 500, 500, 500);
        assertEquals(100, clipped.getWidth());
        assertEquals(100, clipped.getHeight());
        assertNull(RemovalSampleLog.cropScaled(frame, 900, 900, 50, 50));
        assertNull(RemovalSampleLog.cropScaled(null, 0, 0, 50, 50));
    }

    @Test
    void unRetiroPorDesplazamientoSeCuentaAparteYNoEntraAlDataset() throws IOException {
        RemovalSampleLog log = log("removed");
        ObjectTracker tracker = tracker(store, log, true);

        settle(tracker);
        Detection moved = new Detection("backpack", 0.9f, 200, 100, 200, 160); // mas de 25 px a la derecha
        for (int i = 0; i < 2; i++) { // el retiro por desplazamiento exige 2 frames seguidos
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(scene(true), List.of(moved));
        }
        log.close();

        assertEquals(1, store.countEvents("REMOVED"));
        List<Map<String, String>> discarded = table("discarded.csv");
        assertEquals(1, discarded.size());
        assertEquals("DISPLACED_REMOVAL", discarded.get(0).get("reason"));
        assertEquals(0, table("samples.csv").size());
        assertEquals(0, table("events.csv").size());
    }

    @Test
    void elTextoLibreDelEscenarioSeEscapaEnElCsv() throws IOException {
        assertEquals("sin comas", RemovalSampleLog.csv("sin comas"));
        assertEquals("\"con, coma y \"\"comillas\"\"\"", RemovalSampleLog.csv("con, coma y \"comillas\""));
        assertEquals("", RemovalSampleLog.csv(null));

        RemovalSampleLog log = RemovalSampleLog.open(dataset, "A", "s1", "still", "persona, delante");
        ObjectTracker tracker = tracker(store, log, true);
        settle(tracker);
        unseen(tracker, 1, scene(true));
        log.close();

        String line = Files.readAllLines(dataset.resolve("samples.csv"), StandardCharsets.UTF_8).get(1);
        assertTrue(line.contains(",\"persona, delante\","), line);
    }

    @Test
    void fromEnvironmentSoloSeActivaConLaVariableYExigeLoNecesario() throws IOException {
        assertNull(RemovalSampleLog.fromEnvironment(Map.of(), dataset), "sin la variable todo sigue como siempre");
        assertNull(RemovalSampleLog.fromEnvironment(Map.of("SAFEMODE_REMOVAL_LOG", "0", "SAFEMODE_SCENE", "A",
                "SAFEMODE_LABEL", "still"), dataset));

        IllegalArgumentException noScene = assertThrows(IllegalArgumentException.class, () ->
                RemovalSampleLog.fromEnvironment(Map.of("SAFEMODE_REMOVAL_LOG", "1", "SAFEMODE_LABEL", "still"), dataset));
        assertTrue(noScene.getMessage().contains("SAFEMODE_SCENE"), noScene.getMessage());
        IllegalArgumentException noLabel = assertThrows(IllegalArgumentException.class, () ->
                RemovalSampleLog.fromEnvironment(Map.of("SAFEMODE_REMOVAL_LOG", "1", "SAFEMODE_SCENE", "A"), dataset));
        assertTrue(noLabel.getMessage().contains("SAFEMODE_LABEL"), noLabel.getMessage());
        assertThrows(IllegalArgumentException.class, () -> RemovalSampleLog.fromEnvironment(
                Map.of("SAFEMODE_REMOVAL_LOG", "1", "SAFEMODE_SCENE", "A", "SAFEMODE_LABEL", "tal vez"), dataset));
        assertThrows(IllegalArgumentException.class, () -> RemovalSampleLog.fromEnvironment(
                Map.of("SAFEMODE_REMOVAL_LOG", "1", "SAFEMODE_SCENE", "A B", "SAFEMODE_LABEL", "still"), dataset));

        RemovalSampleLog ok = RemovalSampleLog.fromEnvironment(Map.of("SAFEMODE_REMOVAL_LOG", "1", "SAFEMODE_SCENE", "B",
                "SAFEMODE_LABEL", "REMOVED", "SAFEMODE_SCENARIO", "removed_limpio"), dataset);
        assertNotNull(ok);
        assertTrue(ok.describe().contains("escena=B"), ok.describe());
        assertTrue(ok.describe().contains("etiqueta=removed"), "la etiqueta se normaliza a minusculas");
        ok.close();
        for (String file : List.of("samples.csv", "events.csv", "discarded.csv")) {
            assertEquals(1, Files.readAllLines(dataset.resolve(file)).size(), file + " debe tener solo el encabezado");
        }
        assertEquals(RemovalSampleLog.SAMPLES_HEADER, Files.readAllLines(dataset.resolve("samples.csv")).get(0));
    }

    @Test
    void noSePuedeRepetirUnaSesionQueYaTieneDatos() {
        RemovalSampleLog first = log("still");
        ObjectTracker tracker = tracker(store, first, true);
        settle(tracker);
        unseen(tracker, 1, scene(true));
        first.close();

        IllegalArgumentException repeated = assertThrows(IllegalArgumentException.class, () -> log("still"));
        assertTrue(repeated.getMessage().contains("ya tiene datos"), repeated.getMessage());
        // otra sesion en la misma carpeta si se puede (se agrega al final, sin tocar lo anterior)
        RemovalSampleLog second = RemovalSampleLog.open(dataset, "A", "s2", "still", "prueba");
        second.close();
    }
}
