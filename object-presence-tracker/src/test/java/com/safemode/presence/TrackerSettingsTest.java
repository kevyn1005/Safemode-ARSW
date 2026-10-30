package com.safemode.presence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Las decisiones de arranque de ObjectPresenceTrackerTest: cuantas camaras, que modelo y cuantos hilos (ADR 0003, 3b). */
class TrackerSettingsTest {

    @Test
    void sinVariableOConUnValorInvalidoSeVigilaUnaCamara() {
        assertEquals(1, ObjectPresenceTrackerTest.parseCameraCount(null));
        assertEquals(1, ObjectPresenceTrackerTest.parseCameraCount("  "));
        assertEquals(1, ObjectPresenceTrackerTest.parseCameraCount("dos"));
        assertEquals(1, ObjectPresenceTrackerTest.parseCameraCount("0"));
        assertEquals(1, ObjectPresenceTrackerTest.parseCameraCount("-3"));
        assertEquals(1, ObjectPresenceTrackerTest.parseCameraCount("9999"));
    }

    @Test
    void elNumeroDeCamarasSeLeDeLaVariable() {
        assertEquals(1, ObjectPresenceTrackerTest.parseCameraCount("1"));
        assertEquals(2, ObjectPresenceTrackerTest.parseCameraCount(" 2 "));
        assertEquals(8, ObjectPresenceTrackerTest.parseCameraCount("8"));
    }

    @Test
    void conUnaCamaraSeUsaElModeloGrandeSiExiste() {
        assertTrue(ObjectPresenceTrackerTest.detectionModel(1, null, true).endsWith("yolov8m.onnx"));
        assertTrue(ObjectPresenceTrackerTest.detectionModel(1, null, false).endsWith("yolov8s.onnx"),
                "sin el modelo grande se usa el del repositorio, como siempre");
    }

    @Test
    void conVariasCamarasSeUsaElModeloLiviano() {
        assertTrue(ObjectPresenceTrackerTest.detectionModel(2, null, true).endsWith("yolov8s.onnx"));
        assertTrue(ObjectPresenceTrackerTest.detectionModel(3, null, false).endsWith("yolov8s.onnx"));
    }

    @Test
    void elModeloSePuedeForzarConLaVariable() {
        assertEquals("otro/modelo.onnx", ObjectPresenceTrackerTest.detectionModel(2, " otro/modelo.onnx ", true));
        assertTrue(ObjectPresenceTrackerTest.detectionModel(1, "  ", true).endsWith("yolov8m.onnx"), "una variable vacia no cuenta");
    }

    @Test
    void conUnaCamaraLosHilosSonLosDeSiempre() {
        assertEquals(0, ObjectPresenceTrackerTest.detectorThreads(1, null, 16), "0 = valor por defecto de ONNX Runtime");
    }

    @Test
    void conVariasCamarasLosNucleosSeReparten() {
        assertEquals(8, ObjectPresenceTrackerTest.detectorThreads(2, null, 16));
        assertEquals(4, ObjectPresenceTrackerTest.detectorThreads(2, null, 8));
        assertEquals(1, ObjectPresenceTrackerTest.detectorThreads(2, null, 1), "nunca menos de un hilo");
        assertEquals(1, ObjectPresenceTrackerTest.detectorThreads(8, null, 4));
    }

    @Test
    void losHilosSePuedenForzarYUnValorInvalidoSeIgnora() {
        assertEquals(6, ObjectPresenceTrackerTest.detectorThreads(2, "6", 16));
        assertEquals(1, ObjectPresenceTrackerTest.detectorThreads(2, "0", 16));
        assertEquals(8, ObjectPresenceTrackerTest.detectorThreads(2, "muchos", 16));
    }
}
