package com.safemode.vision;

import com.safemode.vision.PoseEstimator.Keypoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Prueba la lectura de la salida del modelo con datos sinteticos: no necesita el archivo .onnx. */
class PoseEstimatorParseTest {

    /** Salida falsa [56][3]: tres candidatos; el segundo (columna 1) es el de mayor confianza. */
    private static float[][] fakeOutput(float conf0, float conf1, float conf2) {
        float[][] pred = new float[56][3];
        pred[4][0] = conf0;
        pred[4][1] = conf1;
        pred[4][2] = conf2;
        for (int k = 0; k < 17; k++) {
            pred[5 + 3 * k][1] = 110 + k;   // x en escala 640
            pred[6 + 3 * k][1] = 60 + 2 * k; // y en escala 640
            pred[7 + 3 * k][1] = 0.9f;
        }
        return pred;
    }

    @Test
    void tomaLaPersonaConMasConfianzaYDeshaceElEscaladoYElRelleno() {
        // recorte escalado a la mitad (0.5) y con 10px de relleno a izquierda y 20 arriba
        Keypoint[] kp = PoseEstimator.parse(fakeOutput(0.4f, 0.9f, 0.5f), 0.5f, 10, 20);

        assertEquals(17, kp.length);
        assertEquals(200f, kp[0].x(), 0.001, "(110 - 10) / 0.5");
        assertEquals(80f, kp[0].y(), 0.001, "(60 - 20) / 0.5");
        assertEquals(216f, kp[8].x(), 0.001, "(118 - 10) / 0.5");
        assertEquals(0.9f, kp[5].confidence(), 0.001);
    }

    @Test
    void sinNingunaPersonaConfiableDevuelveNull() {
        assertNull(PoseEstimator.parse(fakeOutput(0.1f, 0.2f, 0.29f), 1f, 0, 0));
    }
}
