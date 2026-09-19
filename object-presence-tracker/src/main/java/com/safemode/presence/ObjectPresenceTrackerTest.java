package com.safemode.presence;

import com.safemode.camerafeed.FrameCapturer;
import com.safemode.vision.ObjectDetector;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

// Script de verificacion manual (no es un test automatizado; no usa JUnit).
// La logica de ObjectTracker si tiene pruebas automatizadas de verdad:
// ver ObjectTrackerTest en src/test/java (no necesitan camara).
public class ObjectPresenceTrackerTest {
    public static void main(String[] args) throws Exception {
        Rectangle region = new Rectangle(41, 45, 1195, 660);

        FrameCapturer capturer = new FrameCapturer(region);
        capturer.start(2); // 2 fps

        ObjectDetector detector = new ObjectDetector(
                "vision-detection-service/src/main/resources/models/yolov8s.onnx"
        );

        try (PresenceEventStore store = new PresenceEventStore("./object-presence-tracker/data/presence")) {
            ObjectTracker tracker = new ObjectTracker(store);

            Thread.sleep(1000); // esperar el primer frame

            int totalFrames = 30;
            for (int i = 0; i < totalFrames; i++) {
                BufferedImage frame = capturer.getLatestFrame();
                if (frame == null) {
                    Thread.sleep(500);
                    continue;
                }

                List<ObjectDetector.Detection> detections = detector.detect(frame);
                tracker.onFrame(detections);

                Thread.sleep(500);
            }
        } finally {
            capturer.stop();
        }
    }
}
