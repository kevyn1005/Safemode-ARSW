package com.safemode.presence;

import com.safemode.vision.PoseEstimator;
import com.safemode.vision.PoseEstimator.Keypoint;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Recorta los antebrazos de una persona a partir de sus codos y munecas (puntos de pose) y los
 * junta lado a lado en una sola imagen. Sirve para preguntarle a un modelo de vision solo por los
 * tatuajes: en el recorte completo el brazo es diminuto y el modelo responde "no se ve", y este
 * recorte tampoco incluye la cara.
 */
final class ArmsCropper {

    private static final float MIN_KEYPOINT_CONFIDENCE = 0.4f;
    private static final int PANEL_HEIGHT_PX = 512;
    private static final int MIN_BOX_SIDE_PX = 24;

    private ArmsCropper() {
    }

    /** Imagen con uno o dos antebrazos lado a lado (512 px de alto), o null si no hay ninguno confiable. */
    static BufferedImage armsCrop(BufferedImage personCrop, Keypoint[] pose) {
        if (personCrop == null || pose == null || pose.length <= PoseEstimator.RIGHT_WRIST) {
            return null;
        }
        List<BufferedImage> arms = new ArrayList<>();
        addArm(arms, personCrop, pose[PoseEstimator.LEFT_ELBOW], pose[PoseEstimator.LEFT_WRIST]);
        addArm(arms, personCrop, pose[PoseEstimator.RIGHT_ELBOW], pose[PoseEstimator.RIGHT_WRIST]);
        if (arms.isEmpty()) {
            return null;
        }

        int[] widths = new int[arms.size()];
        int totalWidth = 0;
        for (int i = 0; i < arms.size(); i++) {
            widths[i] = Math.max(1, arms.get(i).getWidth() * PANEL_HEIGHT_PX / arms.get(i).getHeight());
            totalWidth += widths[i];
        }
        BufferedImage composite = new BufferedImage(totalWidth, PANEL_HEIGHT_PX, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int x = 0;
        for (int i = 0; i < arms.size(); i++) {
            g.drawImage(arms.get(i), x, 0, widths[i], PANEL_HEIGHT_PX, null);
            x += widths[i];
        }
        g.dispose();
        return composite;
    }

    private static void addArm(List<BufferedImage> arms, BufferedImage crop, Keypoint elbow, Keypoint wrist) {
        if (elbow.confidence() < MIN_KEYPOINT_CONFIDENCE || wrist.confidence() < MIN_KEYPOINT_CONFIDENCE) {
            return;
        }
        int x1 = (int) Math.min(elbow.x(), wrist.x());
        int x2 = (int) Math.max(elbow.x(), wrist.x());
        int y1 = (int) Math.min(elbow.y(), wrist.y());
        int y2 = (int) Math.max(elbow.y(), wrist.y());
        // margen para que entre el ancho del brazo (los puntos estan sobre el eje, no en los bordes)
        int margin = Math.max(40, (int) (0.35 * Math.max(x2 - x1, y2 - y1)));
        Rectangle box = new Rectangle(x1 - margin, y1 - margin, (x2 - x1) + 2 * margin, (y2 - y1) + 2 * margin)
                .intersection(new Rectangle(0, 0, crop.getWidth(), crop.getHeight()));
        if (box.isEmpty() || box.width < MIN_BOX_SIDE_PX || box.height < MIN_BOX_SIDE_PX) {
            return;
        }
        BufferedImage copy = new BufferedImage(box.width, box.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(crop.getSubimage(box.x, box.y, box.width, box.height), 0, 0, null);
        g.dispose();
        arms.add(copy);
    }
}
