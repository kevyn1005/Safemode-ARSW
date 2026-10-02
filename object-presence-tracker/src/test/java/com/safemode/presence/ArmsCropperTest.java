package com.safemode.presence;

import com.safemode.vision.PoseEstimator;
import com.safemode.vision.PoseEstimator.Keypoint;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmsCropperTest {

    private static Keypoint[] pose() {
        Keypoint[] kp = new Keypoint[17];
        Arrays.fill(kp, new Keypoint(0, 0, 0));
        return kp;
    }

    private static BufferedImage person() {
        BufferedImage img = new BufferedImage(300, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.GRAY);
        g.fillRect(0, 0, 300, 600);
        g.dispose();
        return img;
    }

    @Test
    void unBrazoConfiableDaUnaImagenDe512DeAlto() {
        Keypoint[] kp = pose();
        kp[PoseEstimator.LEFT_ELBOW] = new Keypoint(50, 160, 0.9f);
        kp[PoseEstimator.LEFT_WRIST] = new Keypoint(50, 290, 0.9f);

        BufferedImage arms = ArmsCropper.armsCrop(person(), kp);

        assertNotNull(arms);
        assertEquals(512, arms.getHeight());
        // caja: margen 45 -> x 5..95 (90 de ancho), y 115..335 (220 de alto) -> 90 * 512 / 220 = 209
        assertEquals(209, arms.getWidth());
    }

    @Test
    void dosBrazosSeJuntanLadoALado() {
        Keypoint[] kp = pose();
        kp[PoseEstimator.LEFT_ELBOW] = new Keypoint(50, 160, 0.9f);
        kp[PoseEstimator.LEFT_WRIST] = new Keypoint(50, 290, 0.9f);
        kp[PoseEstimator.RIGHT_ELBOW] = new Keypoint(250, 160, 0.9f);
        kp[PoseEstimator.RIGHT_WRIST] = new Keypoint(250, 290, 0.9f);

        BufferedImage arms = ArmsCropper.armsCrop(person(), kp);

        assertNotNull(arms);
        assertEquals(512, arms.getHeight());
        assertTrue(arms.getWidth() > 209, "con dos brazos la imagen es mas ancha que con uno");
    }

    @Test
    void sinPuntosConfiablesNoHayImagen() {
        Keypoint[] kp = pose();
        kp[PoseEstimator.LEFT_ELBOW] = new Keypoint(50, 160, 0.2f);
        kp[PoseEstimator.LEFT_WRIST] = new Keypoint(50, 290, 0.9f);

        assertNull(ArmsCropper.armsCrop(person(), kp));
        assertNull(ArmsCropper.armsCrop(person(), pose()));
        assertNull(ArmsCropper.armsCrop(person(), null));
        assertNull(ArmsCropper.armsCrop(null, pose()));
    }

    @Test
    void laCajaSeRecortaAlBordeDeLaImagen() {
        Keypoint[] kp = pose();
        kp[PoseEstimator.LEFT_ELBOW] = new Keypoint(5, 20, 0.9f);   // casi en la esquina superior izquierda
        kp[PoseEstimator.LEFT_WRIST] = new Keypoint(10, 150, 0.9f);

        BufferedImage arms = ArmsCropper.armsCrop(person(), kp);

        assertNotNull(arms, "una caja que se sale de la imagen se recorta, no falla");
        assertEquals(512, arms.getHeight());
    }
}
