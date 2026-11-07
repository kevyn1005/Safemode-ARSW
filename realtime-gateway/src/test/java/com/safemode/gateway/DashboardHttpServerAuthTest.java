package com.safemode.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardHttpServerAuthTest {

    private static final String JSON = "application/json";

    @TempDir
    Path tmp;

    private final HttpClient client = HttpClient.newHttpClient();
    private DashboardHttpServer server;
    private String panelOrigin;

    @BeforeEach
    void setUp() throws IOException {
        Path dashboard = Files.createDirectories(tmp.resolve("dashboard"));
        Path photos = Files.createDirectories(tmp.resolve("photos"));
        Files.writeString(dashboard.resolve("index.html"), "<html>panel</html>");
        Files.write(photos.resolve("a.png"), new byte[]{1, 2, 3});

        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort(); // el Origin autorizado necesita conocer el puerto de antemano
        }
        panelOrigin = "http://localhost:" + port;

        AuthService.TokenVerifier verifier = token -> switch (token) {
            case "token-permitido" -> Optional.of(new AuthService.User("ana@example.com", "Ana"));
            case "token-ajeno" -> Optional.of(new AuthService.User("otra@example.com", "Otra"));
            default -> Optional.empty();
        };
        AuthService auth = new AuthService("client-123", Set.of("Ana@Example.com"), verifier, Clock.systemUTC());
        server = new DashboardHttpServer("localhost", port, dashboard, photos, null, Set.of(panelOrigin)).withAuth(auth);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(panelOrigin + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String origin, String contentType, String body, String cookie)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(panelOrigin + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Inicia sesion con el correo permitido y devuelve "safemode_session=<id>" para mandarlo como Cookie. */
    private String login() throws Exception {
        HttpResponse<String> response = post("/auth/google", panelOrigin, JSON, "{\"credential\":\"token-permitido\"}", null);
        assertEquals(200, response.statusCode());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    @Test
    void laConfiguracionPublicaEntregaElClientId() throws Exception {
        HttpResponse<String> response = get("/auth/config", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("client-123"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void sinSesionLasRutasProtegidasDan401() throws Exception {
        assertEquals(401, get("/auth/me", null).statusCode());
        assertEquals(401, get("/report", null).statusCode());
        assertEquals(401, get("/cameras", null).statusCode());
        assertEquals(401, get("/tracker/status", null).statusCode());
        assertEquals(401, get("/photo/a.png", null).statusCode());
    }

    @Test
    void laPaginaDelPanelSeEntregaSinSesionParaPoderMostrarElLogin() throws Exception {
        assertEquals(200, get("/", null).statusCode());
    }

    @Test
    void iniciarSesionDejaUnaCookieHttpOnlyYPermiteVerLasFotos() throws Exception {
        HttpResponse<String> response = post("/auth/google", panelOrigin, JSON, "{\"credential\":\"token-permitido\"}", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ana@example.com"));
        String setCookie = response.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.startsWith(AuthService.COOKIE_NAME + "="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));

        String cookie = setCookie.substring(0, setCookie.indexOf(';'));
        assertEquals(200, get("/photo/a.png", cookie).statusCode());
        HttpResponse<String> me = get("/auth/me", cookie);
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("Ana"));
    }

    @Test
    void iniciarSesionExigeOrigenDelPanel() throws Exception {
        String body = "{\"credential\":\"token-permitido\"}";
        assertEquals(403, post("/auth/google", null, JSON, body, null).statusCode());
        assertEquals(403, post("/auth/google", "http://evil.example", JSON, body, null).statusCode());
    }

    @Test
    void iniciarSesionRechazaContentTypeYCuerposInvalidos() throws Exception {
        assertEquals(415, post("/auth/google", panelOrigin, "text/plain", "{}", null).statusCode());
        assertEquals(415, post("/auth/google", panelOrigin, null, "{}", null).statusCode());
        assertEquals(400, post("/auth/google", panelOrigin, JSON, "no es json", null).statusCode());
        assertEquals(400, post("/auth/google", panelOrigin, JSON, "{\"otro\":1}", null).statusCode());
        assertEquals(413, post("/auth/google", panelOrigin, JSON,
                "{\"credential\":\"" + "x".repeat(9000) + "\"}", null).statusCode());
    }

    @Test
    void unTokenQueGoogleNoValidaDa401() throws Exception {
        assertEquals(401, post("/auth/google", panelOrigin, JSON, "{\"credential\":\"token-falso\"}", null).statusCode());
    }

    @Test
    void unaCuentaValidaPeroFueraDeLaListaDa403YNoDejaCookie() throws Exception {
        HttpResponse<String> response = post("/auth/google", panelOrigin, JSON, "{\"credential\":\"token-ajeno\"}", null);
        assertEquals(403, response.statusCode());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
    }

    @Test
    void cerrarSesionInvalidaLaCookie() throws Exception {
        String cookie = login();
        assertEquals(403, post("/auth/logout", null, JSON, "", cookie).statusCode());
        assertEquals(200, get("/auth/me", cookie).statusCode(), "un logout rechazado no cierra la sesion");

        HttpResponse<String> response = post("/auth/logout", panelOrigin, JSON, "", cookie);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
        assertEquals(401, get("/auth/me", cookie).statusCode());
    }

    @Test
    void unaRutaOMetodoDeAuthDesconocidoDa404() throws Exception {
        assertEquals(404, get("/auth/otra", null).statusCode());
        assertEquals(404, get("/auth/google", null).statusCode());
        assertEquals(404, post("/auth/config", panelOrigin, JSON, "{}", null).statusCode());
    }
}