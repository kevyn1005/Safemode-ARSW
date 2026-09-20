package com.safemode.presence;

import com.safemode.vision.PoseEstimator;
import com.safemode.vision.PoseEstimator.Keypoint;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

// Script de verificacion manual (sin camara): describe con IA un recorte de dueno ya guardado en disco.
// OJO privacidad: las imagenes se envian al servicio de NVIDIA. Requiere la variable de entorno NVIDIA_API_KEY.
// Si existe el modelo de pose local, prueba tambien la segunda pregunta (solo tatuajes, sobre un recorte de antebrazos
// sin cara) y guarda ese recorte en data/frames/_antebrazos_prueba.png para poder verlo.
// Uso:
//   mvn -q -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.NvidiaCropCheck" "-Dexec.args=ruta\al\recorte.png"
public class NvidiaCropCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Falta la ruta del recorte (-Dexec.args=ruta\\al\\recorte.png).");
            return;
        }
        NvidiaOwnerVisionDescriber ai = NvidiaOwnerVisionDescriber.fromEnvironment();
        if (ai == null) {
            System.out.println("Falta la variable de entorno NVIDIA_API_KEY.");
            return;
        }
        BufferedImage crop = ImageIO.read(new File(args[0]));
        if (crop == null) {
            System.out.println("No se pudo leer la imagen: " + args[0]);
            return;
        }
        System.out.println("Modelo: " + ai.modelName() + " - imagen " + crop.getWidth() + "x" + crop.getHeight());

        System.out.println("\n--- 1) Descripcion de la persona ---");
        long startedAt = System.nanoTime();
        String raw = ai.fetchModelContent(crop);
        double seconds = (System.nanoTime() - startedAt) / 1e9;
        if (raw == null) {
            System.out.printf("Sin respuesta (%.1f s); ver el mensaje [IA] de arriba.%n", seconds);
        } else {
            String description = NvidiaOwnerVisionDescriber.formatDescription(raw);
            System.out.printf("Respuesta en %.1f s%n", seconds);
            System.out.println("Texto crudo del modelo: " + raw.replaceAll("\\s+", " ").trim());
            System.out.println("Frase: " + (description != null ? description : "(ninguna: sin campos utiles)"));
        }

        System.out.println("\n--- 2) Tatuajes, sobre un recorte de antebrazos ---");
        Path poseModel = Path.of("object-presence-tracker", "data", "models", "yolov8m-pose.onnx");
        if (!Files.exists(poseModel)) {
            System.out.println("Sin modelo de pose (" + poseModel + "): no se puede recortar los antebrazos.");
            return;
        }
        BufferedImage arms;
        try (PoseEstimator pose = new PoseEstimator(poseModel.toString())) {
            Keypoint[] keypoints = pose.estimate(crop);
            arms = ArmsCropper.armsCrop(crop, keypoints);
        }
        if (arms == null) {
            System.out.println("No se ubicaron los antebrazos con confianza en esta imagen.");
            return;
        }
        Path armsFile = Path.of("object-presence-tracker", "data", "frames", "_antebrazos_prueba.png");
        Files.createDirectories(armsFile.getParent());
        ImageIO.write(arms, "png", armsFile.toFile());
        System.out.println("Recorte de antebrazos " + arms.getWidth() + "x" + arms.getHeight() + " guardado en " + armsFile);

        startedAt = System.nanoTime();
        String rawArms = ai.fetchModelContent(arms, NvidiaOwnerVisionDescriber.ARMS_PROMPT);
        seconds = (System.nanoTime() - startedAt) / 1e9;
        if (rawArms == null) {
            System.out.printf("Sin respuesta (%.1f s); ver el mensaje [IA] de arriba.%n", seconds);
            return;
        }
        String tattoos = NvidiaOwnerVisionDescriber.formatTattoos(rawArms);
        System.out.printf("Respuesta en %.1f s%n", seconds);
        System.out.println("Texto crudo del modelo: " + rawArms.replaceAll("\\s+", " ").trim());
        System.out.println("Tatuajes que se guardarian: " + (tattoos != null ? tattoos : "(sin dato)"));
    }
}
