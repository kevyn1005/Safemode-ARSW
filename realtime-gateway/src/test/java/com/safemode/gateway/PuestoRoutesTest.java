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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las rutas del panel hacia la configuracion compartida del puesto y la presencia (paso 1 de la integracion). El gateway NO
 * decide nada del puesto: pone la identidad (de la sesion de Google), exige sesion y origen del panel, y reenvia. Aqui se
 * comprueba justo eso, con un servicio de colaboracion falso.
 */
class PuestoRoutesTest {

    private static final String JSON = "application/json";

    @TempDir
    Path tmp;

    private final HttpClient client = HttpClient.newHttpClient();
    private final FakePuesto api = new FakePuesto();
    private final Map<String, String> operatorsByEmail = Map.of("ana@example.com", "ana");
    private boolean directoryDown;
    private DashboardHttpServer server;
    private String panelOrigin;

    /** El servicio de colaboracion falso: anota lo que le llega y responde lo configurado. */
    static final class FakePuesto implements PuestoApi {
        final List<String> calls = new ArrayList<>();
        Reply reply = new Reply(200, "{\"id\":\"puesto-1\",\"version\":3}");

        @Override
        public Reply puesto() {
            calls.add("puesto");
            return reply;
        }

        @Override
        public Reply permission(String operator) {
            calls.add("permission:" + operator);
            return new Reply(200, "{\"operator\":\"" + operator + "\",\"canConfigure\":true,\"source\":\"authority\"}");
        }

        @Override
        public Reply save(String operator, String body) {
            calls.add("save:" + operator + ":" + body);
            return reply;
        }

        @Override
        public Reply touchPresence(String operator) {
            calls.add("touch:" + operator);
            return new Reply(200, "{\"available\":true,\"operator\":\"" + operator + "\"}");
        }

        @Override
        public Reply presence() {
            calls.add("presence");
            return new Reply(200, "{\"available\":true,\"operators\":[\"ana\"]}");
        }

        @Override
        public Reply history(Long before, int limit) {
            calls.add("history:" + before + ":" + limit);
            return new Reply(200, "{\"items\":[{\"version\":4,\"by\":\"ana\"}],\"nextBefore\":null}");
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        Path dashboard = Files.createDirectories(tmp.resolve("dashboard"));
        Path photos = Files.createDirectories(tmp.resolve("photos"));
        Files.writeString(dashboard.resolve("index.html"), "<html>panel</html>");
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        panelOrigin = "http://localhost:" + port;
        server = newServer(port, true);
        server.start();
    }

    private DashboardHttpServer newServer(int port, boolean withPuesto) throws IOException {
        Path dashboard = tmp.resolve("dashboard");
        AuthService.TokenVerifier verifier = token -> switch (token) {
            case "token-ana" -> Optional.of(new AuthService.User("ana@example.com", "Ana"));
            case "token-sin-alta" -> Optional.of(new AuthService.User("sin-alta@example.com", "Sin Alta"));
            default -> Optional.empty();
        };
        AuthService auth = new AuthService("client-123", Set.of("ana@example.com", "sin-alta@example.com"), verifier, Clock.systemUTC());
        DashboardHttpServer http = new DashboardHttpServer("localhost", port, dashboard, tmp.resolve("photos"), null, Set.of("http://localhost:" + port))
                .withAuth(auth);
        if (withPuesto) {
            http.withPuesto(api, email -> {
                if (directoryDown) {
                    throw new IllegalStateException("Gestion de acceso caida");
                }
                return Optional.ofNullable(operatorsByEmail.get(email));
            });
        }
        return http;
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> send(String method, String path, String origin, String contentType, String body, String cookie)
            throws Exception {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(panelOrigin + path)).method(method, publisher);
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

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return send("GET", path, null, null, null, cookie);
    }

    private String login(String token) throws Exception {
        HttpResponse<String> response = send("POST", "/auth/google", panelOrigin, JSON, "{\"credential\":\"" + token + "\"}", null);
        assertEquals(200, response.statusCode());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    @Test
    void sinSesionTodasLasRutasDelPuestoDan401YNoLlegaNadaAlServicio() throws Exception {
        assertEquals(401, get("/puesto", null).statusCode());
        assertEquals(401, get("/puesto/permission", null).statusCode());
        assertEquals(401, get("/presence", null).statusCode());
        assertEquals(401, get("/puesto/history", null).statusCode());
        assertEquals(401, send("PUT", "/puesto", panelOrigin, JSON, "{}", null).statusCode());
        assertEquals(401, send("POST", "/presence", panelOrigin, null, null, null).statusCode());
        assertTrue(api.calls.isEmpty(), "sin sesion no se llama al servicio: " + api.calls);
    }

    @Test
    void leerElPuestoReenviaLaRespuestaDelServicioSinCache() throws Exception {
        String cookie = login("token-ana");
        HttpResponse<String> response = get("/puesto", cookie);

        assertEquals(200, response.statusCode());
        assertEquals("{\"id\":\"puesto-1\",\"version\":3}", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    @Test
    void elOperadorSaleDeLaSesionNuncaDeLoQueMandeElNavegador() throws Exception {
        String cookie = login("token-ana");
        String body = "{\"expectedVersion\":3,\"title\":\"Puesto 1\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(panelOrigin + "/puesto"))
                .header("Origin", panelOrigin).header("Content-Type", JSON).header("Cookie", cookie)
                .header("X-Operator", "luis") // intento de hacerse pasar por otro operador
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(List.of("save:ana:" + body), api.calls, "se reenvia el cuerpo tal cual y con el operador de la sesion");
    }

    @Test
    void unConflictoDeVersionLlegaAlPanelComo409ConSuMensaje() throws Exception {
        api.reply = new PuestoApi.Reply(409, "{\"error\":\"La version cambio\",\"currentVersion\":4}");
        String cookie = login("token-ana");

        HttpResponse<String> response = send("PUT", "/puesto", panelOrigin, JSON, "{\"expectedVersion\":3,\"title\":\"X\"}", cookie);

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("\"currentVersion\":4"));
    }

    @Test
    void guardarExigeOrigenDelPanelJsonYUnCuerpoChico() throws Exception {
        String cookie = login("token-ana");
        String body = "{\"expectedVersion\":1,\"title\":\"X\"}";

        assertEquals(403, send("PUT", "/puesto", null, JSON, body, cookie).statusCode());
        assertEquals(403, send("PUT", "/puesto", "http://evil.example", JSON, body, cookie).statusCode());
        assertEquals(415, send("PUT", "/puesto", panelOrigin, "text/plain", body, cookie).statusCode());
        assertEquals(415, send("PUT", "/puesto", panelOrigin, null, body, cookie).statusCode());
        assertEquals(413, send("PUT", "/puesto", panelOrigin, JSON, "{\"title\":\"" + "x".repeat(9000) + "\"}", cookie).statusCode());
        assertTrue(api.calls.isEmpty(), "ningun rechazo llega al servicio: " + api.calls);
    }

    @Test
    void unaCuentaAutorizadaQueNoEsOperadorPuedeVerPeroNoConfigurarNiTenerPresencia() throws Exception {
        String cookie = login("token-sin-alta");

        assertEquals(200, get("/puesto", cookie).statusCode());
        HttpResponse<String> permission = get("/puesto/permission", cookie);
        assertEquals(200, permission.statusCode());
        assertTrue(permission.body().contains("\"canConfigure\":false"));
        assertTrue(permission.body().contains("\"operator\":null"));
        assertEquals(403, send("PUT", "/puesto", panelOrigin, JSON, "{\"expectedVersion\":1,\"title\":\"X\"}", cookie).statusCode());
        assertEquals(403, send("POST", "/presence", panelOrigin, null, null, cookie).statusCode());
        assertEquals(List.of("puesto"), api.calls, "solo la lectura publica llego al servicio");
    }

    @Test
    void conGestionDeAccesoCaidaSeRechazaConLaFalla503YNoSeReenvia() throws Exception {
        String cookie = login("token-ana");
        directoryDown = true;

        HttpResponse<String> save = send("PUT", "/puesto", panelOrigin, JSON, "{\"expectedVersion\":1,\"title\":\"X\"}", cookie);
        assertEquals(503, save.statusCode());
        assertTrue(save.body().contains("Gestion de acceso no disponible"));
        assertEquals(503, send("POST", "/presence", panelOrigin, null, null, cookie).statusCode());
        assertEquals(503, get("/puesto/permission", cookie).statusCode());
        assertEquals(200, get("/puesto", cookie).statusCode(), "leer el puesto no necesita saber quien es el operador");
        assertEquals(List.of("puesto"), api.calls);
    }

    @Test
    void laPresenciaUsaElOperadorDeLaSesion() throws Exception {
        String cookie = login("token-ana");

        assertEquals(200, send("POST", "/presence", panelOrigin, null, null, cookie).statusCode());
        assertEquals(403, send("POST", "/presence", null, null, null, cookie).statusCode(), "el latido exige el origen del panel");
        HttpResponse<String> list = get("/presence", cookie);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"operators\":[\"ana\"]"));
        HttpResponse<String> permission = get("/puesto/permission", cookie);
        assertTrue(permission.body().contains("\"canConfigure\":true"));

        assertEquals(List.of("touch:ana", "presence", "permission:ana"), api.calls);
    }

    @Test
    void elHistorialSeLeeConSesionConCursorYSinNecesitarSerOperador() throws Exception {
        String cookie = login("token-sin-alta"); // ve el puesto aunque no sea operador

        HttpResponse<String> first = get("/puesto/history", cookie);
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("\"version\":4"));
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(200, get("/puesto/history?limit=5&before=7", cookie).statusCode());
        assertEquals(200, get("/puesto/history?before=7", cookie).statusCode());

        assertEquals(List.of("history:null:20", "history:7:5", "history:7:20"), api.calls);
    }

    @Test
    void laConsultaDelHistorialSoloAceptaBeforeYLimitConNumerosEnRango() throws Exception {
        String cookie = login("token-ana");
        for (String bad : new String[] {"limit=0", "limit=51", "limit=-1", "limit=abc", "limit=", "before=0", "before=abc", "before=",
                "before=1234567890123", "foo=1", "limit=5&limit=6", "before=1&before=2", "limit=5&x=1", "limit=5&", "&", "before=1%27"}) {
            assertEquals(400, get("/puesto/history?" + bad, cookie).statusCode(), bad);
        }
        assertTrue(api.calls.isEmpty(), "ninguna consulta invalida llega al servicio: " + api.calls);
        assertEquals(405, send("POST", "/puesto/history", panelOrigin, JSON, "{}", cookie).statusCode());
        assertEquals(405, send("DELETE", "/puesto/history", panelOrigin, null, null, cookie).statusCode());
    }

    @Test
    void unMetodoQueLaRutaNoAdmiteDa405() throws Exception {
        String cookie = login("token-ana");
        assertEquals(405, send("DELETE", "/puesto", panelOrigin, null, null, cookie).statusCode());
        assertEquals(405, send("POST", "/puesto", panelOrigin, JSON, "{}", cookie).statusCode());
        assertEquals(405, send("PUT", "/presence", panelOrigin, JSON, "{}", cookie).statusCode());
        assertEquals(405, send("POST", "/puesto/permission", panelOrigin, JSON, "{}", cookie).statusCode());
        assertTrue(api.calls.isEmpty());
    }

    @Test
    void sinPuestoConfiguradoLasRutasNoExisten() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        DashboardHttpServer plain = newServer(port, false);
        plain.start();
        try {
            String origin = "http://localhost:" + port;
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(origin + "/auth/google"))
                    .header("Origin", origin).header("Content-Type", JSON)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"credential\":\"token-ana\"}")).build(), HttpResponse.BodyHandlers.ofString());
            String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
            String cookie = setCookie.substring(0, setCookie.indexOf(';'));

            for (String path : List.of("/puesto", "/puesto/permission", "/puesto/history", "/presence")) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(origin + path))
                        .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, response.statusCode(), path);
            }
        } finally {
            plain.stop();
        }
    }
}
