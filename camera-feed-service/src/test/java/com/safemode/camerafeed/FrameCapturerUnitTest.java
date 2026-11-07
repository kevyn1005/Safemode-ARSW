package com.safemode.camerafeed;

import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class FrameCapturerUnitTest {

    @Test
    void capturaFramesDeLaRegionYSeDetiene() throws Exception {
        // java.awt.Robot necesita pantalla: sin ella (CI, Docker) el test se omite
        assumeFalse(GraphicsEnvironment.isHeadless());
        FrameCapturer capturer = new FrameCapturer(new Rectangle(0, 0, 50, 40));
        assertNull(capturer.getLatestFrame());

        capturer.start(10);
        long limite = System.currentTimeMillis() + 3000;
        while (capturer.getLatestFrame() == null && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
        }
        capturer.stop();

        BufferedImage frame = capturer.getLatestFrame();
        assertNotNull(frame);
        assertEquals(50, frame.getWidth());
        assertEquals(40, frame.getHeight());
    }

    @Test
    void stopSinStartNoFalla() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        FrameCapturer capturer = new FrameCapturer(new Rectangle(0, 0, 10, 10));
        assertDoesNotThrow(capturer::stop);
    }
}