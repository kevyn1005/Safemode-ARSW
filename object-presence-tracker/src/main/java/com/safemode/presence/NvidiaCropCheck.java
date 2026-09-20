package com.safemode.presence;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

// Script de verificacion manual (sin camara): describe con IA un recorte de dueno ya guardado en disco.
// OJO privacidad: la imagen se envia al servicio de NVIDIA. Requiere la variable de entorno NVIDIA_API_KEY.
// Uso:
//   mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.NvidiaCropCheck" "-Dexec.args=ruta\al\recorte.png"
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
        long startedAt = System.nanoTime();
        String raw = ai.fetchModelContent(crop);
        double seconds = (System.nanoTime() - startedAt) / 1e9;
        if (raw == null) {
            System.out.printf("Sin respuesta (%.1f s); ver el mensaje [IA] de arriba.%n", seconds);
            return;
        }
        String description = NvidiaOwnerVisionDescriber.formatDescription(raw);
        System.out.printf("Respuesta en %.1f s%n", seconds);
        System.out.println("Texto crudo del modelo: " + raw.replaceAll("\\s+", " ").trim());
        System.out.println("Frase que se guardaria: " + (description != null ? description : "(ninguna: sin campos utiles)"));
    }
}
