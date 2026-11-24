package com.safemode.presence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link RemovalClassifier}: el calculo a mano con numeros chicos, la validacion del JSON y, sobre todo, que Java da la MISMA
 * probabilidad que Python (tolerancia 1e-9) en los casos que genera {@code ml/export_model.py}. Los modelos "toy" son de juguete (pesos
 * inventados para probar el formato); si existe el modelo entrenado ({@code trained.json}) tambien se comprueba.
 */
class RemovalClassifierTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = RemovalClassifierTest.class.getResourceAsStream("/removal-model/" + name)) {
            assertNotNull(in, "falta el archivo de prueba " + name + " (se genera con ml/export_model.py)");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean resourceExists(String name) {
        return RemovalClassifierTest.class.getResource("/removal-model/" + name) != null;
    }

    private static void assertMatchesPython(String modelResource, String casesResource) throws IOException {
        RemovalClassifier model = RemovalClassifier.fromJson(resource(modelResource));
        List<String> lines = resource(casesResource).lines().toList();
        String[] header = lines.get(0).split(",");
        int n = header.length - 1;
        assertEquals(model.features(), List.of(header).subList(0, n), "las columnas de los casos son las senales del modelo, en su orden");
        assertTrue(lines.size() - 1 >= 200, "se esperaban al menos 200 casos");
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",");
            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                values[i] = Double.parseDouble(cells[i]);
            }
            assertEquals(Double.parseDouble(cells[n]), model.probabilityRemoved(values), 1e-9, "caso: " + line);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"toy-logistic", "toy-mlp", "toy-mlp8"})
    void javaDaLaMismaProbabilidadQuePythonEnLosModelosDeJuguete(String name) throws IOException {
        assertMatchesPython(name + ".json", name + ".cases.csv");
    }

    /** El modelo REAL vive en src/main/resources/removal-model/model.json (lo deja ml/export_model.py export) y sus casos en test/resources. */
    @Test
    void javaDaLaMismaProbabilidadQuePythonEnElModeloEntrenado() throws IOException {
        assumeTrue(resourceExists("model.json"), "todavia no hay modelo entrenado exportado (ml/export_model.py export)");
        assertMatchesPython("model.json", "trained.cases.csv");
    }

    @Test
    void elModeloDelProyectoNuncaEsDeJuguete() throws IOException {
        assumeTrue(resourceExists("model.json"), "todavia no hay modelo entrenado exportado (ml/export_model.py export)");
        assertFalse(RemovalClassifier.fromJson(resource("model.json")).isToy(),
                "src/main/resources/removal-model/model.json no puede ser un modelo de juguete");
    }

    @ParameterizedTest
    @ValueSource(strings = {"toy-logistic", "toy-mlp", "toy-mlp8"})
    void losModelosDeJugueteSeReconocenComoTales(String name) throws IOException {
        RemovalClassifier model = RemovalClassifier.fromJson(resource(name + ".json"));

        assertTrue(model.isToy());
        assertTrue(model.describe().contains("JUGUETE"), model.describe());
    }

    @Test
    void elJugueteDelModeloPrincipalUsaLasCuatroSenalesDelPlan() throws IOException {
        // el plan: el modelo principal lee solo x1-x4; las 8 del catalogo son un experimento de la ablacion (toy-mlp8)
        assertEquals(List.of("x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff"),
                RemovalClassifier.fromJson(resource("toy-mlp.json")).features());
        assertEquals(4, RemovalClassifier.fromJson(resource("toy-logistic.json")).features().size());
        assertEquals(RemovalFeatures.CATALOG.size(), RemovalClassifier.fromJson(resource("toy-mlp8.json")).features().size());
    }

    @Test
    void unModeloArmadoAManoOSinMetaNoEsDeJuguete() {
        RemovalClassifier byHand = RemovalClassifier.logistic(List.of("x1_changed"), new double[]{0}, new double[]{1}, new double[]{1}, 0, 0.5);
        assertFalse(byHand.isToy());
        assertFalse(byHand.describe().contains("JUGUETE"));
        assertFalse(RemovalClassifier.fromJson(logisticJson("[\"x1_changed\"]", "[0]", "[1]", "[3]", "0.4")).isToy());
    }

    @Test
    void unMetaRaroNoRompeLaCargaNiMarcaJuguete() {
        String base = logisticJson("[\"x1_changed\"]", "[0]", "[1]", "[3]", "0.4");
        for (String meta : List.of("5", "\"toy\"", "[true]", "{\"toy\":\"yes\"}", "{\"toy\":1}", "{\"toy\":null}", "{}")) {
            RemovalClassifier model = RemovalClassifier.fromJson(base.substring(0, base.length() - 1) + ",\"meta\":" + meta + "}");
            assertFalse(model.isToy(), "meta = " + meta);
        }
        RemovalClassifier toy = RemovalClassifier.fromJson(base.substring(0, base.length() - 1) + ",\"meta\":{\"toy\":true,\"note\":\"x\"}}");
        assertTrue(toy.isToy());
    }

    @Test
    void laRegresionLogisticaSeCalculaComoSeEsperaAMano() {
        // z = (x - 0.5) / 0.25; p = sigmoide(2 z - 1)
        RemovalClassifier model = RemovalClassifier.logistic(List.of("x1_changed"), new double[]{0.5}, new double[]{0.25},
                new double[]{2}, -1, 0.6);

        assertEquals(1 / (1 + Math.exp(1)), model.probabilityRemoved(new double[]{0.5}), 1e-15); // z = 0 -> sigmoide(-1)
        assertEquals(1 / (1 + Math.exp(-1)), model.probabilityRemoved(new double[]{0.75}), 1e-15); // z = 1 -> sigmoide(1)
        assertFalse(model.isRemoved(new double[]{0.5}));
        assertTrue(model.isRemoved(new double[]{0.75}), "0.731 supera el umbral de 0.6");
        assertEquals("logistic", model.kind());
        assertEquals(0.6, model.threshold());
    }

    @Test
    void elMlpSeCalculaComoSeEsperaAMano() {
        // w1 = [[1, 0], [0, -1]], b1 = [0, 0.5], w2 = [1, 1], b2 = -0.5; entrada (2, 1): h = [relu(2), relu(-1 + 0.5)] = [2, 0]
        RemovalClassifier model = RemovalClassifier.mlp(List.of("x1_changed", "x2_visible"), new double[]{0, 0}, new double[]{1, 1},
                new double[][]{{1, 0}, {0, -1}}, new double[]{0, 0.5}, new double[]{1, 1}, -0.5, 0.5);

        assertEquals(1 / (1 + Math.exp(-1.5)), model.probabilityRemoved(new double[]{2, 1}), 1e-15);
        // la ReLU recorta lo negativo: con x2 alto la segunda neurona se apaga y con x1 = 0 la primera tambien
        assertEquals(1 / (1 + Math.exp(0.5)), model.probabilityRemoved(new double[]{0, 5}), 1e-15);
    }

    @Test
    void laSigmoideNoDaNaNConPuntuacionesEnormes() {
        RemovalClassifier model = RemovalClassifier.logistic(List.of("x1_changed"), new double[]{0}, new double[]{1},
                new double[]{1e6}, 0, 0.5);

        assertEquals(1.0, model.probabilityRemoved(new double[]{1}), 0.0);
        assertEquals(0.0, model.probabilityRemoved(new double[]{-1}), 0.0);
    }

    @Test
    void conElCatalogoCompletoElModeloTomaSoloSusSenalesPorNombre() {
        // el modelo usa x2 y luego x1 (al reves que el catalogo) y solo pesa x1
        RemovalClassifier model = RemovalClassifier.logistic(List.of("x2_visible", "x1_changed"), new double[]{0, 0}, new double[]{1, 1},
                new double[]{0, 10}, -5, 0.5);
        double[] catalog = new double[RemovalFeatures.CATALOG.size()];
        catalog[RemovalFeatures.CATALOG.indexOf("x1_changed")] = 1.0;
        catalog[RemovalFeatures.CATALOG.indexOf("x2_visible")] = 0.0;
        catalog[RemovalFeatures.CATALOG.indexOf("frames_unseen")] = 9; // esta no la usa: no debe influir

        assertEquals(1 / (1 + Math.exp(-5)), model.probabilityFromCatalog(catalog), 1e-15);
        assertTrue(model.usesFeature("x1_changed"));
        assertFalse(model.usesFeature("x5_frame_changed"));
    }

    private static String logisticJson(String features, String mean, String std, String weights, String threshold) {
        return "{\"format\":1,\"kind\":\"logistic\",\"features\":" + features + ",\"mean\":" + mean + ",\"std\":" + std
                + ",\"threshold\":" + threshold + ",\"logistic\":{\"weights\":" + weights + ",\"bias\":0}}";
    }

    @Test
    void unJsonValidoSeCarga() {
        RemovalClassifier model = RemovalClassifier.fromJson(logisticJson("[\"x1_changed\"]", "[0]", "[1]", "[3]", "0.4"));

        assertEquals(List.of("x1_changed"), model.features());
        assertTrue(model.describe().contains("regresion logistica"), model.describe());
    }

    @Test
    void unModeloInvalidoSeRechazaConUnMensajeClaro() {
        assertMessage("Senal desconocida", logisticJson("[\"color_de_la_mochila\"]", "[0]", "[1]", "[1]", "0.5"));
        assertMessage("repetida", logisticJson("[\"x1_changed\",\"x1_changed\"]", "[0,0]", "[1,1]", "[1,1]", "0.5"));
        assertMessage("std debe ser", logisticJson("[\"x1_changed\"]", "[0]", "[0]", "[1]", "0.5"));
        assertMessage("umbral", logisticJson("[\"x1_changed\"]", "[0]", "[1]", "[1]", "1.0"));
        assertMessage("umbral", logisticJson("[\"x1_changed\"]", "[0]", "[1]", "[1]", "0"));
        assertMessage("mean debe tener", logisticJson("[\"x1_changed\",\"x2_visible\"]", "[0]", "[1,1]", "[1,1]", "0.5"));
        assertMessage("logistic.weights debe tener", logisticJson("[\"x1_changed\"]", "[0]", "[1]", "[1,2]", "0.5"));
        assertMessage("Formato de modelo no soportado", "{\"format\":2,\"kind\":\"logistic\"}");
        assertMessage("Tipo de modelo desconocido", "{\"format\":1,\"kind\":\"svm\",\"features\":[\"x1_changed\"],\"mean\":[0],\"std\":[1],\"threshold\":0.5}");
        assertMessage("no es un JSON valido", "esto no es json");
        assertMessage("incompleto", "{\"format\":1,\"kind\":\"logistic\"}");
        assertMessage("al menos una senal", logisticJson("[]", "[]", "[]", "[]", "0.5"));
    }

    private static void assertMessage(String expected, String json) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> RemovalClassifier.fromJson(json), json);
        assertTrue(error.getMessage().contains(expected), "se esperaba \"" + expected + "\" en: " + error.getMessage());
    }

    @Test
    void siFaltaElArchivoDelModeloElErrorDiceComoArreglarlo() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RemovalClassifier.load(Path.of("no-existe", "model.json")));

        assertTrue(error.getMessage().contains("SAFEMODE_REMOVAL_MODEL_FILE"), error.getMessage());
        assertTrue(error.getMessage().contains("SAFEMODE_REMOVAL_MODEL=off"), error.getMessage());
    }

    @Test
    void elModoSeLeeDeLaVariableDeEntorno() {
        assertEquals(RemovalModelMode.OFF, RemovalModelMode.parse(null));
        assertEquals(RemovalModelMode.OFF, RemovalModelMode.parse("  "));
        assertEquals(RemovalModelMode.OFF, RemovalModelMode.parse("off"));
        assertEquals(RemovalModelMode.SHADOW, RemovalModelMode.parse("Shadow"));
        assertEquals(RemovalModelMode.ACTIVE, RemovalModelMode.parse(" ACTIVE "));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> RemovalModelMode.parse("sombra"));
        assertTrue(error.getMessage().contains("off, shadow o active"), error.getMessage());
    }
}
