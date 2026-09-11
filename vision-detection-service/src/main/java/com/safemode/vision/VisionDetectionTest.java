package com.safemode.vision;

import com.safemode.camerafeed.FrameCapturer;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

public class VisionDetectionTest {
    public static void main(String[] args) throws Exception {
        Rectangle region = new Rectangle(41, 45, 1195, 660);

        FrameCapturer capturer = new FrameCapturer(region);
        capturer.start(2); // 2 fps

        ObjectDetector detector = new ObjectDetector(
                "vision-detection-service/src/main/resources/models/yolov8n.onnx"
        );

        Thread.sleep(1000); // esperar el primer frame

        int totalFrames = 15;
        for (int i = 0; i < totalFrames; i++) {
            BufferedImage frame = capturer.getLatestFrame();
            if (frame == null) {
                Thread.sleep(500);
                continue;
            }

            List<ObjectDetector.Detection> detections = detector.detect(frame);

            System.out.println("--- Frame " + i + " ---");
            if (detections.isEmpty()) {
                System.out.println("  Sin detecciones.");
            } else {
                for (ObjectDetector.Detection d : detections) {
                    System.out.println("  clase=" + d.className()
                            + " posicion=(" + d.x() + "," + d.y() + ")"
                            + " tamaño=" + d.width() + "x" + d.height());
                }
            }

            Thread.sleep(500);
        }

        capturer.stop();
    }
}