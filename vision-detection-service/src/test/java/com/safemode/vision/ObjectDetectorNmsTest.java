package com.safemode.vision;

import com.safemode.vision.ObjectDetector.Detection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectDetectorNmsTest {

    private static Detection det(String cls, float conf, int x, int y, int w, int h) {
        return new Detection(cls, conf, x, y, w, h);
    }

    @Test
    void dosPersonasQueNoSeSolapanSeConservanAmbas() {
        List<Detection> result = ObjectDetector.nonMaxSuppression(List.of(
                det("person", 0.9f, 0, 0, 100, 300),
                det("person", 0.8f, 500, 0, 100, 300)));

        assertEquals(2, result.size(), "dos personas distintas no deben fusionarse en una sola");
    }

    @Test
    void cajasMuySolapadasDelMismoObjetoQuedanEnUna() {
        List<Detection> result = ObjectDetector.nonMaxSuppression(List.of(
                det("backpack", 0.6f, 102, 100, 200, 200),
                det("backpack", 0.9f, 100, 100, 200, 200),
                det("backpack", 0.4f, 98, 104, 200, 196)));

        assertEquals(1, result.size());
        assertEquals(0.9f, result.get(0).confidence(), "debe quedarse la de mayor confianza");
    }

    @Test
    void clasesDistintasNoSeEliminanAunqueSeSolapen() {
        List<Detection> result = ObjectDetector.nonMaxSuppression(List.of(
                det("person", 0.9f, 0, 0, 300, 600),
                det("backpack", 0.8f, 20, 200, 200, 200)));

        assertEquals(2, result.size(), "una mochila sobre una persona no debe borrar a la persona");
    }

    @Test
    void laMismaMochilaDetectadaComoDosClasesDeBolsoQuedaEnUna() {
        List<Detection> result = ObjectDetector.nonMaxSuppression(List.of(
                det("handbag", 0.42f, 1720, 350, 160, 150),
                det("backpack", 0.55f, 1722, 349, 158, 152)));

        assertEquals(1, result.size(), "una sola maleta no debe salir como dos objetos");
        assertEquals("backpack", result.get(0).className(), "gana la clase de mayor confianza");
    }

    @Test
    void bolsosDeClasesDistintasEnLugaresDistintosSeConservan() {
        List<Detection> result = ObjectDetector.nonMaxSuppression(List.of(
                det("backpack", 0.8f, 100, 100, 150, 150),
                det("suitcase", 0.7f, 900, 100, 150, 150)));

        assertEquals(2, result.size());
    }

    @Test
    void cajasDeLaMismaClaseConPocoSolapeSonObjetosDistintos() {
        // IoU aprox. 0.11: dos personas pegadas, no un duplicado
        List<Detection> result = ObjectDetector.nonMaxSuppression(List.of(
                det("person", 0.9f, 0, 0, 100, 300),
                det("person", 0.8f, 80, 0, 100, 300)));

        assertEquals(2, result.size());
    }

    @Test
    void sinDeteccionesDevuelveListaVacia() {
        assertTrue(ObjectDetector.nonMaxSuppression(List.of()).isEmpty());
    }
}
