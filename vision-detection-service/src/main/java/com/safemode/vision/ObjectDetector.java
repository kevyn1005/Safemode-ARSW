package com.safemode.vision;

import ai.onnxruntime.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.*;

public class ObjectDetector {

    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.3f;
    private static final int NUM_CLASSES = 80;
    private static final int NUM_BOXES = 8400;
    private static final float NMS_IOU_THRESHOLD = 0.5f;

    // Clases COCO que te interesan para SafeMode
    private static final Map<Integer, String> RELEVANT_CLASSES = Map.of(
            0, "person",
            24, "backpack",
            26, "handbag",
            28, "suitcase"
    );

    private final OrtEnvironment env;
    private final OrtSession session;

    public ObjectDetector(String modelPath) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        this.session = env.createSession(modelPath, new OrtSession.SessionOptions());
    }

    public List<Detection> detect(BufferedImage frame) throws OrtException {
        Letterbox lb = letterbox(frame, INPUT_SIZE);
        float[] inputData = imageToCHWArray(lb.image);

        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(inputData), new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});

        String inputName = session.getInputNames().iterator().next();
        OrtSession.Result result = session.run(Map.of(inputName, inputTensor));

        float[][][] output = (float[][][]) result.get(0).getValue(); // [1][84][8400]

        return parseDetections(output, lb);
    }

    // Método de debug: top N detecciones SIN filtrar por umbral (todas las 80 clases)
    public void debugTopDetections(BufferedImage frame, int topN) throws OrtException {
        Letterbox lb = letterbox(frame, INPUT_SIZE);
        float[] inputData = imageToCHWArray(lb.image);

        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(inputData), new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});

        String inputName = session.getInputNames().iterator().next();
        OrtSession.Result result = session.run(Map.of(inputName, inputTensor));

        float[][][] output = (float[][][]) result.get(0).getValue();
        float[][] pred = output[0];

        System.out.println("Shape real: [" + pred.length + "][" + pred[0].length + "]");

        List<float[]> best = new ArrayList<>();
        for (int i = 0; i < pred[0].length; i++) {
            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = pred[4 + c][i];
                best.add(new float[]{score, c, i});
            }
        }
        best.sort((a, b) -> Float.compare(b[0], a[0]));

        System.out.println("Top " + topN + " detecciones (sin filtro de umbral):");
        for (int i = 0; i < topN; i++) {
            float[] d = best.get(i);
            int classIdx = (int) d[1];
            String className = RELEVANT_CLASSES.getOrDefault(classIdx, "clase#" + classIdx);
            System.out.println("  score=" + d[0] + " clase=" + className);
        }
    }

    // Método de debug: mejor score SOLO de tus 4 clases relevantes
    public void debugRelevantClassesOnly(BufferedImage frame) throws OrtException {
        Letterbox lb = letterbox(frame, INPUT_SIZE);
        float[] inputData = imageToCHWArray(lb.image);

        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(inputData), new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});

        String inputName = session.getInputNames().iterator().next();
        OrtSession.Result result = session.run(Map.of(inputName, inputTensor));

        float[][][] output = (float[][][]) result.get(0).getValue();
        float[][] pred = output[0];

        for (Map.Entry<Integer, String> entry : RELEVANT_CLASSES.entrySet()) {
            int classIdx = entry.getKey();
            String className = entry.getValue();
            float best = 0;
            for (int i = 0; i < pred[0].length; i++) {
                float score = pred[4 + classIdx][i];
                if (score > best) best = score;
            }
            System.out.println("  mejor score para " + className + " = " + best);
        }
    }

    // Redimensiona manteniendo proporción y rellena con gris (estándar de YOLO)
    private Letterbox letterbox(BufferedImage src, int targetSize) {
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min((float) targetSize / w, (float) targetSize / h);
        int newW = Math.round(w * scale);
        int newH = Math.round(h * scale);

        Image scaled = src.getScaledInstance(newW, newH, Image.SCALE_SMOOTH);
        BufferedImage padded = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = padded.createGraphics();
        g.setColor(new Color(114, 114, 114));
        g.fillRect(0, 0, targetSize, targetSize);
        int padX = (targetSize - newW) / 2;
        int padY = (targetSize - newH) / 2;
        g.drawImage(scaled, padX, padY, null);
        g.dispose();

        Letterbox result = new Letterbox();
        result.image = padded;
        result.scale = scale;
        result.padX = padX;
        result.padY = padY;
        return result;
    }

    // Convierte a formato CHW (channels-height-width) que espera YOLO/ONNX
    private float[] imageToCHWArray(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        float[] result = new float[3 * w * h];
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            result[i] = ((p >> 16) & 0xFF) / 255.0f;               // canal R
            result[w * h + i] = ((p >> 8) & 0xFF) / 255.0f;         // canal G
            result[2 * w * h + i] = (p & 0xFF) / 255.0f;            // canal B
        }
        return result;
    }

    // output[0] tiene forma [84][8400]:
    // filas 0-3 = x_center, y_center, width, height (en escala 0-640, sobre la imagen con letterbox)
    // filas 4-83 = score de cada una de las 80 clases COCO
    private List<Detection> parseDetections(float[][][] output, Letterbox lb) {
        List<Detection> detections = new ArrayList<>();
        float[][] pred = output[0]; // [84][8400]

        for (int i = 0; i < NUM_BOXES; i++) {
            int bestClass = -1;
            float bestScore = 0;
            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = pred[4 + c][i];
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }

            if (bestScore < CONFIDENCE_THRESHOLD) continue;
            if (!RELEVANT_CLASSES.containsKey(bestClass)) continue;

            float cx = pred[0][i], cy = pred[1][i], bw = pred[2][i], bh = pred[3][i];

            // Deshacer el padding y el escalado del letterbox para volver a coordenadas reales
            int x = (int) ((cx - bw / 2 - lb.padX) / lb.scale);
            int y = (int) ((cy - bh / 2 - lb.padY) / lb.scale);
            int w = (int) (bw / lb.scale);
            int h = (int) (bh / lb.scale);

            detections.add(new Detection(RELEVANT_CLASSES.get(bestClass), bestScore, x, y, w, h));
        }

        return nonMaxSuppression(detections);
    }

    // Elimina cajas duplicadas: de la misma clase y muy solapadas (YOLO genera varias por objeto).
    // Dos objetos distintos de la misma clase (ej. dos personas) se conservan si no se solapan.
    static List<Detection> nonMaxSuppression(List<Detection> input) {
        List<Detection> sorted = new ArrayList<>(input);
        sorted.sort((a, b) -> Float.compare(b.confidence(), a.confidence()));

        List<Detection> result = new ArrayList<>();
        boolean[] removed = new boolean[sorted.size()];

        for (int i = 0; i < sorted.size(); i++) {
            if (removed[i]) continue;
            Detection a = sorted.get(i);
            result.add(a);
            for (int j = i + 1; j < sorted.size(); j++) {
                if (removed[j]) continue;
                Detection b = sorted.get(j);
                if (a.className().equals(b.className()) && iou(a, b) > NMS_IOU_THRESHOLD) {
                    removed[j] = true;
                }
            }
        }
        return result;
    }

    private static float iou(Detection a, Detection b) {
        int x1 = Math.max(a.x(), b.x());
        int y1 = Math.max(a.y(), b.y());
        int x2 = Math.min(a.x() + a.width(), b.x() + b.width());
        int y2 = Math.min(a.y() + a.height(), b.y() + b.height());
        float inter = (float) Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float union = (float) a.width() * a.height() + (float) b.width() * b.height() - inter;
        return union <= 0 ? 0 : inter / union;
    }

    private static class Letterbox {
        BufferedImage image;
        float scale;
        int padX, padY;
    }

    public record Detection(String className, float confidence, int x, int y, int width, int height) {}
}