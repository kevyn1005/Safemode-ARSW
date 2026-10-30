package com.safemode.presence;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.List;

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
    // region efectivamente muestreada (recortada al frame): sirve para saber donde cae cada punto de la cuadricula
    private final int x0;
    private final int y0;
    private final int x1;
    private final int y1;

    private RegionFingerprint(int[] brightness, int x0, int y0, int x1, int y1) {
        this.brightness = brightness;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /** Cuanto cambio la zona (entre los puntos que se podian comparar) y que parte de la zona se pudo comparar. */
    record Comparison(double changedFraction, double visibleFraction) {}

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
                values[j * GRID + i] = averageBrightness(frame, sampleX(x0, x1, i), sampleY(y0, y1, j));
            }
        }
        return new RegionFingerprint(values, x0, y0, x1, y1);
    }

    private static int sampleX(int x0, int x1, int i) {
        return x0 + (int) ((i + 0.5) * (x1 - x0) / GRID);
    }

    private static int sampleY(int y0, int y1, int j) {
        return y0 + (int) ((j + 0.5) * (y1 - y0) / GRID);
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
        return compare(other, List.of()).changedFraction();
    }

    /**
     * Compara con otra huella de la misma zona ignorando los puntos que caen dentro de {@code covered} (por ejemplo las
     * cajas de las personas, que tapan el objeto sin que este se haya movido): el brazo de alguien que pasa por delante
     * no cuenta como cambio. Si casi toda la zona esta tapada, {@code visibleFraction} sale baja y no se puede concluir nada.
     */
    Comparison compare(RegionFingerprint other, Collection<Rectangle> covered) {
        int compared = 0;
        int changed = 0;
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                int px = sampleX(x0, x1, i);
                int py = sampleY(y0, y1, j);
                if (covered.stream().anyMatch(r -> r.contains(px, py))) {
                    continue;
                }
                compared++;
                if (Math.abs(brightness[j * GRID + i] - other.brightness[j * GRID + i]) > CHANGED_LEVEL) {
                    changed++;
                }
            }
        }
        return new Comparison(compared == 0 ? 0 : (double) changed / compared, (double) compared / brightness.length);
    }
}
