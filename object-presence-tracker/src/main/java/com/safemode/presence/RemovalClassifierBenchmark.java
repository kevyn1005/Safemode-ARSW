package com.safemode.presence;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

// Script de medicion manual (no es JUnit): meta M5, cuanto tarda la DECISION del modelo de retiro por cada frame en que el detector no ve un
// objeto en reposo, con fotogramas del tamano real (1280x1080). Incluye todo lo que se hace en ese frame: la huella de la zona, la comparacion
// con la de reposo, las senales de secuencia, la inferencia y, SOLO si el modelo usa x5, el cambio del resto del cuadro (igual que el tracker
// real). No incluye YOLO (eso mide DetectionBenchmark) ni la huella de reposo, que se calcula una vez por frame mientras el objeto se ve
// quieto. Meta: menos de 5 ms.
//   mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.RemovalClassifierBenchmark"
// Argumentos opcionales: repeticiones (por defecto 2000) y ruta de un modelo. Sin ruta mide el modelo entrenado del proyecto
// (src/main/resources/removal-model/model.json) si existe; si no existe, mide DOS MODELOS DE JUGUETE (pesos inventados; solo sirven para medir
// tiempos, no para evaluar nada): el del plan (4 senales, MLP 4-6-1, 37 parametros) y el maximo previsto (8 senales, 6 neuronas ocultas).
public class RemovalClassifierBenchmark {
    private static final double GOAL_MS = 5.0;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 1080;

    public static void main(String[] args) throws Exception {
        int repetitions = args.length > 0 ? Integer.parseInt(args[0].trim()) : 2000;
        Map<String, RemovalClassifier> models = new LinkedHashMap<>();
        if (args.length > 1) {
            RemovalClassifier loaded = RemovalClassifier.load(Path.of(args[1].trim()));
            models.put((loaded.isToy() ? "modelo de juguete " : "modelo ") + args[1].trim(), loaded);
        } else {
            RemovalClassifier trained = projectModel();
            if (trained != null) {
                models.put("modelo entrenado del proyecto (src/main/resources/removal-model/model.json)", trained);
            } else {
                models.put("JUGUETE del plan: 4 senales, MLP 4-6-1 (37 parametros)", toyMlp(List.of(
                        "x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff")));
                models.put("JUGUETE maximo previsto: 8 senales, MLP 8-6-1", toyMlp(RemovalFeatures.CATALOG));
            }
        }

        BufferedImage atRest = texture(1);
        BufferedImage now = texture(2);
        Rectangle zone = new Rectangle(420, 300, 320, 340);
        List<Rectangle> people = List.of(new Rectangle(100, 40, 260, 900), new Rectangle(800, 80, 300, 880));
        RegionFingerprint restZone = RegionFingerprint.of(atRest, zone.x, zone.y, zone.width, zone.height);
        RegionFingerprint restFrame = RegionFingerprint.of(atRest, 0, 0, WIDTH, HEIGHT);

        System.out.println("=== Medicion de la decision del modelo de retiro (meta M5: menos de " + GOAL_MS + " ms) ===");
        System.out.println("Fotograma: " + WIDTH + "x" + HEIGHT + " | zona del objeto: " + zone.width + "x" + zone.height
                + " | personas: " + people.size() + " | repeticiones: " + repetitions + " (mas 200 de calentamiento)");

        List<String> verdicts = new ArrayList<>();
        for (Map.Entry<String, RemovalClassifier> entry : models.entrySet()) {
            RemovalClassifier classifier = entry.getValue();
            System.out.println("\n--- " + entry.getKey() + " ---");
            System.out.println(classifier.describe());
            boolean needsFrameChange = classifier.usesFeature("x5_frame_changed");
            double sink = 0;
            double[] total = new double[repetitions];
            double[] inferenceOnly = new double[repetitions];
            for (int i = -200; i < repetitions; i++) { // las primeras 200 son de calentamiento (JIT) y no se cuentan
                long t0 = System.nanoTime();
                RegionFingerprint nowZone = RegionFingerprint.of(now, zone.x, zone.y, zone.width, zone.height);
                RegionFingerprint.RemovalSignals signals = restZone.signals(nowZone, people);
                double frameChanged = needsFrameChange ? RemovalSampleLog.frameChanged(restFrame, now, zone, people) : -1;
                double[] catalog = new RemovalFeatures.Run().next(signals, frameChanged, 3);
                long t1 = System.nanoTime();
                sink += classifier.probabilityFromCatalog(catalog);
                long t2 = System.nanoTime();
                if (i >= 0) {
                    total[i] = (t2 - t0) / 1e6;
                    inferenceOnly[i] = (t2 - t1) / 1e6;
                }
            }
            report("Decision completa (senales + inferencia)", total);
            report("Solo la inferencia del modelo", inferenceOnly);
            double p95 = percentile(total, 95);
            verdicts.add(String.format(Locale.ROOT, "%-62s p95 = %.3f ms -> %s la meta de %.1f ms (suma de control: %.3f)",
                    entry.getKey(), p95, p95 < GOAL_MS ? "CUMPLE" : "NO CUMPLE", GOAL_MS, sink));
        }
        System.out.println("\n=== Resultado ===");
        verdicts.forEach(System.out::println);
    }

    /** El modelo entrenado que viaja con el proyecto, o null si todavia no existe. */
    private static RemovalClassifier projectModel() throws Exception {
        try (InputStream in = RemovalClassifierBenchmark.class.getResourceAsStream(RemovalModelLoader.RESOURCE)) {
            return in == null ? null : RemovalClassifier.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void report(String name, double[] ms) {
        System.out.println(String.format(Locale.ROOT, "%-42s media %.3f ms | mediana %.3f | p95 %.3f | p99 %.3f | max %.3f",
                name, Arrays.stream(ms).average().orElse(0), percentile(ms, 50), percentile(ms, 95), percentile(ms, 99),
                Arrays.stream(ms).max().orElse(0)));
    }

    private static double percentile(double[] values, double percent) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(percent / 100.0 * sorted.length) - 1)];
    }

    /** Imagen con textura pseudoaleatoria reproducible (para que la huella no sea un cuadro plano). */
    private static BufferedImage texture(long seed) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int v = 60 + random.nextInt(140);
                image.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return image;
    }

    /** MLP de una capa oculta de 6 neuronas con pesos ALEATORIOS (juguete): solo sirve para medir cuanto tarda el calculo. */
    private static RemovalClassifier toyMlp(List<String> features) {
        Random random = new Random(7);
        int inputs = features.size();
        int hidden = 6;
        double[] mean = new double[inputs];
        double[] std = new double[inputs];
        Arrays.fill(std, 1.0);
        double[][] w1 = new double[hidden][inputs];
        for (double[] row : w1) {
            for (int i = 0; i < inputs; i++) {
                row[i] = random.nextGaussian();
            }
        }
        double[] b1 = new double[hidden];
        double[] w2 = new double[hidden];
        for (int j = 0; j < hidden; j++) {
            w2[j] = random.nextGaussian();
        }
        return RemovalClassifier.mlp(features, mean, std, w1, b1, w2, 0.1, 0.35, true);
    }
}
