package com.safemode.presence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las senales del modelo de retiro, sobre todo las dos de secuencia ({@code covered_frames} y {@code last_visible_x1}): a mano con una
 * historia chica y contra los casos que genera {@code ml/removal_features.py} (la misma definicion en Python).
 */
class RemovalFeaturesTest {

    private static RegionFingerprint.RemovalSignals signals(double x1, double x2) {
        return new RegionFingerprint.RemovalSignals(x1, x2, 0.5, 0.25);
    }

    @Test
    void elCatalogoTieneLasOchoSenalesEnSuOrden() {
        assertEquals(List.of("x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff", "x5_frame_changed", "frames_unseen",
                "covered_frames", "last_visible_x1"), RemovalFeatures.CATALOG);
    }

    @Test
    void lasSenalesDeSecuenciaSiguenLaHistoriaDeLaRacha() {
        RemovalFeatures.Run run = new RemovalFeatures.Run();

        // k=1 tapada, k=2 tapada, k=3 se ve y cambio 0.44, k=4 vuelve a taparse, k=5 se ve y cambio 0.9
        double[] k1 = run.next(signals(0.0, 0.20), 0.1, 1);
        double[] k2 = run.next(signals(0.0, 0.10), 0.1, 2);
        double[] k3 = run.next(signals(0.44, 0.90), 0.1, 3);
        double[] k4 = run.next(signals(0.0, 0.00), 0.1, 4);
        double[] k5 = run.next(signals(0.90, 1.00), 0.1, 5);

        int covered = RemovalFeatures.CATALOG.indexOf("covered_frames");
        int lastVisible = RemovalFeatures.CATALOG.indexOf("last_visible_x1");
        assertEquals(1, k1[covered]);
        assertEquals(2, k2[covered]);
        assertEquals(0, k3[covered], "al verse la zona, la cuenta de frames tapados vuelve a 0");
        assertEquals(1, k4[covered]);
        assertEquals(0, k5[covered]);
        assertEquals(0.0, k1[lastVisible], "todavia no se vio la zona");
        assertEquals(0.0, k2[lastVisible]);
        assertEquals(0.44, k3[lastVisible]);
        assertEquals(0.44, k4[lastVisible], "mientras esta tapada se recuerda lo ultimo que se vio");
        assertEquals(0.90, k5[lastVisible]);
        assertEquals(4, k4[RemovalFeatures.CATALOG.indexOf("frames_unseen")]);
    }

    @Test
    void lasSenalesBasicasSeCopianTalCual() {
        // x2 = 0.8: la zona se ve, asi que no cuenta como tapada y last_visible_x1 es el x1 de este mismo frame
        double[] features = new RemovalFeatures.Run().next(new RegionFingerprint.RemovalSignals(0.1, 0.8, 0.3, 0.4), 0.5, 7);

        assertArrayEquals(new double[]{0.1, 0.8, 0.3, 0.4, 0.5, 7, 0, 0.1}, features, 0.0);
    }

    @Test
    void siElCuadroNoSePudoMedirSeUsaCeroEnX5() {
        double[] features = new RemovalFeatures.Run().next(signals(0.3, 0.8), -1, 2);

        assertEquals(0.0, features[RemovalFeatures.CATALOG.indexOf("x5_frame_changed")]);
    }

    @Test
    void javaCalculaLoMismoQuePythonEnCuarentaHistorias() throws IOException {
        String text;
        try (InputStream in = RemovalFeaturesTest.class.getResourceAsStream("/removal-model/features.cases.csv")) {
            assertNotNull(in, "falta features.cases.csv (python object-presence-tracker/ml/export_model.py toy)");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> lines = text.lines().toList();
        String currentCase = null;
        RemovalFeatures.Run run = null;
        int checked = 0;
        int cases = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",");
            if (!cells[0].equals(currentCase)) {
                currentCase = cells[0];
                run = new RemovalFeatures.Run();
                cases++;
            }
            RegionFingerprint.RemovalSignals s = new RegionFingerprint.RemovalSignals(Double.parseDouble(cells[2]),
                    Double.parseDouble(cells[3]), Double.parseDouble(cells[4]), Double.parseDouble(cells[5]));
            double[] actual = run.next(s, Double.parseDouble(cells[6]), Integer.parseInt(cells[1]));
            for (int i = 0; i < RemovalFeatures.CATALOG.size(); i++) {
                assertEquals(Double.parseDouble(cells[7 + i]), actual[i], 1e-12,
                        "caso " + currentCase + " k=" + cells[1] + " senal " + RemovalFeatures.CATALOG.get(i));
            }
            checked++;
        }
        assertEquals(40, cases);
        assertTrue(checked >= 40, "se esperaban muchos pasos de comparacion");
    }
}
