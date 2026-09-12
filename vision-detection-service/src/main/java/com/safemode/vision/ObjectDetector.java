package com.safemode.vision;

import ai.onnxruntime.*;
import java.awt.image.BufferedImage;
import java.awt.Image;
import java.nio.FloatBuffer;
import java.util.*;

public class ObjectDetector {

    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.3f;
    private static final int NUM_CLASSES = 80;
    private static final int NUM_BOXES = 8400;

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
        int origW = frame.getWidth();
        int origH = frame.getHeight();

        BufferedImage resized = resize(frame, INPUT_SIZE, INPUT_SIZE);
        float[] inputData = imageToCHWArray(resized);

        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(inputData), new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});

        String inputName = session.getInputNames().iterator().next();
        OrtSession.Result result = session.run(Map.of(inputName, inputTensor));

        float[][][] output = (float[][][]) result.get(0).getValue(); // [1][84][8400]

        return parseDetections(output, origW, origH);
    }

    // Método de debug: muestra las mejores detecciones SIN filtrar por umbral,
    // para diagnosticar si el modelo "ve" algo aunque sea con poca confianza.
    public void debugTopDetections(BufferedImage frame, int topN) throws OrtException {
        BufferedImage resized = resize(frame, INPUT_SIZE, INPUT_SIZE);
        float[] inputData = imageToCHWArray(resized);

        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(inputData), new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});

        String inputName = session.getInputNames().iterator().next();
        OrtSession.Result result = session.run(Map.of(inputName, inputTensor));

        float[][][] output = (float[][][]) result.get(0).getValue();
        float[][] pred = output[0];

        System.out.println("Shape real: [" + pred.length + "][" + pred[0].length + "]");

        List<float[]> best = new ArrayList<>(); // cada elemento: [score, classIdx, boxIdx]
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

    private BufferedImage resize(BufferedImage original, int w, int h) {
        Image scaled = original.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        resized.getGraphics().drawImage(scaled, 0, 0, null);
        return resized;
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
    // filas 0-3 = x_center, y_center, width, height (en escala 0-640)
    // filas 4-83 = score de cada una de las 80 clases COCO
    private List<Detection> parseDetections(float[][][] output, int origW, int origH) {
        List<Detection> detections = new ArrayList<>();
        float[][] pred = output[0]; // [84][8400]

        float scaleX = (float) origW / INPUT_SIZE;
        float scaleY = (float) origH / INPUT_SIZE;

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

            int x = (int) ((cx - bw / 2) * scaleX);
            int y = (int) ((cy - bh / 2) * scaleY);
            int w = (int) (bw * scaleX);
            int h = (int) (bh * scaleY);

            detections.add(new Detection(RELEVANT_CLASSES.get(bestClass), bestScore, x, y, w, h));
        }

        return nonMaxSuppression(detections);
    }

    // Elimina cajas duplicadas/solapadas de la misma clase (YOLO genera muchas por objeto)
    private List<Detection> nonMaxSuppression(List<Detection> input) {
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
                if (a.className().equals(b.className()) && iou(a, b) > 0.45f) {
                    removed[j] = true;
                }
            }
        }
        return result;
    }

    private float iou(Detection a, Detection b) {
        int x1 = Math.max(a.x(), b.x());
        int y1 = Math.max(a.y(), b.y());
        int x2 = Math.min(a.x() + a.width(), b.x() + b.width());
        int y2 = Math.min(a.y() + a.height(), b.y() + b.height());

        int interArea = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        int areaA = a.width() * a.height();
        int areaB = b.width() * b.height();

        return (float) interArea / (areaA + areaB - interArea);
    }

    public record Detection(String className, float confidence, int x, int y, int width, int height) {}
}