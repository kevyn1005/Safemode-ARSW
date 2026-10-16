package com.safemode.presence;

import com.safemode.camerafeed.BrowserFrameSource;
import com.safemode.camerafeed.FrameCapturer;
import com.safemode.camerafeed.FrameSource;
import com.safemode.vision.ObjectDetector;
import com.safemode.vision.PoseEstimator;

import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

// Script de verificacion manual (no es un test automatizado; no usa JUnit).
// La logica de ObjectTracker si tiene pruebas automatizadas de verdad:
// ver ObjectTrackerTest en src/test/java (no necesitan camara).
public class ObjectPresenceTrackerTest {
    private static final int BROWSER_FRAMES_PORT = 8091;

    public static void main(String[] args) throws Exception {
        clearPreviousRunPhotos();

        // Camaras a vigilar: 1 (como siempre) o mas, desde la ventana compartida del navegador (ver openFrameSource).
        int cameras = cameraCount();
        FrameSource capturer = openFrameSource(cameras > 1);

        // Modelo de deteccion segun el modo: con 1 camara el mas grande disponible (yolov8m); con 2 o mas, yolov8s, que
        // cabe en el ritmo del tracker (ver docs/adr/0003, seccion 3b). Se puede forzar con SAFEMODE_MODEL.
        String modelPath = detectionModel(cameras);
        int threads = detectorThreads(cameras);
        System.out.println("Camaras: " + cameras + " | modelo de deteccion: " + modelPath
                + (threads > 0 ? " | hilos por detector: " + threads : ""));
        ObjectDetector[] detectors = new ObjectDetector[cameras];
        for (int i = 0; i < cameras; i++) { // un detector por camara: comparten el modelo del disco, no el estado
            detectors[i] = threads > 0 ? new ObjectDetector(modelPath, threads) : new ObjectDetector(modelPath);
        }

        // Modelo de pose opcional (tambien local, en data/): ubica el torso del dueno para leer el color de su camisa.
        Path poseModel = Path.of("object-presence-tracker", "data", "models", "yolov8m-pose.onnx");
        PoseEstimator poseEstimator = Files.exists(poseModel) ? new PoseEstimator(poseModel.toString()) : null;
        System.out.println("Modelo de pose: " + (poseEstimator != null ? poseModel : "ninguno (se usa la franja central)"));

        // Sin variable SAFEMODE_DB_URL usa el archivo local; con ella (ej. jdbc:h2:tcp://localhost:9092/presence) la base de docker-compose.
        try (PresenceEventStore store = PresenceEventStore.fromEnvironment("./object-presence-tracker/data/presence")) {
            // Cada corrida de prueba empieza tambien con la tabla vacia (las fotos ya se borraron arriba).
            System.out.println("Filas de la corrida anterior borradas: " + store.clearAllEvents());

            // Descripcion del dueno con IA (opcional): solo si existe la variable de entorno NVIDIA_API_KEY.
            // OJO privacidad: el recorte con la cara de la persona se envia a un servicio externo.
            NvidiaOwnerVisionDescriber ai = NvidiaOwnerVisionDescriber.fromEnvironment();
            if (ai != null) {
                ai.warmUpAsync(); // el servicio apaga los modelos sin uso: se despierta mientras se prepara la escena
            }
            System.out.println("Descripcion por IA: " + (ai != null ? "NVIDIA " + ai.modelName()
                    : "desactivada (falta la variable de entorno NVIDIA_API_KEY)"));

            // Un seguimiento independiente por camara: los ids de objeto y de persona no se mezclan entre camaras.
            ObjectTracker[] trackers = new ObjectTracker[cameras];
            for (int i = 0; i < cameras; i++) {
                trackers[i] = new ObjectTracker(store, poseEstimator).withCameraId(i + 1);
                // si el detector pierde la mochila un momento (alguien se cruza) pero la zona sigue igual, no se da por retirada
                trackers[i].withRemovalVerification();
                if (ai != null) {
                    trackers[i].withOwnerVisionDescriber(ai);
                }
            }

            Thread.sleep(1000); // esperar el primer frame

            // 60 s por defecto (da tiempo a dejar un objeto, esperar y retirarlo); otro valor: "-Dexec.args=30".
            // 0 = sin limite de tiempo: corre hasta que lo detengan (lo usa el boton del panel, ver watchStopSignal).
            long captureSeconds = args.length > 0 ? Long.parseLong(args[0].trim()) : 60;
            boolean untilStopped = captureSeconds <= 0;
            System.out.println(untilStopped ? "Duracion de captura: hasta que lo detengan"
                    : "Duracion de captura: " + captureSeconds + " s");
            long endAt = System.currentTimeMillis() + captureSeconds * 1000;
            AtomicBoolean stopRequested = watchStopSignal();

            if (cameras == 1) {
                runCamera(1, cameraSource(capturer, 1), detectors[0], trackers[0], stopRequested, untilStopped, endAt);
            } else {
                List<Thread> loops = new ArrayList<>();
                for (int i = 0; i < cameras; i++) {
                    int cameraId = i + 1;
                    ObjectDetector detector = detectors[i];
                    ObjectTracker tracker = trackers[i];
                    FrameSource source = cameraSource(capturer, cameraId);
                    Thread loop = new Thread(() -> {
                        try {
                            runCamera(cameraId, source, detector, tracker, stopRequested, untilStopped, endAt);
                        } catch (Exception e) {
                            System.err.println("[CAM " + cameraId + "] El bucle termino con error: " + e);
                            stopRequested.set(true); // una camara caida detiene todo de forma ordenada
                        }
                    }, "camera-" + cameraId);
                    loops.add(loop);
                    loop.start();
                }
                for (Thread loop : loops) {
                    loop.join();
                }
            }

            // las descripciones por IA llegan en segundo plano: esperarlas antes de cerrar la base de datos
            for (ObjectTracker tracker : trackers) {
                tracker.awaitPendingDescriptions(Duration.ofSeconds(70));
            }
            if (ai != null) {
                System.out.println(ai.usageSummary());
            }
        } finally {
            capturer.stop();
        }
    }

    /** El bucle de una camara: toma el ultimo fotograma, lo detecta y se lo pasa a su seguimiento, hasta la senal de parada. */
    private static void runCamera(int cameraId, FrameSource source, ObjectDetector detector, ObjectTracker tracker,
                                  AtomicBoolean stopRequested, boolean untilStopped, long endAt) throws Exception {
        while (!stopRequested.get() && (untilStopped || System.currentTimeMillis() < endAt)) {
            BufferedImage frame = source.getLatestFrame();
            if (frame == null) {
                Thread.sleep(500);
                continue;
            }

            List<ObjectDetector.Detection> detections = detector.detect(frame);
            tracker.onFrame(frame, detections);

            Thread.sleep(500);
        }
    }

    /** La vista de una camara: con la ventana compartida cada camara tiene su fotograma; la captura de pantalla es solo la 1. */
    private static FrameSource cameraSource(FrameSource base, int cameraId) {
        return base instanceof BrowserFrameSource browser ? browser.forCamera(cameraId) : base;
    }

    /** Numero de camaras a vigilar (variable {@code SAFEMODE_CAMERAS}, de 1 a {@link BrowserFrameSource#MAX_CAMERAS}; por defecto 1). */
    static int cameraCount() {
        return parseCameraCount(System.getenv("SAFEMODE_CAMERAS"));
    }

    static int parseCameraCount(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            int cameras = Integer.parseInt(value.trim());
            if (cameras >= 1 && cameras <= BrowserFrameSource.MAX_CAMERAS) {
                return cameras;
            }
        } catch (NumberFormatException ignored) {
            // cae al mensaje de abajo
        }
        System.out.println("SAFEMODE_CAMERAS invalida (" + value + "): se usa 1 camara.");
        return 1;
    }

    /** Modelo de deteccion: el indicado en SAFEMODE_MODEL; si no, yolov8m con 1 camara (si existe) y yolov8s con 2 o mas. */
    static String detectionModel(int cameras) {
        Path biggerModel = Path.of("object-presence-tracker", "data", "models", "yolov8m.onnx");
        return detectionModel(cameras, System.getenv("SAFEMODE_MODEL"), Files.exists(biggerModel));
    }

    static String detectionModel(int cameras, String forced, boolean biggerModelExists) {
        if (forced != null && !forced.isBlank()) {
            return forced.trim();
        }
        // el modelo mas grande se exporta localmente (no va al repo: data/ esta en .gitignore)
        return cameras == 1 && biggerModelExists
                ? Path.of("object-presence-tracker", "data", "models", "yolov8m.onnx").toString()
                : "vision-detection-service/src/main/resources/models/yolov8s.onnx";
    }

    /**
     * Hilos de calculo por detector: con 1 camara el valor por defecto de ONNX Runtime (0 = todos los nucleos, como siempre); con
     * varias, los nucleos repartidos entre las camaras, porque dos detectores con todos los hilos se estorban (medido en el ADR
     * 0003). Se puede forzar con SAFEMODE_DETECTOR_THREADS.
     */
    static int detectorThreads(int cameras) {
        return detectorThreads(cameras, System.getenv("SAFEMODE_DETECTOR_THREADS"), Runtime.getRuntime().availableProcessors());
    }

    static int detectorThreads(int cameras, String forced, int processors) {
        if (forced != null && !forced.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(forced.trim()));
            } catch (NumberFormatException ignored) {
                System.out.println("SAFEMODE_DETECTOR_THREADS invalida (" + forced + "): se usa el valor automatico.");
            }
        }
        return cameras == 1 ? 0 : Math.max(1, processors / cameras);
    }

    /**
     * Cuando el gateway lanza este programa desde el boton del panel (variable {@code SAFEMODE_STOP_ON_STDIN=1}) se
     * detiene de forma ordenada: el gateway escribe "stop" en la entrada estandar, o la cierra si el mismo se apaga
     * (asi no queda un tracker huerfano). Sin la variable no se lee la entrada: una corrida normal desde la terminal
     * o un IDE (cuya entrada puede venir vacia) no se debe cortar sola.
     */
    private static AtomicBoolean watchStopSignal() {
        AtomicBoolean stopRequested = new AtomicBoolean();
        if (!"1".equals(System.getenv("SAFEMODE_STOP_ON_STDIN"))) {
            return stopRequested;
        }
        Thread watcher = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String line;
                do {
                    line = reader.readLine();
                } while (line != null && !line.trim().equalsIgnoreCase("stop"));
            } catch (IOException ignored) {
                // la entrada se cerro: se trata igual que un "stop"
            }
            System.out.println("Senal de parada recibida: terminando la corrida...");
            stopRequested.set(true);
        }, "stop-watcher");
        watcher.setDaemon(true);
        watcher.start();
        return stopRequested;
    }

    /**
     * De donde salen los frames. Por defecto, la captura de la mitad izquierda de la pantalla (como siempre). Con la
     * variable de entorno {@code SAFEMODE_FRAME_SOURCE=browser} se recibe la ventana que el operador comparte desde el
     * panel (boton "Compartir ventana de la camara"): asi la camara puede quedar tapada por otras ventanas.
     */
    private static FrameSource openFrameSource(boolean forceBrowser) throws Exception {
        if (forceBrowser || "browser".equalsIgnoreCase(System.getenv("SAFEMODE_FRAME_SOURCE"))) {
            BrowserFrameSource source = new BrowserFrameSource("localhost", BROWSER_FRAMES_PORT,
                    Set.of("http://localhost:8080", "http://127.0.0.1:8080"));
            source.startAndWait();
            System.out.println("Fuente de frames: ventana compartida desde el navegador (ws://localhost:" + BROWSER_FRAMES_PORT
                    + "). Abre http://localhost:8080 y pulsa \"Compartir ventana de la camara\".");
            return source;
        }
        // Solo la mitad izquierda de la pantalla: se captura esa zona (no toda), asi se puede tener el panel de
        // alertas (http://localhost:8080) abierto en la otra mitad sin que el detector lo confunda con la escena.
        // En Windows: Win+flecha-izquierda sobre la ventana de la camara, Win+flecha-derecha sobre el navegador.
        FrameCapturer capturer = new FrameCapturer(leftHalfOfScreen());
        capturer.start(2); // 2 fps
        return capturer;
    }

    /**
     * Mitad izquierda de la pantalla (ancho total / 2, alto completo): se calcula del tamano real de la pantalla en
     * vez de tener un numero fijo, para que funcione en cualquier monitor. Coincide con lo que Windows deja al acoplar
     * una ventana con Win+flecha-izquierda, asi que no hace falta calcular esquinas a mano con MousePositionFinder.
     * Si la camara no ocupa toda esa mitad (otra resolucion, otro acomodo de ventanas), ajustar aqui.
     */
    private static Rectangle leftHalfOfScreen() {
        var screen = Toolkit.getDefaultToolkit().getScreenSize();
        return new Rectangle(0, 0, screen.width / 2, screen.height);
    }

    // Cada corrida de prueba empieza con la carpeta de fotos vacia: cada PNG pesa ~3 MB y se acumulan rapido.
    // Solo borra los .png de esa carpeta (no toca subcarpetas). La tabla de eventos tambien se vacia al abrir la base.
    private static void clearPreviousRunPhotos() throws IOException {
        Path framesDir = Path.of("object-presence-tracker", "data", "frames");
        if (!Files.isDirectory(framesDir)) {
            return;
        }
        int deleted = 0;
        try (var files = Files.list(framesDir)) {
            for (Path file : files.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".png")).toList()) {
                Files.delete(file);
                deleted++;
            }
        }
        System.out.println("Fotos de la corrida anterior borradas: " + deleted);
    }
}
