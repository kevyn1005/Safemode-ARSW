package com.safemode.camerafeed;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class FrameCapturer {

    private final Robot robot;
    private final Rectangle region;
    private volatile BufferedImage latestFrame; // el frame más reciente, en memoria
    private final AtomicLong frameCount = new AtomicLong(0);
    private ScheduledExecutorService scheduler;

    public FrameCapturer(Rectangle region) throws AWTException {
        this.robot = new Robot();
        this.region = region;
    }

    public void start(int fps) {
        long periodMillis = 1000 / fps;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                latestFrame = robot.createScreenCapture(region);
                long count = frameCount.incrementAndGet();
                System.out.println("Frame #" + count + " capturado - "
                        + latestFrame.getWidth() + "x" + latestFrame.getHeight());
            } catch (Exception e) {
                System.err.println("Error capturando frame: " + e.getMessage());
            }
        }, 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    // Esto es lo que va a usar el siguiente servicio para pedir el frame actual
    public BufferedImage getLatestFrame() {
        return latestFrame;
    }
}