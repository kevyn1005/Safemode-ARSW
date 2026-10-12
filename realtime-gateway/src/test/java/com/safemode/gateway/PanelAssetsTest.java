package com.safemode.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contra el panel REAL del repositorio ({@code security-dashboard/}): todo lo local que la pagina referencia (estilos y scripts)
 * lo tiene que entregar el gateway. Asi, un script nuevo que se agrega a la pagina y se olvida en la lista de archivos
 * permitidos falla aqui y no en el navegador. Es el mismo panel en el computador y en Docker.
 */
class PanelAssetsTest {

    @TempDir
    Path photos;

    private DashboardHttpServer server;
    private Path dashboard;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        dashboard = Path.of("..", "security-dashboard");
        assumeTrue(Files.isRegularFile(dashboard.resolve("index.html")), "no se encontro security-dashboard/ (otra ubicacion de ejecucion)");
        server = new DashboardHttpServer(0, dashboard, photos);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.boundPort() + "/" + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int status(String path) throws Exception {
        return get(path).statusCode();
    }

    private String html() throws Exception {
        return Files.readString(dashboard.resolve("index.html"), StandardCharsets.UTF_8);
    }

    @Test
    void laPaginaDelPanelSeEntrega() throws Exception {
        assertEquals(200, status(""));
        assertEquals(200, status("index.html"));
    }

    @Test
    void todoLoLocalQueLaPaginaReferenciaSeEntrega() throws Exception {
        // src="..." y href="..." sin esquema (http:, https:, data:, mailto:) ni ancla (#)
        Matcher m = Pattern.compile("(?:src|href)=\"([^\"#:]+)\"").matcher(html());
        List<String> local = new ArrayList<>();
        while (m.find()) {
            local.add(m.group(1));
        }
        assertFalse(local.isEmpty(), "la pagina referencia al menos sus scripts");
        for (String path : local) {
            assertEquals(200, status(path), "el gateway no entrega lo que la pagina pide: " + path);
        }
    }

    @Test
    void estanLosScriptsDeLasFuncionesDelPanel() throws Exception {
        String html = html();
        for (String script : new String[]{"tracker-control.js", "camera-zones.js", "camera-share.js", "camera-names.js", "report.js",
                "browser-notifications.js", "panel.js"}) {
            assertTrue(html.contains("src=\"" + script + "\""), "la pagina debe incluir " + script);
        }
    }

    @Test
    void losEstilosDelPanelSeEntreganComoCss() throws Exception {
        assertTrue(html().contains("href=\"panel.css\""), "la pagina debe incluir panel.css");
        HttpResponse<String> css = get("panel.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
    }

    @Test
    void elPanelYaNoCargaElVideoNiLaSecuenciaDeImagenesDelEncabezado() throws Exception {
        String html = html();
        assertFalse(html.contains(".mp4"), "el panel ya no lleva el video del encabezado");
        assertFalse(html.contains("lens-frames"), "el panel ya no lleva la secuencia de imagenes del encabezado");
    }

    @Test
    void laCarpetaDePruebasDeNodeNoSeEntrega() throws Exception {
        assertEquals(404, status("test/camera-zones.test.js"));
        assertEquals(404, status("package.json"));
    }
}
