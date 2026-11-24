package com.safemode.presence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Busca y carga el modelo de verificacion de retiro para el seguimiento, y <b>nunca hace fallar al tracker</b>: si el modelo falta, esta
 * danado, es de juguete y se pidio {@code active}, o el modo esta mal escrito, devuelve "sin modelo" (modo {@code off}) con un aviso y el
 * seguimiento sigue decidiendo con la regla de siempre.
 *
 * <p>Donde lo busca, en este orden:
 * <ol>
 *   <li>El archivo de {@code SAFEMODE_REMOVAL_MODEL_FILE}, si la variable existe (para probar un modelo sin recompilar).</li>
 *   <li>El recurso del proyecto {@value #RESOURCE}, es decir {@code object-presence-tracker/src/main/resources/removal-model/model.json}.
 *       Ahi lo deja {@code ml/export_model.py export}; hay que volver a compilar ({@code mvn ... install -DskipTests}) para que viaje en el
 *       jar. Mientras no haya modelo entrenado, ese archivo no existe y todo sigue como siempre.</li>
 * </ol>
 *
 * <p>Un modelo de juguete ({@code "meta": {"toy": true}}, pesos inventados) solo se acepta en {@code shadow}; en {@code active} se rechaza.
 * Un modelo que NO es de juguete y usa {@code frames_unseen} se rechaza siempre: esa senal es un atajo aprendido (aprende el guion de las
 * capturas, no la zona del objeto) y solo se permite en el experimento de ablacion de {@code ml/} (ver docs/adr/0007).
 */
public final class RemovalModelLoader {

    /** Senal que un modelo final no puede usar (ver arriba). */
    static final String FORBIDDEN_IN_FINAL_MODEL = "frames_unseen";

    /** Donde vive el modelo entrenado dentro del proyecto (src/main/resources/removal-model/model.json). */
    public static final String RESOURCE = "/removal-model/model.json";

    /** Lo que el tracker debe usar: el clasificador (o null), el modo efectivo y un aviso para la consola (o null si no hay nada que decir). */
    public record Loaded(RemovalClassifier classifier, RemovalModelMode mode, String notice) {
        public boolean usesModel() {
            return classifier != null && mode != RemovalModelMode.OFF;
        }
    }

    private RemovalModelLoader() {
    }

    /** Lee {@code SAFEMODE_REMOVAL_MODEL} y {@code SAFEMODE_REMOVAL_MODEL_FILE} del entorno. */
    public static Loaded fromEnvironment() {
        return load(System.getenv("SAFEMODE_REMOVAL_MODEL"), System.getenv("SAFEMODE_REMOVAL_MODEL_FILE"));
    }

    /** {@code modeValue}: off | shadow | active (o vacio = off); {@code fileValue}: ruta del modelo, o vacio para usar el recurso del proyecto. */
    public static Loaded load(String modeValue, String fileValue) {
        return load(modeValue, fileValue, RESOURCE);
    }

    static Loaded load(String modeValue, String fileValue, String resource) {
        RemovalModelMode requested;
        try {
            requested = RemovalModelMode.parse(modeValue);
        } catch (IllegalArgumentException e) {
            return fallback(e.getMessage() + ".");
        }
        if (requested == RemovalModelMode.OFF) {
            return new Loaded(null, RemovalModelMode.OFF, null); // apagado: ni se mira el modelo
        }
        RemovalClassifier classifier;
        String origin;
        try {
            if (fileValue != null && !fileValue.isBlank()) {
                Path file = Path.of(fileValue.trim());
                origin = file.toString();
                classifier = RemovalClassifier.load(file);
            } else {
                origin = "el recurso " + resource;
                try (InputStream in = RemovalModelLoader.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        return fallback("No hay un modelo entrenado en " + resource.substring(1) + " (src/main/resources) y no se indico "
                                + "SAFEMODE_REMOVAL_MODEL_FILE. Entrenalo y exportalo con ml/export_model.py export (ver ml/README.md).");
                    }
                    classifier = RemovalClassifier.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | RuntimeException e) {
            return fallback("No se pudo cargar el modelo (" + e.getMessage() + ").");
        }
        if (!classifier.isToy() && classifier.usesFeature(FORBIDDEN_IN_FINAL_MODEL)) {
            return fallback("El modelo de " + origin + " usa " + FORBIDDEN_IN_FINAL_MODEL + ", un atajo aprendido que solo se permite en la "
                    + "ablacion (aprende el guion de las capturas, no la zona del objeto).");
        }
        if (classifier.isToy() && requested == RemovalModelMode.ACTIVE) {
            return fallback("El modelo de " + origin + " es de JUGUETE (pesos inventados) y no puede decidir en modo active.");
        }
        String notice = "Modelo de verificacion de retiro: " + requested + " (" + classifier.describe() + ") [" + origin + "]";
        if (classifier.isToy()) {
            notice += ". OJO: solo para ver la integracion; sus probabilidades no significan nada";
        }
        return new Loaded(classifier, requested, notice);
    }

    private static Loaded fallback(String reason) {
        return new Loaded(null, RemovalModelMode.OFF,
                "AVISO modelo de retiro: " + reason + " El tracker sigue con la regla de siempre (modo off).");
    }
}
