package com.safemode.vision;

import com.safemode.camerafeed.FrameCapturer;
import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;

public class VisionDetectionTest {
    public static void main(String[] args) throws Exception {
        Rectangle region = new Rectangle(41, 45, 1195, 660);

        FrameCapturer capturer = new FrameCapturer(region);
        capturer.start(2);

        ObjectDetector detector = new ObjectDetector(
                "vision-detection-service/src/main/resources/models/yolov8n.onnx"
        );

        System.out.println("Tienes 8 segundos para acomodar la escena...");
        Thread.sleep(8000);

        BufferedImage frame = capturer.getLatestFrame();

        ImageIO.write(frame, "png", new File("debug_frame.png"));
        System.out.println("Frame guardado en debug_frame.png - revísalo");

        detector.debugRelevantClassesOnly(frame);

        capturer.stop();
    }
}