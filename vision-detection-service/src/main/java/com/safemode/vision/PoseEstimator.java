package com.safemode.vision;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.Map;

/**
 * Ubica los 17 puntos del cuerpo (formato COCO: hombros, caderas, rodillas...) de la
 * persona mas visible en una imagen, usando un modelo YOLOv8-pose en ONNX. Se pensó
 * para correrse sobre el recorte de una sola persona, una vez por evento (no por frame).
 */
public class PoseEstimator implements AutoCloseable {

    public static final int LEFT_SHOULDER = 5;
    public static final int RIGHT_SHOULDER = 6;
    public static final int LEFT_HIP = 11;
    public static final int RIGHT_HIP = 12;

    private static final int INPUT_SIZE = 640;
    private static final int NUM_KEYPOINTS = 17;
    private static final float MIN_PERSON_CONFIDENCE = 0.3f;

    /** Punto del cuerpo en coordenadas de la imagen analizada; confidence es la visibilidad estimada (0-1). */
    public record Keypoint(float x, float y, float confidence) {}

    private final OrtEnvironment env;
    private final OrtSession session;

    public PoseEstimator(String modelPath) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        this.session = env.createSession(modelPath, new OrtSession.SessionOptions());
    }

    /**
     * Devuelve los 17 puntos de la persona con mayor confianza, en coordenadas de
     * {@code personCrop}, o {@code null} si no se detecto ninguna persona.
     */
    public Keypoint[] estimate(BufferedImage personCrop) throws OrtException {
        int w = personCrop.getWidth();
        int h = personCrop.getHeight();
        float scale = Math.min((float) INPUT_SIZE / w, (float) INPUT_SIZE / h);
        int newW = Math.max(1, Math.round(w * scale));
        int newH = Math.max(1, Math.round(h * scale));
        int padX = (INPUT_SIZE - newW) / 2;
        int padY = (INPUT_SIZE - newH) / 2;

        BufferedImage padded = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = padded.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(114, 114, 114));
        g.fillRect(0, 0, INPUT_SIZE, INPUT_SIZE);
        g.drawImage(personCrop, padX, padY, newW, newH, null);
        g.dispose();

        int[] pixels = padded.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        int plane = INPUT_SIZE * INPUT_SIZE;
        float[] input = new float[3 * plane];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            input[i] = ((p >> 16) & 0xFF) / 255.0f;
            input[plane + i] = ((p >> 8) & 0xFF) / 255.0f;
            input[2 * plane + i] = (p & 0xFF) / 255.0f;
        }

        String inputName = session.getInputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
            float[][][] output = (float[][][]) result.get(0).getValue(); // [1][56][8400]
            return parse(output[0], scale, padX, padY);
        }
    }

    /**
     * pred es [56][n]: filas 0-3 = caja, fila 4 = confianza de persona, filas 5-55 = 17 puntos
     * como (x, y, visibilidad) en la escala 640x640 con letterbox. Se toma la columna de mayor
     * confianza y se deshace el escalado y el relleno para volver a coordenadas del recorte.
     */
    static Keypoint[] parse(float[][] pred, float scale, int padX, int padY) {
        int best = -1;
        float bestConf = MIN_PERSON_CONFIDENCE;
        for (int i = 0; i < pred[4].length; i++) {
            if (pred[4][i] >= bestConf) {
                bestConf = pred[4][i];
                best = i;
            }
        }
        if (best < 0) {
            return null;
        }

        Keypoint[] keypoints = new Keypoint[NUM_KEYPOINTS];
        for (int k = 0; k < NUM_KEYPOINTS; k++) {
            float x = (pred[5 + 3 * k][best] - padX) / scale;
            float y = (pred[6 + 3 * k][best] - padY) / scale;
            keypoints[k] = new Keypoint(x, y, pred[7 + 3 * k][best]);
        }
        return keypoints;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
