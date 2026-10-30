package com.safemode.camerafeed;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

// Script de verificacion manual (no es un test automatizado; no usa JUnit).
public class ScreenCaptureTest {
    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();
        Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage capture = robot.createScreenCapture(screenRect);
        ImageIO.write(capture, "png", new File("captura_test.png"));
        System.out.println("Listo, revisa captura_test.png");
    }
}