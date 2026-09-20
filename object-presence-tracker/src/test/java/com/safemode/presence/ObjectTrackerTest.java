package com.safemode.presence;

import com.safemode.vision.ObjectDetector.Detection;
import com.safemode.vision.PoseEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void personaConCajaGrandeQueSostieneElObjetoCuentaComoCercaAunqueSuCentroEsteLejos() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("retiro-persona-caja-grande");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(suitcase(100, 100)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(suitcase(100, 100)));

        // persona sentada/cargando: caja de 150x600 que contiene a la maleta, con su centro a ~180px de ella
        Detection bigPerson = new Detection("person", 0.9f, 0, 0, 150, 600);
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of(bigPerson));
        }

        assertEquals(Boolean.TRUE, store.lastPersonNearby("REMOVED"),
                "la distancia debe medirse a la caja de la persona, no solo a su centro");
    }

    @Test
    void unaPersonaLejanaNoCuentaComoCercaAlRetirarElObjeto() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("retiro-persona-lejos");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(suitcase(100, 100)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(suitcase(100, 100)));

        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of(person(900, 500)));
        }

        assertEquals(Boolean.FALSE, store.lastPersonNearby("REMOVED"));
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
    void variosObjetosDeLaMismaClaseSeRegistranYSeRetiranPorSeparadoCadaUnoConSuDueno() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("dos-maletas-dos-duenos");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        // dos maletas y dos personas, cada una junto a su maleta (A -> id 1, B -> id 2)
        List<Detection> escena = List.of(
                suitcase(100, 100), suitcase(600, 300),
                person(60, 60), person(560, 260));

        tracker.onFrame(null, escena);
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, escena);

        assertEquals(2, store.countEvents("REGISTERED_AT_REST"), "cada maleta debe registrarse por separado");
        assertEquals(2L, store.lastOwner("REGISTERED_AT_REST").personId(),
                "la segunda maleta (ultimo registro) debe tener como dueno a la persona B");

        // se retira solo la primera maleta; la segunda sigue en su sitio
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of(suitcase(600, 300), person(560, 260)));
        }
        assertEquals(1, store.countEvents("REMOVED"), "solo debe retirarse la maleta que desaparecio");
        assertEquals(1L, store.lastOwner("REMOVED").personId(), "el retiro debe llevar al dueno de la primera maleta");

        // ahora se retira la segunda
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of());
        }
        assertEquals(2, store.countEvents("REMOVED"));
        assertEquals(2L, store.lastOwner("REMOVED").personId());
    }

    @Test
    void usaLosPuntosDelCuerpoParaLeerElColorDeLaCamisaDelDueno(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("dueno-con-pose");

        // buscador de pose falso: hombros y caderas sobre la zona roja, a la izquierda del recorte
        PoseEstimator.Keypoint[] pose = new PoseEstimator.Keypoint[17];
        java.util.Arrays.fill(pose, new PoseEstimator.Keypoint(0, 0, 0));
        pose[PoseEstimator.LEFT_SHOULDER] = new PoseEstimator.Keypoint(8, 32, 0.9f);
        pose[PoseEstimator.RIGHT_SHOULDER] = new PoseEstimator.Keypoint(42, 32, 0.9f);
        pose[PoseEstimator.LEFT_HIP] = new PoseEstimator.Keypoint(10, 68, 0.9f);
        pose[PoseEstimator.RIGHT_HIP] = new PoseEstimator.Keypoint(40, 68, 0.9f);
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir, crop -> pose);

        // frame blanco con un torso rojo en (65,90); la persona (caja ancha 150x120) empieza en (60,60)
        BufferedImage frame = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 400);
        g.setColor(Color.RED);
        g.fillRect(65, 90, 40, 40);
        g.dispose();
        Detection ancha = new Detection("person", 0.9f, 60, 60, 150, 120);

        tracker.onFrame(frame, List.of(suitcase(100, 100), ancha));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(frame, List.of(suitcase(100, 100), ancha));

        PresenceEventStore.OwnerInfo owner = store.lastOwner("REGISTERED_AT_REST");
        assertNotNull(owner);
        assertEquals("persona con camisa roja", owner.description());
    }

    private static BufferedImage blankFrame() {
        return new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
    }

    private static void registrarConDueno(ObjectTracker tracker, MutableClock clock, BufferedImage frame) {
        tracker.onFrame(frame, List.of(suitcase(100, 100), person(60, 60)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(frame, List.of(suitcase(100, 100), person(60, 60)));
    }

    private static void retirar(ObjectTracker tracker, MutableClock clock, BufferedImage frame) {
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(frame, List.of());
        }
    }

    @Test
    void guardaLaDescripcionPorIADelDuenoEnLosDosEventos(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("ia-dos-eventos");
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir)
                .withOwnerVisionDescriber(crop -> "ropa superior: camiseta negra; tatuajes: antebrazo");
        tracker.useAiExecutor(Runnable::run);

        registrarConDueno(tracker, clock, blankFrame());
        assertEquals("ropa superior: camiseta negra; tatuajes: antebrazo", store.lastOwnerAiDescription("REGISTERED_AT_REST"));

        retirar(tracker, clock, blankFrame());
        assertEquals("ropa superior: camiseta negra; tatuajes: antebrazo", store.lastOwnerAiDescription("REMOVED"),
                "el retiro tambien debe llevar la descripcion del dueno");
    }

    @Test
    void mergeTattoosReemplazaElDatoDeLaPrimeraRespuestaYAgregaSiNoEstaba() {
        assertEquals("ropa superior: camiseta negra; tatuajes: ancla",
                ObjectTracker.mergeTattoos("ropa superior: camiseta negra; tatuajes: brazo", "ancla"));
        assertEquals("ropa superior: camiseta negra; tatuajes: ninguno",
                ObjectTracker.mergeTattoos("ropa superior: camiseta negra", "ninguno"));
        assertEquals("tatuajes: ancla", ObjectTracker.mergeTattoos(null, "ancla"));
        assertEquals("ropa superior: camiseta negra", ObjectTracker.mergeTattoos("ropa superior: camiseta negra", null));
        assertNull(ObjectTracker.mergeTattoos(null, null));
    }

    /** Puntos del cuerpo falsos: un brazo dentro del recorte de la persona (50x120 en person(60,60)). */
    private static PoseEstimator.Keypoint[] poseConUnBrazo() {
        PoseEstimator.Keypoint[] pose = new PoseEstimator.Keypoint[17];
        java.util.Arrays.fill(pose, new PoseEstimator.Keypoint(0, 0, 0));
        pose[PoseEstimator.LEFT_ELBOW] = new PoseEstimator.Keypoint(10, 50, 0.9f);
        pose[PoseEstimator.LEFT_WRIST] = new PoseEstimator.Keypoint(12, 95, 0.9f);
        return pose;
    }

    /** IA falsa que anota cada pregunta que recibe y responde lo indicado (respuestaPersona puede ser null: fallo). */
    private static OwnerVisionDescriber iaQueAnotaLasPreguntas(java.util.List<String> preguntas,
                                                                String respuestaPersona, String respuestaAntebrazos) {
        return new OwnerVisionDescriber() {
            @Override
            public String describe(BufferedImage ownerCrop) {
                preguntas.add("persona");
                return respuestaPersona;
            }

            @Override
            public String describeArms(BufferedImage armsCrop) {
                preguntas.add("antebrazos");
                return respuestaAntebrazos;
            }
        };
    }

    @Test
    void siLaIANoDiceNadaDeTatuajesSePreguntaSobreLosAntebrazosYSeCombinaLaRespuesta(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("ia-antebrazos");
        java.util.List<String> preguntas = new java.util.ArrayList<>();
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir, crop -> poseConUnBrazo())
                .withOwnerVisionDescriber(iaQueAnotaLasPreguntas(preguntas, "ropa superior: camiseta negra", "dibujo gris en el antebrazo"));
        tracker.useAiExecutor(Runnable::run);

        registrarConDueno(tracker, clock, blankFrame());

        assertEquals(java.util.List.of("persona", "antebrazos"), preguntas);
        assertEquals("ropa superior: camiseta negra; tatuajes: dibujo gris en el antebrazo",
                store.lastOwnerAiDescription("REGISTERED_AT_REST"));
    }

    @Test
    void siLaIAYaDijoAlgoDeTatuajesNoSeGastaLaSegundaLlamada(@TempDir Path tempDir) {
        for (String respuesta : java.util.List.of("ropa superior: camiseta negra; tatuajes: ancla en el brazo",
                "ropa superior: camiseta negra; tatuajes: ninguno")) {
            MutableClock clock = new MutableClock();
            PresenceEventStore store = PresenceEventStore.inMemory("ia-sin-segunda-" + respuesta.hashCode());
            java.util.List<String> preguntas = new java.util.ArrayList<>();
            ObjectTracker tracker = new ObjectTracker(store, clock, tempDir, crop -> poseConUnBrazo())
                    .withOwnerVisionDescriber(iaQueAnotaLasPreguntas(preguntas, respuesta, "no debe usarse"));
            tracker.useAiExecutor(Runnable::run);

            registrarConDueno(tracker, clock, blankFrame());

            assertEquals(java.util.List.of("persona"), preguntas, "ya hay dato de tatuajes: " + respuesta);
            assertEquals(respuesta, store.lastOwnerAiDescription("REGISTERED_AT_REST"));
        }
    }

    @Test
    void siLaPrimeraLlamadaFalloNoSeInsisteConLosAntebrazos(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("ia-primera-fallo");
        java.util.List<String> preguntas = new java.util.ArrayList<>();
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir, crop -> poseConUnBrazo())
                .withOwnerVisionDescriber(iaQueAnotaLasPreguntas(preguntas, null, "no debe usarse"));
        tracker.useAiExecutor(Runnable::run);

        registrarConDueno(tracker, clock, blankFrame());

        assertEquals(java.util.List.of("persona"), preguntas, "si el servicio no respondio, no se hace una segunda llamada");
        assertNull(store.lastOwnerAiDescription("REGISTERED_AT_REST"));
    }

    @Test
    void hasTattooInfoDetectaSoloUnDatoRealDeTatuajes() {
        assertTrue(ObjectTracker.hasTattooInfo("ropa superior: camisa; tatuajes: ninguno"));
        assertTrue(ObjectTracker.hasTattooInfo("tatuajes: ancla"));
        assertFalse(ObjectTracker.hasTattooInfo("ropa superior: camisa; cabello: corto"));
        assertFalse(ObjectTracker.hasTattooInfo("ropa superior: camisa con tatuajes: no es una clave"));
        assertFalse(ObjectTracker.hasTattooInfo(null));
    }

    @Test
    void sinPoseNoSePreguntaPorLosAntebrazos(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("ia-sin-pose");
        java.util.List<String> preguntas = new java.util.ArrayList<>();
        OwnerVisionDescriber describer = new OwnerVisionDescriber() {
            @Override
            public String describe(BufferedImage ownerCrop) {
                preguntas.add("persona");
                return "ropa superior: camisa";
            }

            @Override
            public String describeArms(BufferedImage armsCrop) {
                preguntas.add("antebrazos");
                return "algo";
            }
        };
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir).withOwnerVisionDescriber(describer);
        tracker.useAiExecutor(Runnable::run);

        registrarConDueno(tracker, clock, blankFrame());

        assertEquals(java.util.List.of("persona"), preguntas, "sin puntos del cuerpo no hay recorte de antebrazos");
        assertEquals("ropa superior: camisa", store.lastOwnerAiDescription("REGISTERED_AT_REST"));
    }

    @Test
    void siLaDescripcionPorIALlegaDespuesDelRetiroLasDosFilasLaReciben(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("ia-llega-tarde");
        java.util.List<Runnable> pendientes = new java.util.ArrayList<>();
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir)
                .withOwnerVisionDescriber(crop -> "ropa superior: camisa roja");
        tracker.useAiExecutor(pendientes::add); // la IA "todavia no responde"

        registrarConDueno(tracker, clock, blankFrame());
        retirar(tracker, clock, blankFrame());
        assertEquals(1, store.countEvents("REMOVED"));
        assertNull(store.lastOwnerAiDescription("REMOVED"), "aun no llega la respuesta de la IA");
        assertEquals(1, pendientes.size());

        pendientes.forEach(Runnable::run); // ahora si llega

        assertEquals("ropa superior: camisa roja", store.lastOwnerAiDescription("REGISTERED_AT_REST"));
        assertEquals("ropa superior: camisa roja", store.lastOwnerAiDescription("REMOVED"));
    }

    @Test
    void sinRespuestaDeLaIAElDuenoConservaLaDescripcionLocal(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("ia-sin-respuesta");
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir).withOwnerVisionDescriber(crop -> null);
        tracker.useAiExecutor(Runnable::run);

        registrarConDueno(tracker, clock, blankFrame());

        assertNull(store.lastOwnerAiDescription("REGISTERED_AT_REST"));
        assertNotNull(store.lastOwner("REGISTERED_AT_REST").description(), "la descripcion por color sigue ahi");
    }

    @Test
    void siLaIALanzaUnaExcepcionElSeguimientoContinua(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("ia-excepcion");
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir).withOwnerVisionDescriber(crop -> {
            throw new IllegalStateException("servicio caido");
        });
        tracker.useAiExecutor(Runnable::run);

        registrarConDueno(tracker, clock, blankFrame());
        retirar(tracker, clock, blankFrame());

        assertEquals(1, store.countEvents("REGISTERED_AT_REST"));
        assertEquals(1, store.countEvents("REMOVED"));
    }

    @Test
    void laFotoDelDuenoIncluyeElObjetoAunqueNoEsteDentroDeLaCajaDeLaPersona(@TempDir Path tempDir) throws Exception {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("foto-dueno-con-objeto");
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir);
        BufferedImage frame = blankFrame();

        Detection persona = person(60, 60);   // x 60-110, y 60-180
        Detection maleta = suitcase(150, 100); // x 150-230, y 100-160: a un lado de la persona, sin tocarla
        tracker.onFrame(frame, List.of(maleta, persona));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(frame, List.of(maleta, persona));

        BufferedImage saved = javax.imageio.ImageIO.read(new java.io.File(store.lastOwner("REGISTERED_AT_REST").cropPath()));
        assertEquals(170, saved.getWidth(), "el ancho abarca desde la persona (x=60) hasta el final de la maleta (x=230)");
        assertEquals(120, saved.getHeight(), "el alto es el de la persona (y 60-180)");
    }

    @Test
    void vaciarLosEventosBorraTodoYReiniciaLosIds() {
        PresenceEventStore store = PresenceEventStore.inMemory("vaciar-eventos");
        Instant when = Instant.parse("2026-01-01T00:00:00Z");
        store.recordRegistered(1, "suitcase", 0, 0, 10, 10, when, null, null);
        store.recordRemoved(1, "suitcase", 0, 0, 10, 10, when, false, null, null);

        assertEquals(2, store.clearAllEvents(), "debe informar cuantas filas habia");
        assertEquals(0, store.countEvents("REGISTERED_AT_REST"));
        assertEquals(0, store.countEvents("REMOVED"));
        assertEquals(1L, store.recordRegistered(2, "backpack", 0, 0, 10, 10, when, null, null),
                "despues de vaciar, los ids empiezan otra vez en 1");
    }

    @Test
    void unObjetoQueElDetectorCambiaDeClaseEntreFramesSigueSiendoElMismo() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("cambio-de-clase");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        // la misma maleta, siempre en el mismo sitio: el primer frame el detector dice backpack y despues handbag
        tracker.onFrame(null, List.of(new Detection("backpack", 0.6f, 100, 100, 80, 90)));
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(1));
            tracker.onFrame(null, List.of(new Detection("handbag", 0.5f, 102, 101, 80, 90)));
        }

        assertEquals(1, store.countEvents("REGISTERED_AT_REST"));
        assertEquals(1L, store.lastTrackedObjectId("REGISTERED_AT_REST"),
                "sigue siendo el objeto #1 (el que empezo como backpack), no uno nuevo que empezo a contar de cero al cambiar la clase");
    }

    @Test
    void unaDeteccionDeOtraClaseNoLeRobaElObjetoAOtraDeSuMismaClase() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("sin-robo-entre-clases");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        Detection mochila = new Detection("backpack", 0.7f, 100, 100, 80, 90);
        Detection bolso = new Detection("handbag", 0.7f, 110, 110, 80, 90); // solapada con la mochila, pero es otro objeto
        tracker.onFrame(null, List.of(mochila, bolso));
        clock.advance(Duration.ofSeconds(4));
        // en el siguiente frame llegan en orden distinto: cada una debe seguir con su objeto
        tracker.onFrame(null, List.of(bolso, mochila));

        assertEquals(2, store.countEvents("REGISTERED_AT_REST"), "dos objetos distintos, aunque se solapen");
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

    @Test
    void registraComoDuenoALaPersonaQueEstabaCercaDelObjeto() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("dueno-cerca");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(suitcase(100, 100), person(60, 60)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(suitcase(100, 100), person(60, 60)));

        PresenceEventStore.OwnerInfo registered = store.lastOwner("REGISTERED_AT_REST");
        assertNotNull(registered, "debe quedar un dueno probable al asentarse el objeto");
        assertNotNull(registered.personId());
        assertNull(registered.description(), "sin imagen no hay descripcion");
        assertNull(registered.cropPath(), "sin imagen no hay recorte");

        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMillis(500));
            tracker.onFrame(null, List.of());
        }
        PresenceEventStore.OwnerInfo removed = store.lastOwner("REMOVED");
        assertNotNull(removed, "el evento de retiro tambien debe llevar al dueno del objeto");
        assertEquals(registered.personId(), removed.personId());
    }

    @Test
    void noHayDuenoSiLaPersonaEstabaLejosDelObjeto() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("dueno-lejos");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        tracker.onFrame(null, List.of(suitcase(100, 100), person(600, 600)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(null, List.of(suitcase(100, 100), person(600, 600)));

        assertEquals(1, store.countEvents("REGISTERED_AT_REST"));
        assertNull(store.lastOwner("REGISTERED_AT_REST"), "una persona lejana no es el dueno");
    }

    @Test
    void guardaElRecorteYDescribeLaRopaDelDueno(@TempDir Path tempDir) {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("dueno-descripcion");
        ObjectTracker tracker = new ObjectTracker(store, clock, tempDir);

        BufferedImage frame = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 400, 400);
        g.dispose();

        tracker.onFrame(frame, List.of(suitcase(100, 100), person(60, 60)));
        clock.advance(Duration.ofSeconds(4));
        tracker.onFrame(frame, List.of(suitcase(100, 100), person(60, 60)));

        PresenceEventStore.OwnerInfo owner = store.lastOwner("REGISTERED_AT_REST");
        assertNotNull(owner);
        assertEquals("persona con camisa roja", owner.description());
        assertNotNull(owner.cropPath(), "debe guardar la ruta del recorte del dueno");
        assertTrue(Files.exists(Path.of(owner.cropPath())), "el recorte del dueno debe existir en disco");
    }

    @Test
    void elDuenoEsLaPersonaQueMasFramesEstuvoCercaYCadaPersonaTieneSuPropioId() {
        MutableClock clock = new MutableClock();
        PresenceEventStore store = PresenceEventStore.inMemory("dueno-votacion");
        ObjectTracker tracker = new ObjectTracker(store, clock);

        // maleta en (300,300); las personas A (id 1) y B (id 2) quedan a 60px de ella y a 170px entre si
        Detection a = person(230, 250);
        Detection b = person(400, 250);

        tracker.onFrame(null, List.of(suitcase(300, 300), a));
        clock.advance(Duration.ofSeconds(1));
        tracker.onFrame(null, List.of(suitcase(300, 300), b));
        clock.advance(Duration.ofSeconds(1));
        tracker.onFrame(null, List.of(suitcase(300, 300), b));
        clock.advance(Duration.ofSeconds(1));
        tracker.onFrame(null, List.of(suitcase(300, 300), b)); // aqui se cumplen los 3s quieta

        PresenceEventStore.OwnerInfo owner = store.lastOwner("REGISTERED_AT_REST");
        assertNotNull(owner);
        assertEquals(2L, owner.personId(), "B estuvo 3 frames cerca y A solo 1: B es el dueno probable");
    }
}
