package com.safemode.presence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Modelo pequeno que decide si un objeto en reposo que el detector dejo de ver "sigue ahi" o "fue retirado", a partir de las senales
 * de su zona ({@link RemovalFeatures}). Sin dependencias de aprendizaje automatico: es solo aritmetica sobre los pesos que entrena
 * {@code ml/} en Python y que se guardan en un JSON (formato 1):
 * <pre>
 * {"format": 1, "kind": "logistic" | "mlp", "features": [...], "mean": [...], "std": [...], "threshold": 0.37,
 *  "logistic": {"weights": [...], "bias": 0.0}                                  // kind = logistic
 *  "mlp": {"w1": [[...]], "b1": [...], "w2": [...], "b2": 0.0}}                 // kind = mlp (una capa oculta ReLU)
 * </pre>
 * El bloque opcional {@code "meta"} lo ignora el calculo, salvo {@code "toy": true}: marca un modelo DE JUGUETE (pesos inventados para probar
 * el formato y la integracion). Un modelo de juguete se puede ver en modo sombra, pero nunca decide ({@link RemovalModelLoader}).
 * Las entradas se estandarizan con {@code (x - mean) / std}. La salida {@link #probabilityRemoved} es un numero entre 0 y 1
 * (sigmoide); {@link #isRemoved} lo compara con el umbral elegido al entrenar. Es inmutable: se puede compartir entre camaras.
 */
public final class RemovalClassifier {

    /** El modelo empieza a decidir desde este frame sin ver el objeto (el primero solo se anota). */
    static final int DECIDES_FROM_FRAME = 2;

    private static final int FORMAT = 1;

    private final String kind;
    private final List<String> features;
    private final int[] catalogIndex;
    private final double[] mean;
    private final double[] std;
    private final double threshold;
    // regresion logistica
    private final double[] weights;
    private final double bias;
    // MLP de una capa oculta: w1[oculta][entrada], b1[oculta], w2[oculta], b2
    private final double[][] w1;
    private final double[] b1;
    private final double[] w2;
    private final double b2;
    // true si el JSON dice meta.toy = true: pesos inventados, no un modelo entrenado
    private final boolean toy;

    private RemovalClassifier(String kind, List<String> features, double[] mean, double[] std, double threshold,
                              double[] weights, double bias, double[][] w1, double[] b1, double[] w2, double b2, boolean toy) {
        validateCommon(features, mean, std, threshold);
        this.kind = kind;
        this.features = List.copyOf(features);
        this.catalogIndex = new int[features.size()];
        for (int i = 0; i < features.size(); i++) {
            catalogIndex[i] = RemovalFeatures.CATALOG.indexOf(features.get(i));
        }
        this.mean = mean.clone();
        this.std = std.clone();
        this.threshold = threshold;
        this.weights = weights;
        this.bias = bias;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
        this.toy = toy;
    }

    /** Regresion logistica (tambien sirve para un perceptron o una SVM lineal: es el mismo calculo, {@code sigmoide(w.z + b)}). */
    static RemovalClassifier logistic(List<String> features, double[] mean, double[] std, double[] weights, double bias, double threshold) {
        return logistic(features, mean, std, weights, bias, threshold, false);
    }

    static RemovalClassifier logistic(List<String> features, double[] mean, double[] std, double[] weights, double bias, double threshold,
                                      boolean toy) {
        requireFinite(weights, "logistic.weights");
        requireLength(weights.length, features.size(), "logistic.weights");
        requireFinite(bias, "logistic.bias");
        return new RemovalClassifier("logistic", features, mean, std, threshold, weights.clone(), bias, null, null, null, 0, toy);
    }

    /** MLP con una capa oculta ReLU y salida sigmoide. {@code w1[oculta][entrada]}. */
    static RemovalClassifier mlp(List<String> features, double[] mean, double[] std, double[][] w1, double[] b1, double[] w2, double b2,
                                 double threshold) {
        return mlp(features, mean, std, w1, b1, w2, b2, threshold, false);
    }

    static RemovalClassifier mlp(List<String> features, double[] mean, double[] std, double[][] w1, double[] b1, double[] w2, double b2,
                                 double threshold, boolean toy) {
        if (w1.length == 0) {
            throw new IllegalArgumentException("mlp.w1 no puede estar vacia");
        }
        requireLength(b1.length, w1.length, "mlp.b1");
        requireLength(w2.length, w1.length, "mlp.w2");
        double[][] copy = new double[w1.length][];
        for (int j = 0; j < w1.length; j++) {
            requireLength(w1[j].length, features.size(), "mlp.w1[" + j + "]");
            requireFinite(w1[j], "mlp.w1[" + j + "]");
            copy[j] = w1[j].clone();
        }
        requireFinite(b1, "mlp.b1");
        requireFinite(w2, "mlp.w2");
        requireFinite(b2, "mlp.b2");
        return new RemovalClassifier("mlp", features, mean, std, threshold, null, 0, copy, b1.clone(), w2.clone(), b2, toy);
    }

    /** Lee un modelo del archivo JSON; si no existe, el error dice como arreglarlo. */
    public static RemovalClassifier load(Path file) throws IOException {
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("No encuentro el modelo de verificacion de retiro en " + file
                    + ". Entrenalo (ver ml/README.md) o indica otro archivo con SAFEMODE_REMOVAL_MODEL_FILE; "
                    + "con SAFEMODE_REMOVAL_MODEL=off no hace falta.", e);
        }
    }

    public static RemovalClassifier fromJson(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("El modelo no es un JSON valido: " + e.getMessage(), e);
        }
        try {
            int format = root.has("format") ? root.get("format").getAsInt() : -1;
            if (format != FORMAT) {
                throw new IllegalArgumentException("Formato de modelo no soportado: " + format + " (se espera " + FORMAT + ")");
            }
            String kind = root.get("kind").getAsString();
            List<String> features = new ArrayList<>();
            for (JsonElement f : root.getAsJsonArray("features")) {
                features.add(f.getAsString());
            }
            double[] mean = doubles(root.getAsJsonArray("mean"));
            double[] std = doubles(root.getAsJsonArray("std"));
            double threshold = root.get("threshold").getAsDouble();
            boolean toy = markedAsToy(root);
            return switch (kind) {
                case "logistic" -> {
                    JsonObject l = root.getAsJsonObject("logistic");
                    yield logistic(features, mean, std, doubles(l.getAsJsonArray("weights")), l.get("bias").getAsDouble(), threshold, toy);
                }
                case "mlp" -> {
                    JsonObject m = root.getAsJsonObject("mlp");
                    JsonArray rows = m.getAsJsonArray("w1");
                    double[][] w1 = new double[rows.size()][];
                    for (int j = 0; j < rows.size(); j++) {
                        w1[j] = doubles(rows.get(j).getAsJsonArray());
                    }
                    yield mlp(features, mean, std, w1, doubles(m.getAsJsonArray("b1")), doubles(m.getAsJsonArray("w2")),
                            m.get("b2").getAsDouble(), threshold, toy);
                }
                default -> throw new IllegalArgumentException("Tipo de modelo desconocido: \"" + kind + "\" (logistic o mlp)");
            };
        } catch (ClassCastException | IllegalStateException | NullPointerException | NumberFormatException e) {
            throw new IllegalArgumentException("El modelo esta incompleto o tiene un campo de tipo equivocado: " + e.getMessage(), e);
        }
    }

    /** {@code meta.toy == true}. Cualquier otra forma (sin meta, meta rara, toy no booleano) cuenta como "no es de juguete" y no rompe la carga. */
    private static boolean markedAsToy(JsonObject root) {
        JsonElement meta = root.get("meta");
        if (meta == null || !meta.isJsonObject()) {
            return false;
        }
        JsonElement toy = meta.getAsJsonObject().get("toy");
        return toy != null && toy.isJsonPrimitive() && toy.getAsJsonPrimitive().isBoolean() && toy.getAsBoolean();
    }

    /** Probabilidad (0 a 1) de que el objeto haya sido retirado. {@code values} va en el orden de {@link #features()}. */
    public double probabilityRemoved(double[] values) {
        requireLength(values.length, features.size(), "valores de entrada");
        double[] z = new double[values.length];
        for (int i = 0; i < z.length; i++) {
            z[i] = (values[i] - mean[i]) / std[i];
        }
        double score;
        if (weights != null) {
            score = bias;
            for (int i = 0; i < z.length; i++) {
                score += weights[i] * z[i];
            }
        } else {
            score = b2;
            for (int j = 0; j < w1.length; j++) {
                double hidden = b1[j];
                for (int i = 0; i < z.length; i++) {
                    hidden += w1[j][i] * z[i];
                }
                score += w2[j] * Math.max(0.0, hidden);
            }
        }
        return sigmoid(score);
    }

    /** Igual que {@link #probabilityRemoved(double[])}, pero recibe TODO el catalogo y toma solo las senales que el modelo usa. */
    double probabilityFromCatalog(double[] catalog) {
        double[] values = new double[catalogIndex.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = catalog[catalogIndex[i]];
        }
        return probabilityRemoved(values);
    }

    public boolean isRemoved(double[] values) {
        return probabilityRemoved(values) >= threshold;
    }

    public List<String> features() {
        return features;
    }

    public double threshold() {
        return threshold;
    }

    public String kind() {
        return kind;
    }

    public boolean usesFeature(String name) {
        return features.contains(name);
    }

    /** true si es un modelo DE JUGUETE (pesos inventados, no entrenado): se puede mostrar en sombra pero nunca debe decidir. */
    public boolean isToy() {
        return toy;
    }

    /** Frase corta para la consola. */
    public String describe() {
        return (toy ? "[JUGUETE: pesos inventados, NO es un modelo entrenado] " : "")
                + ("logistic".equals(kind) ? "regresion logistica" : "MLP de " + w1.length + " neuronas ocultas") + " con " + features.size()
                + " senales " + features + ", umbral " + String.format(Locale.ROOT, "%.3f", threshold);
    }

    static double sigmoid(double x) {
        // forma estable para valores muy positivos o muy negativos
        return x >= 0 ? 1.0 / (1.0 + Math.exp(-x)) : Math.exp(x) / (1.0 + Math.exp(x));
    }

    private static void validateCommon(List<String> features, double[] mean, double[] std, double threshold) {
        if (features.isEmpty()) {
            throw new IllegalArgumentException("El modelo necesita al menos una senal");
        }
        Set<String> seen = new HashSet<>();
        for (String name : features) {
            if (!RemovalFeatures.CATALOG.contains(name)) {
                throw new IllegalArgumentException("Senal desconocida: \"" + name + "\". Las validas son " + RemovalFeatures.CATALOG);
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("Senal repetida: \"" + name + "\"");
            }
        }
        requireLength(mean.length, features.size(), "mean");
        requireLength(std.length, features.size(), "std");
        requireFinite(mean, "mean");
        for (double s : std) {
            if (!Double.isFinite(s) || s <= 0) {
                throw new IllegalArgumentException("std debe ser finita y mayor que 0 (si una senal no varia, quitala del modelo)");
            }
        }
        if (!(threshold > 0 && threshold < 1)) {
            throw new IllegalArgumentException("El umbral debe estar entre 0 y 1 (sin incluirlos), no " + threshold);
        }
    }

    private static void requireLength(int actual, int expected, String name) {
        if (actual != expected) {
            throw new IllegalArgumentException(name + " debe tener " + expected + " elemento(s), tiene " + actual);
        }
    }

    private static void requireFinite(double[] values, String name) {
        for (double v : values) {
            requireFinite(v, name);
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " tiene un valor que no es un numero finito");
        }
    }

    private static double[] doubles(JsonArray array) {
        double[] out = new double[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }
}
