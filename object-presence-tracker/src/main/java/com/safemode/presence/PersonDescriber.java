package com.safemode.presence;

import java.awt.Color;
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
        VERDE("verde"), TURQUESA("turquesa"), AZUL("azul"), MORADA("morada"), ROSADA("rosada");

        final String label;

        ColorName(String label) {
            this.label = label;
        }
    }

    private static final int SAMPLES_PER_AXIS = 12;

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
        if (personCrop == null) {
            return null;
        }
        return "persona con camisa " + dominantTorsoColor(personCrop, excluded).label;
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

        ColorName winner = ColorName.GRIS;
        int best = -1;
        for (Map.Entry<ColorName, Integer> e : votes.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                winner = e.getKey();
            }
        }
        return winner;
    }

    static ColorName classify(int rgb) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        float hue = hsb[0] * 360f;
        float sat = hsb[1];
        float bri = hsb[2];

        // En una webcam el negro sale levantado y con tinte calido (ej. RGB ~ 73,53,62): brillo 0.2-0.3, saturacion baja.
        if (bri < 0.20f || (bri < 0.35f && sat < 0.45f)) {
            return ColorName.NEGRA;
        }
        if (sat < 0.15f) {
            return bri > 0.80f ? ColorName.BLANCA : ColorName.GRIS;
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
