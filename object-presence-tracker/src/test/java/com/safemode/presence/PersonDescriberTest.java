package com.safemode.presence;

import com.safemode.vision.PoseEstimator;
import com.safemode.vision.PoseEstimator.Keypoint;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void elNegroConTinteMagentaDeUnaWebcamSigueSiendoNegro() {
        // valores medidos en una camiseta negra real: RGB (78,42,78), saturacion 0.47, brillo 0.32
        assertEquals("persona con camisa negra", PersonDescriber.describe(solid(new Color(78, 42, 78), 50, 120)));
        assertEquals("persona con camisa negra", PersonDescriber.describe(solid(new Color(70, 45, 60), 50, 120)));
    }

    @Test
    void unMoradoOVinoOscuroDeVerdadNoSeConfundeConNegro() {
        assertEquals("persona con camisa morada", PersonDescriber.describe(solid(new Color(60, 20, 80), 50, 120)));
        assertEquals("persona con camisa roja", PersonDescriber.describe(solid(new Color(70, 15, 20), 50, 120)));
    }

    @Test
    void unColorOscuroPeroSaturadoNoSeConfundeConNegro() {
        assertEquals("persona con camisa azul", PersonDescriber.describe(solid(new Color(20, 30, 90), 50, 120)));
        assertEquals("persona con camisa roja", PersonDescriber.describe(solid(new Color(100, 20, 25), 50, 120)));
    }

    /** Recorte ancho (150x120) con el torso rojo a la izquierda y fondo blanco: la franja central cae en el fondo. */
    private static BufferedImage personaConTorsoRojoALaIzquierda() {
        BufferedImage img = solid(Color.WHITE, 150, 120);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(5, 30, 40, 40);
        g.dispose();
        return img;
    }

    private static Keypoint[] pose(float confidence, float lsX, float lsY, float rsX, float rsY,
                                   float lhX, float lhY, float rhX, float rhY) {
        Keypoint[] kp = new Keypoint[17];
        java.util.Arrays.fill(kp, new Keypoint(0, 0, 0));
        kp[PoseEstimator.LEFT_SHOULDER] = new Keypoint(lsX, lsY, confidence);
        kp[PoseEstimator.RIGHT_SHOULDER] = new Keypoint(rsX, rsY, confidence);
        kp[PoseEstimator.LEFT_HIP] = new Keypoint(lhX, lhY, confidence);
        kp[PoseEstimator.RIGHT_HIP] = new Keypoint(rhX, rhY, confidence);
        return kp;
    }

    @Test
    void conPuntosDelCuerpoLeeElTorsoAunqueLaFranjaCentralCaigaEnElFondo() {
        BufferedImage img = personaConTorsoRojoALaIzquierda();
        Keypoint[] torso = pose(0.9f, 8, 32, 42, 32, 10, 68, 40, 68);

        assertEquals("persona con camisa roja", PersonDescriber.describe(img, null, torso));
        assertNotEquals("persona con camisa roja", PersonDescriber.describe(img),
                "sin los puntos, la franja fija lee el fondo blanco");
    }

    @Test
    void conPuntosDeBajaConfianzaUsaLaFranjaCentralComoAntes() {
        BufferedImage img = personaConTorsoRojoALaIzquierda();
        Keypoint[] dudosos = pose(0.2f, 8, 32, 42, 32, 10, 68, 40, 68);

        assertEquals(PersonDescriber.describe(img), PersonDescriber.describe(img, null, dudosos));
    }

    @Test
    void siNoSeVenLasCaderasElTorsoSeEstimaHaciaAbajoDesdeLosHombros() {
        BufferedImage img = personaConTorsoRojoALaIzquierda();
        Keypoint[] sinCaderas = pose(0.9f, 8, 32, 42, 32, 0, 0, 0, 0);
        sinCaderas[PoseEstimator.LEFT_HIP] = new Keypoint(0, 0, 0.1f);
        sinCaderas[PoseEstimator.RIGHT_HIP] = new Keypoint(0, 0, 0.1f);

        assertEquals("persona con camisa roja", PersonDescriber.describe(img, null, sinCaderas));
    }

    @Test
    void ignoraElTonoDePielDeUnBrazoQueCruzaElPecho() {
        BufferedImage img = solid(Color.BLACK, 100, 200);   // camisa negra
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 140, 110));               // antebrazo: mas area que la camisa visible en la banda del torso
        g.fillRect(0, 45, 100, 45);
        g.dispose();

        assertEquals("persona con camisa negra", PersonDescriber.describe(img));
    }

    @Test
    void siTodoLoQueSeVeEsPielSeDescribeComoColorPiel() {
        assertEquals("persona con camisa color piel",
                PersonDescriber.describe(solid(new Color(200, 140, 110), 100, 200)));
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
