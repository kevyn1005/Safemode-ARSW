package com.safemode.vision;

import ai.onnxruntime.OrtException;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ObjectDetectorModelTest {

    private static final Path MODEL = Path.of("src", "main", "resources", "models", "yolov8n.onnx");
    private static final Set<String> RELEVANT = Set.of("person", "backpack", "handbag", "suitcase");

    private interface Action {
        void run() throws Exception;
    }

    private static ObjectDetector open() throws OrtException {
        assumeTrue(Files.exists(MODEL), "No esta el modelo " + MODEL);
        return new ObjectDetector(MODEL.toString());
    }

    private static BufferedImage scene(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(90, 120, 150));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(200, 60, 60));
        g.fillRect(width / 4, height / 4, width / 3, height / 2);
        g.dispose();
        return image;
    }

    private static String capture(Action action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void detectDevuelveSoloClasesRelevantesConLaConfianzaMinima() throws Exception {
        ObjectDetector detector = open();
        // horizontal y vertical: el letterbox rellena distinto en cada caso
        for (BufferedImage frame : List.of(scene(640, 480), scene(480, 640), scene(100, 80))) {
            List<ObjectDetector.Detection> detections = detector.detect(frame);
            assertNotNull(detections);
            for (ObjectDetector.Detection detection : detections) {
                assertTrue(RELEVANT.contains(detection.className()), detection.className());
                assertTrue(detection.confidence() >= 0.3f);
            }
        }
    }

    @Test
    void sePuedeLimitarLosHilosDelDetector() throws Exception {
        assumeTrue(Files.exists(MODEL), "No esta el modelo " + MODEL);
        ObjectDetector detector = new ObjectDetector(MODEL.toString(), 1);
        assertNotNull(detector.detect(scene(320, 240)));
    }

    @Test
    void losMetodosDeDepuracionImprimenSusResultados() throws Exception {
        ObjectDetector detector = open();
        BufferedImage frame = scene(640, 480);

        String top = capture(() -> detector.debugTopDetections(frame, 3));
        assertTrue(top.contains("Top 3 detecciones"), top);

        String relevant = capture(() -> detector.debugRelevantClassesOnly(frame));
        for (String className : RELEVANT) {
            assertTrue(relevant.contains("mejor score para " + className), relevant);
        }
    }

    @Test
    void unModeloQueNoExisteLanzaOrtException() {
        assertThrows(OrtException.class, () -> new ObjectDetector("no-existe.onnx"));
        assertThrows(OrtException.class, () -> new ObjectDetector("no-existe.onnx", 1));
    }
}