package com.safemode.presence;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Las cuatro senales del modelo de retiro ({@link RegionFingerprint#signals}): las dos primeras tienen que ser exactamente las de
 * {@link RegionFingerprint#compare} (que no se toco), las otras dos son nuevas.
 */
class RegionFingerprintSignalsTest {

    // la zona de la "mochila": x 100-300, y 100-260
    private static final int X = 100;
    private static final int Y = 100;
    private static final int W = 200;
    private static final int H = 160;

    private static BufferedImage flat(int gray) {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(gray, gray, gray));
        g.fillRect(0, 0, 800, 600);
        g.dispose();
        return image;
    }

    private static BufferedImage paint(BufferedImage image, int gray, int x, int y, int w, int h) {
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(gray, gray, gray));
        g.fillRect(x, y, w, h);
        g.dispose();
        return image;
    }

    private static RegionFingerprint zoneOf(BufferedImage frame) {
        RegionFingerprint fingerprint = RegionFingerprint.of(frame, X, Y, W, H);
        assertNotNull(fingerprint);
        return fingerprint;
    }

    @Test
    void lasDosPrimerasSenalesSonExactamenteLasDeCompare() {
        RegionFingerprint atRest = zoneOf(paint(flat(240), 60, X, Y, W, H));
        List<RegionFingerprint> scenes = List.of(
                zoneOf(paint(flat(240), 60, X, Y, W, H)), // igual
                zoneOf(flat(240)), // la mochila ya no esta
                zoneOf(paint(paint(flat(240), 60, X, Y, W, H), 120, X, Y, 90, H)), // cambio a medias
                zoneOf(paint(flat(240), 60, X, Y, W, H / 2))); // la mochila a medias
        List<List<Rectangle>> coverings = List.of(
                List.of(),
                List.of(new Rectangle(220, 0, 280, 600)), // una persona tapa el ultimo 40 %
                List.of(new Rectangle(90, 0, 400, 600)), // tapa casi todo
                List.of(new Rectangle(0, 0, 800, 600))); // tapa todo

        for (RegionFingerprint now : scenes) {
            for (List<Rectangle> covered : coverings) {
                RegionFingerprint.Comparison comparison = atRest.compare(now, covered);
                RegionFingerprint.RemovalSignals signals = atRest.signals(now, covered);
                assertEquals(comparison.changedFraction(), signals.changedFraction(), 0.0);
                assertEquals(comparison.visibleFraction(), signals.visibleFraction(), 0.0);
            }
        }
    }

    @Test
    void sinNingunCambioLasCuatroSenalesSonCeroSalvoLoVisible() {
        RegionFingerprint atRest = zoneOf(paint(flat(240), 60, X, Y, W, H));

        RegionFingerprint.RemovalSignals signals = atRest.signals(zoneOf(paint(flat(240), 60, X, Y, W, H)), List.of());

        assertEquals(0.0, signals.changedFraction(), 0.0);
        assertEquals(1.0, signals.visibleFraction(), 0.0);
        assertEquals(0.0, signals.changedSoft(), 0.0);
        assertEquals(0.0, signals.meanAbsDiff(), 0.0);
    }

    @Test
    void unCambioDeLuzSuaveSoloSeVeEnLasSenalesSuaves() {
        // el brillo baja 30 niveles en toda la zona: mas que el nivel suave (25) pero menos que el claro (45)
        RegionFingerprint atRest = zoneOf(flat(200));

        RegionFingerprint.RemovalSignals signals = atRest.signals(zoneOf(flat(170)), List.of());

        assertEquals(0.0, signals.changedFraction(), 0.0, "30 niveles no es un cambio claro");
        assertEquals(1.0, signals.changedSoft(), 0.0, "pero si es un cambio suave en todos los puntos");
        assertEquals(30 / 255.0, signals.meanAbsDiff(), 1e-12);
    }

    @Test
    void laDiferenciaMediaSoloCuentaLosPuntosQueSePudieronComparar() {
        RegionFingerprint atRest = zoneOf(flat(200));
        // la mitad izquierda de la zona (x 100-200) cambia 100 niveles
        RegionFingerprint now = zoneOf(paint(flat(200), 100, X, Y, 100, H));

        RegionFingerprint.RemovalSignals all = atRest.signals(now, List.of());
        RegionFingerprint.RemovalSignals withoutChangedHalf = atRest.signals(now, List.of(new Rectangle(0, 0, 200, 600)));

        assertEquals(0.5, all.changedFraction(), 1e-12);
        assertEquals(0.5 * 100 / 255.0, all.meanAbsDiff(), 1e-12);
        assertEquals(0.0, withoutChangedHalf.changedFraction(), 0.0, "la mitad que cambio estaba tapada por una persona");
        assertEquals(0.0, withoutChangedHalf.meanAbsDiff(), 0.0);
        assertEquals(0.5, withoutChangedHalf.visibleFraction(), 1e-12);
    }

    @Test
    void siTodaLaZonaEstaTapadaNoSePuedeConcluirNada() {
        RegionFingerprint atRest = zoneOf(flat(200));

        RegionFingerprint.RemovalSignals signals = atRest.signals(zoneOf(flat(50)), List.of(new Rectangle(0, 0, 800, 600)));

        assertEquals(0.0, signals.visibleFraction(), 0.0);
        assertEquals(0.0, signals.changedFraction(), 0.0);
        assertEquals(0.0, signals.changedSoft(), 0.0);
        assertEquals(0.0, signals.meanAbsDiff(), 0.0);
    }
}
