package com.safemode.presence;

import com.safemode.vision.PoseEstimator;
import com.safemode.vision.PoseEstimator.Keypoint;

import java.awt.Color;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * Describe a una persona a partir de su recorte: hoy solo el color dominante
 * de la zona del torso ("persona con camisa roja"). Es una aproximacion por
 * pixeles, sin modelo de ML: depende de la iluminacion y no distingue
 * estampados, tatuajes ni tipo de prenda.
 */
final class PersonDescriber {

    enum ColorName {
        NEGRA("negra"), BLANCA("blanca"), GRIS("gris"),
        ROJA("roja"), NARANJA("naranja"), CAFE("café"), AMARILLA("amarilla"),
        VERDE("verde"), TURQUESA("turquesa"), AZUL("azul"), MORADA("morada"), ROSADA("rosada"),
        /** Tono de piel: brazos y cara se cuelan en el torso; solo cuenta si no hay ningun otro color. */
        PIEL("color piel");

        final String label;

        ColorName(String label) {
            this.label = label;
        }
    }

    /**
     * Cuando hay puntos del cuerpo confiables pero el objeto tapa todo el torso no queda ropa que leer, y la franja fija
     * solo lee el fondo (un respaldo negro daba "camisa negra" para una sudadera beige): mejor decir que no se sabe.
     */
    static final String CLOTHING_HIDDEN = "persona con ropa de color no determinado (el objeto tapa el torso)";

    private static final int SAMPLES_PER_AXIS = 12;
    private static final float MIN_KEYPOINT_CONFIDENCE = 0.5f;
    private static final double TORSO_INSET = 0.6;

    private PersonDescriber() {
    }

    /** Devuelve algo como "persona con camisa roja", o {@code null} si no hay recorte. */
    static String describe(BufferedImage personCrop) {
        return describe(personCrop, null);
    }

    /**
     * Igual que {@link #describe(BufferedImage)}, pero ignora los pixeles dentro de
     * {@code excluded} (coordenadas del recorte): sirve para no leer como "ropa" el
     * objeto que la persona lleva encima, como una mochila sobre el pecho.
     */
    static String describe(BufferedImage personCrop, Rectangle excluded) {
        return describe(personCrop, excluded, null);
    }

    /**
     * Igual que {@link #describe(BufferedImage, Rectangle)}, pero si se dan los puntos del cuerpo
     * ({@code pose}, de {@link PoseEstimator}) el color se lee solo dentro del torso (hombros a
     * caderas), en vez de una franja fija del recorte que puede caer sobre el fondo. Si los
     * puntos no sirven se usa la franja fija.
     */
    static String describe(BufferedImage personCrop, Rectangle excluded, Keypoint[] pose) {
        if (personCrop == null) {
            return null;
        }
        ColorName color = pose == null ? null : colorInsideTorso(personCrop, excluded, pose);
        if (color == null && pose != null && excluded != null && torsoHiddenBy(personCrop, excluded, pose)) {
            return CLOTHING_HIDDEN;
        }
        if (color == null) {
            color = dominantTorsoColor(personCrop, excluded);
        }
        return "persona con camisa " + color.label;
    }

    /** El torso (segun los puntos del cuerpo) queda dentro de la imagen pero el objeto lo cubre entero. */
    private static boolean torsoHiddenBy(BufferedImage crop, Rectangle excluded, Keypoint[] pose) {
        Polygon torso = torsoPolygon(pose);
        if (torso == null) {
            return false;
        }
        Rectangle visible = torso.getBounds().intersection(new Rectangle(0, 0, crop.getWidth(), crop.getHeight()));
        return !visible.isEmpty() && excluded.contains(visible);
    }

    /** Color dominante dentro del cuadrilatero hombros-caderas, o null si los puntos no sirven o no queda ningun pixel util. */
    static ColorName colorInsideTorso(BufferedImage crop, Rectangle excluded, Keypoint[] pose) {
        Polygon torso = torsoPolygon(pose);
        if (torso == null) {
            return null;
        }
        Rectangle bounds = torso.getBounds().intersection(new Rectangle(0, 0, crop.getWidth(), crop.getHeight()));
        if (bounds.isEmpty()) {
            return null;
        }
        int stepX = Math.max(1, bounds.width / SAMPLES_PER_AXIS);
        int stepY = Math.max(1, bounds.height / SAMPLES_PER_AXIS);

        Map<ColorName, Integer> votes = new EnumMap<>(ColorName.class);
        for (int y = bounds.y; y < bounds.y + bounds.height; y += stepY) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x += stepX) {
                if (torso.contains(x, y) && (excluded == null || !excluded.contains(x, y))) {
                    votes.merge(classify(crop.getRGB(x, y)), 1, Integer::sum);
                }
            }
        }
        return votes.isEmpty() ? null : winnerOf(votes);
    }

    /**
     * Cuadrilatero hombros-caderas encogido hacia su centro (para no leer brazos ni fondo), o
     * null si no hay hombros confiables. Si las caderas no se ven, se estiman hacia abajo.
     */
    static Polygon torsoPolygon(Keypoint[] pose) {
        if (pose == null || pose.length <= PoseEstimator.RIGHT_HIP) {
            return null;
        }
        Keypoint ls = pose[PoseEstimator.LEFT_SHOULDER];
        Keypoint rs = pose[PoseEstimator.RIGHT_SHOULDER];
        if (ls.confidence() < MIN_KEYPOINT_CONFIDENCE || rs.confidence() < MIN_KEYPOINT_CONFIDENCE) {
            return null;
        }
        Keypoint lh = pose[PoseEstimator.LEFT_HIP];
        Keypoint rh = pose[PoseEstimator.RIGHT_HIP];

        double[] xs;
        double[] ys;
        if (lh.confidence() >= MIN_KEYPOINT_CONFIDENCE && rh.confidence() >= MIN_KEYPOINT_CONFIDENCE) {
            xs = new double[]{ls.x(), rs.x(), rh.x(), lh.x()};
            ys = new double[]{ls.y(), rs.y(), rh.y(), lh.y()};
        } else {
            double drop = 1.3 * Math.hypot(ls.x() - rs.x(), ls.y() - rs.y());
            xs = new double[]{ls.x(), rs.x(), rs.x(), ls.x()};
            ys = new double[]{ls.y(), rs.y(), rs.y() + drop, ls.y() + drop};
        }

        double cx = (xs[0] + xs[1] + xs[2] + xs[3]) / 4;
        double cy = (ys[0] + ys[1] + ys[2] + ys[3]) / 4;
        int[] px = new int[4];
        int[] py = new int[4];
        for (int i = 0; i < 4; i++) {
            px[i] = (int) Math.round(cx + TORSO_INSET * (xs[i] - cx));
            py[i] = (int) Math.round(cy + TORSO_INSET * (ys[i] - cy));
        }
        return new Polygon(px, py, 4);
    }

    static ColorName dominantTorsoColor(BufferedImage crop, Rectangle excluded) {
        int w = crop.getWidth();
        int h = crop.getHeight();
        // el torso queda aprox. entre el 25% y el 55% de la altura (mas arriba se cuela la cara), en la franja central
        int x0 = (int) (w * 0.25);
        int x1 = Math.min(w, Math.max(x0 + 1, (int) (w * 0.75)));
        int y0 = (int) (h * 0.25);
        int y1 = Math.min(h, Math.max(y0 + 1, (int) (h * 0.55)));
        int stepX = Math.max(1, (x1 - x0) / SAMPLES_PER_AXIS);
        int stepY = Math.max(1, (y1 - y0) / SAMPLES_PER_AXIS);

        Map<ColorName, Integer> votes = new EnumMap<>(ColorName.class);
        for (int y = y0; y < y1; y += stepY) {
            for (int x = x0; x < x1; x += stepX) {
                if (excluded == null || !excluded.contains(x, y)) {
                    votes.merge(classify(crop.getRGB(x, y)), 1, Integer::sum);
                }
            }
        }
        if (votes.isEmpty() && excluded != null) {
            // el objeto tapa todo el torso: mejor una estimacion imperfecta que ninguna
            return dominantTorsoColor(crop, null);
        }

        return winnerOf(votes);
    }

    private static ColorName winnerOf(Map<ColorName, Integer> votes) {
        ColorName winner = null;
        int best = -1;
        for (Map.Entry<ColorName, Integer> e : votes.entrySet()) {
            if (e.getKey() != ColorName.PIEL && e.getValue() > best) {
                best = e.getValue();
                winner = e.getKey();
            }
        }
        if (winner != null) {
            return winner;
        }
        return votes.containsKey(ColorName.PIEL) ? ColorName.PIEL : ColorName.GRIS;
    }

    static ColorName classify(int rgb) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        float hue = hsb[0] * 360f;
        float sat = hsb[1];
        float bri = hsb[2];

        // En una webcam el negro sale levantado y con tinte (calido: RGB ~ 73,53,62; magenta: ~ 78,42,78 con saturacion
        // 0.47): brillo 0.2-0.35 y saturacion hasta ~0.5. Un color oscuro de verdad (azul marino, vino, morado) pasa de 0.7.
        if (bri < 0.20f || (bri < 0.35f && sat < 0.55f)) {
            return ColorName.NEGRA;
        }
        if (sat < 0.15f) {
            return bri > 0.80f ? ColorName.BLANCA : ColorName.GRIS;
        }
        if (hue >= 5 && hue <= 35 && sat >= 0.20f && sat <= 0.65f && bri >= 0.45f && bri <= 0.95f) {
            return ColorName.PIEL;
        }
        if (hue < 15 || hue >= 345) {
            return ColorName.ROJA;
        }
        if (hue < 45) {
            return bri < 0.60f ? ColorName.CAFE : ColorName.NARANJA;
        }
        if (hue < 70) {
            return ColorName.AMARILLA;
        }
        if (hue < 165) {
            return ColorName.VERDE;
        }
        if (hue < 200) {
            return ColorName.TURQUESA;
        }
        if (hue < 260) {
            return ColorName.AZUL;
        }
        if (hue < 300) {
            return ColorName.MORADA;
        }
        return ColorName.ROSADA;
    }
}
