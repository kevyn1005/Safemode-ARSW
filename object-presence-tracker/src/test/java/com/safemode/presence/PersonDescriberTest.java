package com.safemode.presence;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersonDescriberTest {

    private static BufferedImage solid(Color color, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    @Test
    void reconoceLosColoresBasicos() {
        assertEquals("persona con camisa roja", PersonDescriber.describe(solid(Color.RED, 50, 120)));
        assertEquals("persona con camisa negra", PersonDescriber.describe(solid(Color.BLACK, 50, 120)));
        assertEquals("persona con camisa blanca", PersonDescriber.describe(solid(Color.WHITE, 50, 120)));
        assertEquals("persona con camisa azul", PersonDescriber.describe(solid(Color.BLUE, 50, 120)));
        assertEquals("persona con camisa verde", PersonDescriber.describe(solid(Color.GREEN, 50, 120)));
    }

    @Test
    void soloMiraLaZonaDelTorsoNoLaCabezaNiLasPiernas() {
        BufferedImage img = new BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 100, 50);      // cabeza (0%-25%)
        g.setColor(Color.RED);
        g.fillRect(0, 50, 100, 60);     // torso (25%-55%)
        g.setColor(Color.GREEN);
        g.fillRect(0, 110, 100, 90);    // piernas
        g.dispose();

        assertEquals("persona con camisa roja", PersonDescriber.describe(img));
    }

    @Test
    void ignoraElObjetoQueLaPersonaLlevaEncimaAlDescribirLaRopa() {
        BufferedImage img = solid(Color.BLACK, 100, 200);   // camisa negra
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(20, 30, 40, 80);                        // mochila roja tapando parte del torso
        g.dispose();

        assertEquals("persona con camisa roja", PersonDescriber.describe(img),
                "sin excluir nada, la mochila domina el torso");
        assertEquals("persona con camisa negra", PersonDescriber.describe(img, new Rectangle(20, 30, 40, 80)),
                "excluyendo la zona de la mochila queda la camisa");
    }

    @Test
    void siElObjetoTapaTodoElTorsoIgualDaUnaDescripcion() {
        BufferedImage img = solid(Color.RED, 100, 200);
        assertEquals("persona con camisa roja", PersonDescriber.describe(img, new Rectangle(0, 0, 100, 200)));
    }

    @Test
    void elNegroDeUnaWebcamConTinteCalidoSigueSiendoNegro() {
        // valores medidos en una camiseta negra real capturada por webcam
        assertEquals("persona con camisa negra",
                PersonDescriber.describe(solid(new Color(73, 53, 62), 50, 120)));
        assertEquals("persona con camisa negra",
                PersonDescriber.describe(solid(new Color(44, 37, 50), 50, 120)));
    }

    @Test
    void unColorOscuroPeroSaturadoNoSeConfundeConNegro() {
        assertEquals("persona con camisa azul", PersonDescriber.describe(solid(new Color(20, 30, 90), 50, 120)));
        assertEquals("persona con camisa roja", PersonDescriber.describe(solid(new Color(100, 20, 25), 50, 120)));
    }

    @Test
    void sinRecorteNoHayDescripcion() {
        assertNull(PersonDescriber.describe(null));
    }

    @Test
    void unRecorteMinimoNoRompe() {
        assertEquals("persona con camisa roja", PersonDescriber.describe(solid(Color.RED, 1, 1)));
    }
}
