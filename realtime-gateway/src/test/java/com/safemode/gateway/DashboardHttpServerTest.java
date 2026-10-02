package com.safemode.gateway;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardHttpServerTest {

    @TempDir
    Path tmp;

    private DashboardHttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        Path dashboard = Files.createDirectories(tmp.resolve("dashboard"));
        Path photos = Files.createDirectories(tmp.resolve("photos"));
        Files.writeString(dashboard.resolve("index.html"), "<html>panel</html>");
        Files.write(photos.resolve("a.png"), new byte[]{1, 2, 3});
        Files.writeString(tmp.resolve("secreto.txt"), "no debe salir");
        Files.createDirectories(photos.resolve("sub"));
        Files.write(photos.resolve("sub").resolve("b.png"), new byte[]{9});
        server = new DashboardHttpServer(0, dashboard, photos);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.boundPort() + path)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void entregaLaPaginaDelDashboard() throws Exception {
        HttpResponse<byte[]> response = get("/");
        assertEquals(200, response.statusCode());
        assertEquals("<html>panel</html>", new String(response.body()));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
    }

    @Test
    void entregaUnaFotoConSuTipo() throws Exception {
        HttpResponse<byte[]> response = get("/photo/a.png");
        assertEquals(200, response.statusCode());
        assertArrayEquals(new byte[]{1, 2, 3}, response.body());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void noSaleDeLaCarpetaDeFotos() throws Exception {
        assertEquals(404, get("/photo/..%2Fsecreto.txt").statusCode());
        assertEquals(404, get("/photo/../secreto.txt").statusCode());
        assertEquals(404, get("/photo/sub/b.png").statusCode());
        assertEquals(404, get("/photo/no-existe.png").statusCode());
    }

    @Test
    void otrasRutasDanNoEncontrado() throws Exception {
        assertEquals(404, get("/secreto.txt").statusCode());
    }

    @Test
    void soloAceptaGet() throws Exception {
        HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.boundPort() + "/"))
                        .POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(405, response.statusCode());
    }
}
