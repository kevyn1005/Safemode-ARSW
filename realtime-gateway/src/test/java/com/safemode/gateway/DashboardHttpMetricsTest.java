package com.safemode.gateway;

import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El servidor del panel mide cada peticion (cuantas, cuanto tardan y con que resultado) por ruta y clase de resultado, para que
 * Prometheus pueda mostrar trafico, latencia y errores del backend (requisito de observabilidad del Release 2) y para poder
 * escribir reglas de alerta sobre ellos. Las etiquetas tienen pocas variantes a proposito: una ruta desconocida no crea series nuevas.
 */
class DashboardHttpMetricsTest {

    @TempDir
    Path tmp;

    private DashboardHttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        Path dashboard = Files.createDirectories(tmp.resolve("dashboard"));
        Path photos = Files.createDirectories(tmp.resolve("photos"));
        Files.writeString(dashboard.resolve("index.html"), "<html>panel</html>");
        server = new DashboardHttpServer(0, dashboard, photos);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private int status(String path) throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.boundPort() + path)).build(), HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private static long count(String route, String statusClass) {
        Timer timer = Observability.REGISTRY.find("safemode.http.request").tag("route", route).tag("status", statusClass).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void cadaPeticionQuedaMedidaPorRutaYClaseDeResultado() throws Exception {
        long paginaAntes = count("pagina", "2xx");
        long noEncontradaAntes = count("estatico_otro", "4xx");

        assertEquals(200, status("/"));
        assertEquals(200, status("/"));
        assertEquals(404, status("/no-existe"));

        assertEquals(paginaAntes + 2, count("pagina", "2xx"), "dos paginas servidas");
        assertEquals(noEncontradaAntes + 1, count("estatico_otro", "4xx"), "un 404 contado como 4xx");
        Timer timer = Observability.REGISTRY.find("safemode.http.request").tag("route", "pagina").tag("status", "2xx").timer();
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0, "se registra la duracion, no solo el conteo");
    }

    @Test
    void lasRutasSeAgrupanEnPocasEtiquetas() {
        assertEquals("pagina", DashboardHttpServer.routeLabel("/"));
        assertEquals("pagina", DashboardHttpServer.routeLabel("/index.html"));
        assertEquals("auth", DashboardHttpServer.routeLabel("/auth/me"));
        assertEquals("cameras", DashboardHttpServer.routeLabel("/cameras"));
        assertEquals("cameras", DashboardHttpServer.routeLabel("/cameras/2"));
        assertEquals("report", DashboardHttpServer.routeLabel("/report"));
        assertEquals("foto", DashboardHttpServer.routeLabel("/photo/a.png"));
        assertEquals("puesto", DashboardHttpServer.routeLabel("/puesto"));
        assertEquals("tracker", DashboardHttpServer.routeLabel("/tracker/status"));
        // cualquier otra cosa (un escaner probando rutas) cae en la misma etiqueta: no crea series nuevas en Prometheus
        assertEquals("estatico_otro", DashboardHttpServer.routeLabel("/camera-share.js"));
        assertEquals("estatico_otro", DashboardHttpServer.routeLabel("/wp-admin/login.php"));
        assertEquals("estatico_otro", DashboardHttpServer.routeLabel("/" + "x".repeat(300)));
    }
}
