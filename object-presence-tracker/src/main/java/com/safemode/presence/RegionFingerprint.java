package com.safemode.presence;

import java.awt.image.BufferedImage;

/**
 * Huella barata de una region de un frame: el brillo de una cuadricula de puntos (cada uno promediado con sus vecinos
 * para que el grano de la camara no cuente). Sirve para preguntar "el objeto sigue ahi aunque el detector no lo vea?":
 * se compara la huella de la zona del objeto en reposo con la de esa misma zona ahora.
 */
final class RegionFingerprint {

    static final int GRID = 24;
    private static final int NEIGHBOURHOOD = 1;
    // diferencia de brillo (0-255) a partir de la cual un punto cuenta como "cambio"
    private static final int CHANGED_LEVEL = 45;

    private final int[] brightness;

    private RegionFingerprint(int[] brightness) {
        this.brightness = brightness;
    }

    /** Huella de esa region (recortada a los bordes del frame), o null si no hay frame o la region queda fuera. */
    static RegionFingerprint of(BufferedImage frame, int x, int y, int width, int height) {
        if (frame == null) {
            return null;
        }
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(frame.getWidth(), x + width);
        int y1 = Math.min(frame.getHeight(), y + height);
        if (x1 - x0 < GRID || y1 - y0 < GRID) {
            return null;
        }
        int[] values = new int[GRID * GRID];
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                int px = x0 + (int) ((i + 0.5) * (x1 - x0) / GRID);
                int py = y0 + (int) ((j + 0.5) * (y1 - y0) / GRID);
                values[j * GRID + i] = averageBrightness(frame, px, py);
            }
        }
        return new RegionFingerprint(values);
    }

    private static int averageBrightness(BufferedImage frame, int cx, int cy) {
        long sum = 0;
        int count = 0;
        for (int dy = -NEIGHBOURHOOD; dy <= NEIGHBOURHOOD; dy++) {
            for (int dx = -NEIGHBOURHOOD; dx <= NEIGHBOURHOOD; dx++) {
                int px = Math.min(frame.getWidth() - 1, Math.max(0, cx + dx));
                int py = Math.min(frame.getHeight() - 1, Math.max(0, cy + dy));
                int rgb = frame.getRGB(px, py);
                sum += (299 * ((rgb >> 16) & 0xFF) + 587 * ((rgb >> 8) & 0xFF) + 114 * (rgb & 0xFF)) / 1000;
                count++;
            }
        }
        return (int) (sum / count);
    }

    /** Fraccion (0 a 1) de puntos cuyo brillo cambio de forma clara respecto a la otra huella. */
    double changedFraction(RegionFingerprint other) {
        int changed = 0;
        for (int i = 0; i < brightness.length; i++) {
            if (Math.abs(brightness[i] - other.brightness[i]) > CHANGED_LEVEL) {
                changed++;
            }
        }
        return (double) changed / brightness.length;
    }
}
