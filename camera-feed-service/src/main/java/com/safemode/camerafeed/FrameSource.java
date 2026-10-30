package com.safemode.camerafeed;

import java.awt.image.BufferedImage;

/**
 * De donde sale el frame mas reciente: la captura de una region de pantalla ({@link FrameCapturer}) o una ventana
 * compartida desde el navegador ({@link BrowserFrameSource}).
 */
public interface FrameSource {

    /** El frame mas reciente, o {@code null} si todavia no hay uno (o el ultimo ya es demasiado viejo). */
    BufferedImage getLatestFrame();

    void stop();
}
