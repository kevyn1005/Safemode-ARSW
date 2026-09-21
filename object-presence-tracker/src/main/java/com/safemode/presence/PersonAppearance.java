package com.safemode.presence;

import java.awt.image.BufferedImage;

/**
 * Firma barata de como se ve una persona: el color medio de una zona del torso. No reconoce caras: solo sirve para no
 * confundir a una persona que sale de cuadro con otra que entra justo despues (ropa de colores distintos).
 */
final class PersonAppearance {

    private static final int GRID = 8;
    // diferencia de color (distancia en RGB, 0 a 441) por encima de la cual se trata como otra persona
    static final double SAME_PERSON_MAX_DISTANCE = 80;

    private final double red;
    private final double green;
    private final double blue;

    private PersonAppearance(double red, double green, double blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    /** Firma del torso de la persona dentro de su caja, o null si no hay frame o la zona queda fuera de la imagen. */
    static PersonAppearance of(BufferedImage frame, int x, int y, int width, int height) {
        if (frame == null) {
            return null;
        }
        int x0 = Math.max(0, x + (int) (width * 0.3));
        int x1 = Math.min(frame.getWidth(), x + (int) (width * 0.7));
        int y0 = Math.max(0, y + (int) (height * 0.25));
        int y1 = Math.min(frame.getHeight(), y + (int) (height * 0.55));
        if (x1 - x0 < GRID || y1 - y0 < GRID) {
            return null;
        }
        double r = 0;
        double g = 0;
        double b = 0;
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                int rgb = frame.getRGB(x0 + (int) ((i + 0.5) * (x1 - x0) / GRID), y0 + (int) ((j + 0.5) * (y1 - y0) / GRID));
                r += (rgb >> 16) & 0xFF;
                g += (rgb >> 8) & 0xFF;
                b += rgb & 0xFF;
            }
        }
        int n = GRID * GRID;
        return new PersonAppearance(r / n, g / n, b / n);
    }

    double distanceTo(PersonAppearance other) {
        return Math.sqrt(Math.pow(red - other.red, 2) + Math.pow(green - other.green, 2) + Math.pow(blue - other.blue, 2));
    }

    /** Si las dos firmas pueden ser de la misma persona. */
    boolean looksLike(PersonAppearance other) {
        return distanceTo(other) <= SAME_PERSON_MAX_DISTANCE;
    }
}
