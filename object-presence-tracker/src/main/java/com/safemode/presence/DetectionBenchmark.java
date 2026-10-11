package com.safemode.presence;

import com.safemode.vision.ObjectDetector;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

// Script de medicion manual (no es JUnit): cuanto tarda el detector por fotograma en ESTE computador y si alcanzan 2 camaras
// a 2 fps (presupuesto de 500 ms por ciclo). No necesita camara: usa las fotos de escena que ya guardo el tracker.
//   mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.DetectionBenchmark"
// Argumentos opcionales: fotogramas medidos por escenario (por defecto 30) y ruta de otro modelo .onnx (por defecto yolov8m).
public class DetectionBenchmark {
    private static final double TARGET_FPS = 2.0;

    public static void main(String[] args) throws Exception {
        int frames = args.length > 0 ? Integer.parseInt(args[0].trim()) : 30;
        List<BufferedImage> scenes = loadScenes();
        Path biggerModel = Path.of("object-presence-tracker", "data", "models", "yolov8m.onnx");
        String modelPath = args.length > 1 ? args[1].trim() : Files.exists(biggerModel) ? biggerModel.toString()
                : "vision-detection-service/src/main/resources/models/yolov8s.onnx";

        System.out.println("=== Medicion del detector ===");
        System.out.println("Modelo: " + modelPath);
        System.out.println("Nucleos: " + Runtime.getRuntime().availableProcessors()
                + " | memoria maxima de la JVM: " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        System.out.println("Escenas de prueba: " + scenes.size() + " (" + scenes.get(0).getWidth() + "x" + scenes.get(0).getHeight()
                + ") | fotogramas por escenario: " + frames);
        System.out.println("Meta: " + TARGET_FPS + " fps por camara = ciclo de " + (long) (1000 / TARGET_FPS) + " ms\n");

        ObjectDetector detector = new ObjectDetector(modelPath);
        for (int i = 0; i < 5; i++) { // calentamiento: la primera inferencia es mucho mas lenta
            detector.detect(scenes.get(i % scenes.size()));
        }

        // A) una camara
        List<Double> single = time(detector, scenes, frames);
        report("A) 1 camara (un detector)", single);
        double a = mean(single);

        // B) dos camaras, un solo detector, una tras otra en cada ciclo
        List<Double> cycles = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            detector.detect(scenes.get(i % scenes.size()));
            detector.detect(scenes.get((i + 1) % scenes.size()));
            cycles.add((System.nanoTime() - t0) / 1e6);
        }
        report("B) 2 camaras, 1 detector, en fila (ciclo = CAM1 + CAM2)", cycles);
        double b = mean(cycles);

        // C) dos camaras, un detector por camara, en paralelo
        ObjectDetector second = new ObjectDetector(modelPath);
        second.detect(scenes.get(0));
        List<Double> c1 = Collections.synchronizedList(new ArrayList<>());
        List<Double> c2 = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        Thread t1 = worker(detector, scenes, frames, c1, start);
        Thread t2 = worker(second, scenes, frames, c2, start);
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();
        List<Double> both = new ArrayList<>(c1);
        both.addAll(c2);
        report("C) 2 camaras, 2 detectores, en paralelo (tiempo por fotograma)", both);
        double c = mean(both);

        System.out.println("\n=== Resumen ===");
        System.out.printf(Locale.ROOT, "A) 1 camara:            %6.0f ms por fotograma -> hasta %.1f fps%n", a, 1000 / a);
        System.out.printf(Locale.ROOT, "B) 2 camaras en fila:   %6.0f ms por ciclo     -> hasta %.1f fps por camara%n", b, 1000 / b);
        System.out.printf(Locale.ROOT, "C) 2 camaras paralelo:  %6.0f ms por fotograma -> hasta %.1f fps por camara%n", c, 1000 / c);
        double best = Math.max(1000 / b, 1000 / c);
        System.out.println(best >= TARGET_FPS
                ? "Veredicto: 2 camaras a " + TARGET_FPS + " fps CABEN en este computador."
                : String.format(Locale.ROOT, "Veredicto: 2 camaras a %.0f fps NO caben; el maximo medido es %.1f fps por camara. "
                + "Bajar la frecuencia o usar un modelo mas chico (yolov8s).", TARGET_FPS, best));
        System.out.println("Nota: se mide solo el detector (lo que se repite en cada fotograma). La pose (~300 ms) y la IA se "
                + "ejecutan una vez por evento, en segundo plano.");
    }

    private static Thread worker(ObjectDetector detector, List<BufferedImage> scenes, int frames, List<Double> out,
                                 CountDownLatch start) {
        return new Thread(() -> {
            try {
                start.await();
                out.addAll(time(detector, scenes, frames));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static List<Double> time(ObjectDetector detector, List<BufferedImage> scenes, int frames) throws Exception {
        List<Double> times = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            detector.detect(scenes.get(i % scenes.size()));
            times.add((System.nanoTime() - t0) / 1e6);
        }
        return times;
    }

    private static void report(String title, List<Double> ms) {
        List<Double> sorted = new ArrayList<>(ms);
        Collections.sort(sorted);
        System.out.printf(Locale.ROOT, "%s%n    media %.0f ms | mediana %.0f | p95 %.0f | min %.0f | max %.0f%n", title, mean(ms),
                sorted.get(sorted.size() / 2), sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(sorted.size() * 0.95) - 1)),
                sorted.get(0), sorted.get(sorted.size() - 1));
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Fotos de escena completa que dejo el tracker (no los recortes de personas). */
    private static List<BufferedImage> loadScenes() throws Exception {
        Path dir = Path.of("object-presence-tracker", "data", "frames");
        List<BufferedImage> scenes = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path file : files.filter(f -> f.getFileName().toString().matches(".*_(REGISTERED_AT_REST|REMOVED)_.*\\.png"))
                        .sorted().toList()) {
                    BufferedImage image = ImageIO.read(file.toFile());
                    if (image != null) {
                        scenes.add(image);
                    }
                }
            }
        }
        if (scenes.isEmpty()) {
            throw new IllegalStateException("No hay fotos de escena en " + dir + ": corre antes el tracker una vez.");
        }
        return scenes;
    }
}
