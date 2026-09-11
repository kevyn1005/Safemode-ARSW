package com.safemode.camerafeed;

import java.awt.Rectangle;
import java.awt.Toolkit;

public class FrameCapturerTest {
    public static void main(String[] args) throws Exception {
        Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        FrameCapturer capturer = new FrameCapturer(screenRect);

        capturer.start(15); // 15 fps

        System.out.println("Capturando por 10 segundos, sin guardar nada a disco...");
        Thread.sleep(10000);

        capturer.stop();
        System.out.println("Detenido. Último frame en memoria: "
                + capturer.getLatestFrame().getWidth() + "x" + capturer.getLatestFrame().getHeight());
    }
}