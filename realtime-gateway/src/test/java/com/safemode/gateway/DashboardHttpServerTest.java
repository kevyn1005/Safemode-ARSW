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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Files.writeString(dashboard.resolve("camera-share.js"), "// compartir");
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
    void entregaElScriptParaCompartirLaCamara() throws Exception {
        HttpResponse<byte[]> response = get("/camera-share.js");
        assertEquals(200, response.statusCode());
        assertEquals("// compartir", new String(response.body()));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
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
    // ---- boton de vigilancia (/tracker/*) ----

    /** Control falso: cuenta las llamadas y guarda el estado que se le diga. */
    private static class FakeTracker implements TrackerControl {
        int starts, stops, lastCameras = -1;
        State state = State.STOPPED;
        boolean failToStart;

        @Override public Status status() { return new Status(state, -1); }

        @Override public void start() throws IOException {
            if (failToStart) throw new IOException("no hay mvn");
            if (state == State.RUNNING) throw new IllegalStateException("ya corre");
            starts++;
            state = State.RUNNING;
        }

        @Override public void start(int cameras) throws IOException {
            start();
            lastCameras = cameras;
        }

        @Override public void requestStop() {
            stops++;
            state = State.STOPPED;
        }
    }

    private DashboardHttpServer trackerServer;
    private FakeTracker fake;
    private String panelOrigin;

    private void startServerWithTracker() throws IOException {
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();   // el Origin autorizado necesita conocer el puerto de antemano
        }
        panelOrigin = "http://localhost:" + port;
        fake = new FakeTracker();
        trackerServer = new DashboardHttpServer("localhost", port, tmp.resolve("dashboard"), tmp.resolve("photos"), fake,
                java.util.Set.of(panelOrigin));
        trackerServer.start();
    }

    @AfterEach
    void stopTrackerServer() {
        if (trackerServer != null) {
            trackerServer.stop();
        }
    }

    private HttpResponse<String> send(String method, String path, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(panelOrigin + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(String path, String origin, String contentType, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(panelOrigin + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Directorio de camaras falso: guarda los nombres en un mapa y valida como lo haria la base. */
    private static class FakeCameras implements CameraDirectory {
        final java.util.Map<Integer, String> names = new java.util.TreeMap<>(java.util.Map.of(1, "CAM 1", 2, "CAM 2"));

        @Override public java.util.Map<Integer, String> names() { return names; }

        @Override public void rename(int cameraId, String name) {
            if (!names.containsKey(cameraId)) {
                throw new IllegalArgumentException("Camara fuera de rango: " + cameraId);
            }
            if (name.strip().length() > 10) {
                throw new IllegalArgumentException("Nombre demasiado largo");
            }
            names.put(cameraId, name.isBlank() ? "CAM " + cameraId : name.strip());
        }
    }

    private FakeCameras startServerWithCameras() throws IOException {
        startServerWithTracker();
        trackerServer.stop();
        int port = Integer.parseInt(panelOrigin.substring(panelOrigin.lastIndexOf(':') + 1));
        FakeCameras directory = new FakeCameras();
        trackerServer = new DashboardHttpServer("localhost", port, tmp.resolve("dashboard"), tmp.resolve("photos"), fake,
                java.util.Set.of(panelOrigin)).withCameraDirectory(directory);
        trackerServer.start();
        return directory;
    }

    @Test
    void iniciarPuedePedirDosCamaras() throws Exception {
        startServerWithTracker();
        HttpResponse<String> response = send("POST", "/tracker/start?cameras=2", panelOrigin);
        assertEquals(200, response.statusCode());
        assertEquals(2, fake.lastCameras);
    }

    @Test
    void iniciarSinCamarasEsUnaCamara() throws Exception {
        startServerWithTracker();
        assertEquals(200, send("POST", "/tracker/start", panelOrigin).statusCode());
        assertEquals(1, fake.lastCameras);
    }

    @Test
    void unNumeroDeCamarasQueNoEsUnDigitoValidoSeRechazaSinLanzarNada() throws Exception {
        startServerWithTracker();
        for (String bad : new String[]{"0", "9", "10", "-1", "abc", "2;rm", "1&x=1", "", "%32"}) {
            assertEquals(400, send("POST", "/tracker/start?cameras=" + bad, panelOrigin).statusCode(), "cameras=" + bad);
        }
        assertEquals(400, send("POST", "/tracker/start?otra=1", panelOrigin).statusCode());
        assertEquals(0, fake.starts);
    }

    @Test
    void sinDirectorioDeCamarasLasRutasDeCamarasNoExisten() throws Exception {
        startServerWithTracker();
        assertEquals(404, send("GET", "/cameras", null).statusCode());
    }

    @Test
    void sePuedenListarLasCamaras() throws Exception {
        startServerWithCameras();
        HttpResponse<String> response = send("GET", "/cameras", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"name\":\"CAM 2\""));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void sePuedeRenombrarUnaCamaraDesdeElPropioPanel() throws Exception {
        FakeCameras directory = startServerWithCameras();
        HttpResponse<String> response = sendJson("/cameras/2", panelOrigin, "application/json", "{\"name\":\"  Cancha sur \"}");
        assertEquals(200, response.statusCode());
        assertEquals("Cancha sur", directory.names.get(2));
        assertTrue(response.body().contains("Cancha sur"));
    }

    @Test
    void renombrarExigeOrigenDelPanelYJson() throws Exception {
        FakeCameras directory = startServerWithCameras();
        assertEquals(403, sendJson("/cameras/2", null, "application/json", "{\"name\":\"x\"}").statusCode());
        assertEquals(403, sendJson("/cameras/2", "http://evil.example", "application/json", "{\"name\":\"x\"}").statusCode());
        assertEquals(415, sendJson("/cameras/2", panelOrigin, "text/plain", "{\"name\":\"x\"}").statusCode());
        assertEquals(415, sendJson("/cameras/2", panelOrigin, null, "{\"name\":\"x\"}").statusCode());
        assertEquals("CAM 2", directory.names.get(2), "ninguna de las peticiones rechazadas cambia el nombre");
    }

    @Test
    void renombrarRechazaCuerposIdsYNombresInvalidos() throws Exception {
        FakeCameras directory = startServerWithCameras();
        assertEquals(400, sendJson("/cameras/2", panelOrigin, "application/json", "no es json").statusCode());
        assertEquals(400, sendJson("/cameras/2", panelOrigin, "application/json", "{\"otro\":1}").statusCode());
        assertEquals(400, sendJson("/cameras/2", panelOrigin, "application/json", "{\"name\":\"un nombre muy largo\"}").statusCode());
        assertEquals(400, sendJson("/cameras/9", panelOrigin, "application/json", "{\"name\":\"x\"}").statusCode());
        assertEquals(404, sendJson("/cameras/abc", panelOrigin, "application/json", "{\"name\":\"x\"}").statusCode());
        assertEquals(404, sendJson("/cameras/2/../1", panelOrigin, "application/json", "{\"name\":\"x\"}").statusCode());
        assertEquals(413, sendJson("/cameras/2", panelOrigin, "application/json",
                "{\"name\":\"" + "x".repeat(3000) + "\"}").statusCode());
        assertEquals("CAM 2", directory.names.get(2));
    }

    @Test
    void renombrarSoloAceptaPostYListarSoloGet() throws Exception {
        startServerWithCameras();
        assertEquals(405, send("GET", "/cameras/2", panelOrigin).statusCode());
        assertEquals(405, send("POST", "/cameras", panelOrigin).statusCode());
    }

    @Test
    void sinControlDeTrackerLasRutasDeTrackerNoExisten() throws Exception {
        assertEquals(404, get("/tracker/status").statusCode());
    }

    @Test
    void elEstadoDelTrackerSeConsultaSinPermisosEspeciales() throws Exception {
        startServerWithTracker();
        HttpResponse<String> response = send("GET", "/tracker/status", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"state\":\"STOPPED\""));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void iniciarYDetenerDesdeElPropioPanel() throws Exception {
        startServerWithTracker();
        HttpResponse<String> started = send("POST", "/tracker/start", panelOrigin);
        assertEquals(200, started.statusCode());
        assertTrue(started.body().contains("RUNNING"));
        assertEquals(1, fake.starts);

        assertEquals(200, send("POST", "/tracker/stop", panelOrigin).statusCode());
        assertEquals(1, fake.stops);
    }

    @Test
    void iniciarDosVecesDaConflictoYNoLanzaOtro() throws Exception {
        startServerWithTracker();
        send("POST", "/tracker/start", panelOrigin);
        HttpResponse<String> again = send("POST", "/tracker/start", panelOrigin);
        assertEquals(409, again.statusCode());
        assertEquals(1, fake.starts);
    }

    @Test
    void unPostSinOrigenOConOrigenAjenoSeRechazaYNoHaceNada() throws Exception {
        startServerWithTracker();
        assertEquals(403, send("POST", "/tracker/start", null).statusCode());
        assertEquals(403, send("POST", "/tracker/start", "http://evil.example").statusCode());
        assertEquals(403, send("POST", "/tracker/stop", "http://localhost:1").statusCode());
        assertEquals(0, fake.starts);
        assertEquals(0, fake.stops);
    }

    @Test
    void unPostConHostRaroSeRechazaContraDnsRebinding() throws Exception {
        startServerWithTracker();
        int port = trackerServer.boundPort();
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            String request = "POST /tracker/start HTTP/1.1\r\nHost: evil.example:" + port + "\r\nOrigin: " + panelOrigin
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes());
            socket.getOutputStream().flush();
            String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())).readLine();
            assertTrue(statusLine.contains("403"), statusLine);
        }
        assertEquals(0, fake.starts);
    }

    @Test
    void iniciarYDetenerSoloAceptanPost() throws Exception {
        startServerWithTracker();
        assertEquals(405, send("GET", "/tracker/start", panelOrigin).statusCode());
        assertEquals(405, send("GET", "/tracker/stop", panelOrigin).statusCode());
        assertEquals(405, send("POST", "/tracker/status", panelOrigin).statusCode());
        assertEquals(0, fake.starts);
    }

    @Test
    void siNoSePuedeLanzarElTrackerElErrorNoRevelaDetalles() throws Exception {
        startServerWithTracker();
        fake.failToStart = true;
        HttpResponse<String> response = send("POST", "/tracker/start", panelOrigin);
        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("mvn"));
    }

    @Test
    void entregaLosScriptsYLaPaginaDePruebaDelPanel() throws Exception {
        startServerWithTracker();
        for (String name : new String[]{"camera-zones.js", "camera-names.js", "camera-share.js", "tracker-control.js"}) {
            Files.writeString(tmp.resolve("dashboard").resolve(name), "// " + name);
            HttpResponse<String> response = send("GET", "/" + name, null);
            assertEquals(200, response.statusCode(), name);
            assertEquals("// " + name, response.body());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"), name);
        }
        Files.writeString(tmp.resolve("dashboard").resolve("mosaico-prueba.html"), "<html>mosaico</html>");
        HttpResponse<String> page = send("GET", "/mosaico-prueba.html", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertEquals(404, send("GET", "/otro-script.js", null).statusCode(), "solo se entregan los archivos de la lista");
    }

    @Test
    void entregaElScriptDelBotonDeVigilancia() throws Exception {
        startServerWithTracker();
        Files.writeString(tmp.resolve("dashboard").resolve("tracker-control.js"), "// boton");
        HttpResponse<String> response = send("GET", "/tracker-control.js", null);
        assertEquals(200, response.statusCode());
        assertEquals("// boton", response.body());
    }
}
