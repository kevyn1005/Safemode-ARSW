package com.safemode.presence;

import com.safemode.camerafeed.FrameCapturer;
import com.safemode.vision.ObjectDetector;
import com.safemode.vision.PoseEstimator;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

// Script de verificacion manual (no es un test automatizado; no usa JUnit).
// La logica de ObjectTracker si tiene pruebas automatizadas de verdad:
// ver ObjectTrackerTest en src/test/java (no necesitan camara).
public class ObjectPresenceTrackerTest {
    public static void main(String[] args) throws Exception {
        clearPreviousRunPhotos();

        Rectangle region = new Rectangle(0, 0, 2560, 1080);

        FrameCapturer capturer = new FrameCapturer(region);
        capturer.start(2); // 2 fps

        // Si existe un modelo mas grande exportado localmente (no va al repo: data/ esta en .gitignore), se usa ese.
        Path biggerModel = Path.of("object-presence-tracker", "data", "models", "yolov8m.onnx");
        String modelPath = Files.exists(biggerModel)
                ? biggerModel.toString()
                : "vision-detection-service/src/main/resources/models/yolov8s.onnx";
        System.out.println("Modelo de deteccion: " + modelPath);
        ObjectDetector detector = new ObjectDetector(modelPath);

        // Modelo de pose opcional (tambien local, en data/): ubica el torso del dueno para leer el color de su camisa.
        Path poseModel = Path.of("object-presence-tracker", "data", "models", "yolov8m-pose.onnx");
        PoseEstimator poseEstimator = Files.exists(poseModel) ? new PoseEstimator(poseModel.toString()) : null;
        System.out.println("Modelo de pose: " + (poseEstimator != null ? poseModel : "ninguno (se usa la franja central)"));

        try (PresenceEventStore store = new PresenceEventStore("./object-presence-tracker/data/presence")) {
            // Cada corrida de prueba empieza tambien con la tabla vacia (las fotos ya se borraron arriba).
            System.out.println("Filas de la corrida anterior borradas: " + store.clearAllEvents());

            ObjectTracker tracker = new ObjectTracker(store, poseEstimator);

            // Descripcion del dueno con IA (opcional): solo si existe la variable de entorno NVIDIA_API_KEY.
            // OJO privacidad: el recorte con la cara de la persona se envia a un servicio externo.
            NvidiaOwnerVisionDescriber ai = NvidiaOwnerVisionDescriber.fromEnvironment();
            if (ai != null) {
                tracker.withOwnerVisionDescriber(ai);
            }
            System.out.println("Descripcion por IA: " + (ai != null ? "NVIDIA " + ai.modelName()
                    : "desactivada (falta la variable de entorno NVIDIA_API_KEY)"));

            Thread.sleep(1000); // esperar el primer frame

            long captureSeconds = 30;
            long endAt = System.currentTimeMillis() + captureSeconds * 1000;
            while (System.currentTimeMillis() < endAt) {
                BufferedImage frame = capturer.getLatestFrame();
                if (frame == null) {
                    Thread.sleep(500);
                    continue;
                }

                List<ObjectDetector.Detection> detections = detector.detect(frame);
                tracker.onFrame(frame, detections);

                Thread.sleep(500);
            }

            // las descripciones por IA llegan en segundo plano: esperarlas antes de cerrar la base de datos
            tracker.awaitPendingDescriptions(Duration.ofSeconds(70));
        } finally {
            capturer.stop();
        }
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
