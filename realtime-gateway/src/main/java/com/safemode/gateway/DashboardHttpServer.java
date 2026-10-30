package com.safemode.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Servidor HTTP minimo: entrega la pagina del dashboard ({@code /}) y las fotos de evidencia ({@code /photo/<archivo>}).
 * Solo sirve un archivo por nombre dentro de las dos carpetas configuradas, nunca rutas del disco pedidas por el cliente.
 * <p>
 * Si se le da un {@link TrackerControl}, ademas expone el boton de vigilancia del panel: {@code GET /tracker/status},
 * {@code POST /tracker/start} y {@code POST /tracker/stop}. Como lanzan un programa, los POST exigen que la peticion
 * venga de una pagina del propio panel ({@code Origin}) y de un nombre de servidor esperado ({@code Host}, contra el
 * "DNS rebinding"); ninguna de las rutas recibe datos del navegador que lleguen al comando.
 */
public class DashboardHttpServer {

    private final HttpServer server;
    private static final int MAX_BODY_BYTES = 2048;

    private final Path dashboardDir;
    private final Path photosDir;
    private final TrackerControl tracker;     // null = sin boton de vigilancia (las rutas /tracker/* dan 404)
    private final Set<String> allowedOrigins;
    private CameraDirectory cameras;           // null = sin edicion de camaras (las rutas /cameras dan 404)
    private EventReport report;                // null = sin reporte (la ruta /report da 404)
    private AuthService auth;                   // null = sin inicio de sesion (rutas abiertas, como antes)

    public DashboardHttpServer(int port, Path dashboardDir, Path photosDir) throws IOException {
        this("localhost", port, dashboardDir, photosDir);
    }

    /** Igual, pero en la direccion dada (0.0.0.0 dentro de un contenedor). */
    public DashboardHttpServer(String host, int port, Path dashboardDir, Path photosDir) throws IOException {
        this(host, port, dashboardDir, photosDir, null, Set.of());
    }

    /**
     * @param tracker        control del tracker para el boton del panel, o {@code null} para no ofrecerlo
     * @param allowedOrigins paginas autorizadas a usar los POST (ej. {@code http://localhost:8080})
     */
    public DashboardHttpServer(String host, int port, Path dashboardDir, Path photosDir, TrackerControl tracker,
                               Set<String> allowedOrigins) throws IOException {
        this.tracker = tracker;
        this.allowedOrigins = Set.copyOf(allowedOrigins);
        this.dashboardDir = dashboardDir.toAbsolutePath().normalize();
        this.photosDir = photosDir.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handle);
    }

    /** Activa las rutas {@code GET /cameras} y {@code POST /cameras/<id>} (renombrar). Se llama antes de {@link #start()}. */
    public DashboardHttpServer withCameraDirectory(CameraDirectory directory) {
        this.cameras = directory;
        return this;
    }

    /** Activa {@code GET /report} (el reporte de eventos del panel). Se llama antes de {@link #start()}. */
    public DashboardHttpServer withEventReport(EventReport eventReport) {
        this.report = eventReport;
        return this;
    }

    /** Exige sesion de Google en la API y las fotos, y activa las rutas {@code /auth/*}. Se llama antes de {@link #start()}. */
    public DashboardHttpServer withAuth(AuthService authService) {
        this.auth = authService;
        return this;
    }

    public void start() {
        server.start();
    }

    public int boundPort() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (auth != null && path.startsWith("/auth/")) {
                handleAuth(exchange, path);
                return;
            }
            if (auth != null && requiresSession(path)
                    && auth.userFor(exchange.getRequestHeaders().getFirst("Cookie")).isEmpty()) {
                send(exchange, 401, "text/plain; charset=utf-8", "Sesion requerida".getBytes());
                return;
            }
            if (tracker != null && path.startsWith("/tracker/")) {
                handleTracker(exchange, path);
                return;
            }
            if (cameras != null && (path.equals("/cameras") || path.startsWith("/cameras/"))) {
                handleCameras(exchange, path);
                return;
            }
            if (report != null && path.equals("/report")) {
                handleReport(exchange);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "Metodo no permitido".getBytes());
                return;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                serveFile(exchange, dashboardDir, "index.html");
            } else if (path.equals("/camera-share.js") || path.equals("/tracker-control.js")
                    || path.equals("/camera-zones.js") || path.equals("/camera-names.js") || path.equals("/report.js")
                    || path.equals("/browser-notifications.js")
                    || path.equals("/mosaico-prueba.html")) {
                serveFile(exchange, dashboardDir, path.substring(1));
            } else if (path.startsWith(AlertJson.PHOTO_PREFIX)) {
                serveFile(exchange, photosDir, path.substring(AlertJson.PHOTO_PREFIX.length()));
            } else if (path.startsWith("/lens-frames/")) {
                serveFile(exchange, dashboardDir.resolve("lens-frames"), path.substring("/lens-frames/".length()));
            } else if (path.equals("/Camera_pushing_into_AI_core_20260926081000.mp4")) {
                serveFile(exchange, dashboardDir, path.substring(1));
            } else {
                send(exchange, 404, "text/plain; charset=utf-8", "No encontrado".getBytes());
            }
        } finally {
            exchange.close();
        }
    }
    private static final int MAX_AUTH_BODY_BYTES = 8192; // un ID token de Google pesa ~1-2 KB

    /** Rutas que dan datos o ejecutan acciones: exigen sesion. */
    private static boolean requiresSession(String path) {
        return path.startsWith("/tracker/") || path.equals("/cameras") || path.startsWith("/cameras/")
                || path.equals("/report") || path.startsWith(AlertJson.PHOTO_PREFIX);
    }

    private void handleAuth(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (path.equals("/auth/config") && "GET".equals(method)) {
            JsonObject json = new JsonObject();
            json.addProperty("clientId", auth.clientId());
            sendJson(exchange, 200, json);
        } else if (path.equals("/auth/me") && "GET".equals(method)) {
            Optional<AuthService.User> user = auth.userFor(cookie);
            if (user.isPresent()) {
                sendJson(exchange, 200, userJson(user.get()));
            } else {
                send(exchange, 401, "text/plain; charset=utf-8", "Sesion requerida".getBytes());
            }
        } else if (path.equals("/auth/google") && "POST".equals(method)) {
            loginWithGoogle(exchange);
        } else if (path.equals("/auth/logout") && "POST".equals(method)) {
            if (!fromOurOwnPanel(exchange)) {
                send(exchange, 403, "text/plain; charset=utf-8", "Origen no autorizado".getBytes());
                return;
            }
            auth.logout(cookie);
            exchange.getResponseHeaders().add("Set-Cookie", sessionCookie("", 0));
            sendJson(exchange, 200, new JsonObject());
        } else {
            send(exchange, 404, "text/plain; charset=utf-8", "No encontrado".getBytes());
        }
    }

    private void loginWithGoogle(HttpExchange exchange) throws IOException {
        if (!fromOurOwnPanel(exchange)) {
            send(exchange, 403, "text/plain; charset=utf-8", "Origen no autorizado".getBytes());
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            send(exchange, 415, "text/plain; charset=utf-8", "Se espera application/json".getBytes());
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_AUTH_BODY_BYTES + 1);
        if (body.length > MAX_AUTH_BODY_BYTES) {
            send(exchange, 413, "text/plain; charset=utf-8", "Cuerpo demasiado grande".getBytes());
            return;
        }
        String credential;
        try {
            credential = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonObject().get("credential").getAsString();
        } catch (RuntimeException e) {
            send(exchange, 400, "text/plain; charset=utf-8", "Se espera {\"credential\": \"...\"}".getBytes());
            return;
        }
        Optional<AuthService.User> user = auth.authenticate(credential);
        if (user.isEmpty()) {
            send(exchange, 401, "text/plain; charset=utf-8", "Token de Google no valido".getBytes());
            return;
        }
        if (!auth.isAllowed(user.get())) {
            System.err.println("[AUTH] Cuenta de Google no autorizada: " + user.get().email());
            send(exchange, 403, "text/plain; charset=utf-8", "Cuenta no autorizada".getBytes());
            return;
        }
        String session = auth.createSession(user.get());
        exchange.getResponseHeaders().add("Set-Cookie", sessionCookie(session, AuthService.SESSION_TTL.toSeconds()));
        sendJson(exchange, 200, userJson(user.get()));
    }

    /** HttpOnly: el JavaScript de la pagina no la puede leer. Strict: no viaja desde otros sitios. */
    private static String sessionCookie(String value, long maxAgeSeconds) {
        return AuthService.COOKIE_NAME + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAgeSeconds;
    }

    private static JsonObject userJson(AuthService.User user) {
        JsonObject json = new JsonObject();
        json.addProperty("email", user.email());
        json.addProperty("name", user.name());
        return json;
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject json) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void handleTracker(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if (path.equals("/tracker/status")) {
            if (!"GET".equals(method)) {
                send(exchange, 405, "text/plain; charset=utf-8", "Metodo no permitido".getBytes());
                return;
            }
            sendStatus(exchange, 200);
            return;
        }
        boolean start = path.equals("/tracker/start");
        if (!start && !path.equals("/tracker/stop")) {
            send(exchange, 404, "text/plain; charset=utf-8", "No encontrado".getBytes());
            return;
        }
        if (!"POST".equals(method)) {
            send(exchange, 405, "text/plain; charset=utf-8", "Metodo no permitido".getBytes());
            return;
        }
        if (!fromOurOwnPanel(exchange)) {
            send(exchange, 403, "text/plain; charset=utf-8", "Origen no autorizado".getBytes());
            return;
        }
        if (start) {
            int cameraCount = parseCameras(exchange.getRequestURI().getRawQuery());
            if (cameraCount < 0) {
                send(exchange, 400, "text/plain; charset=utf-8", "Numero de camaras invalido".getBytes());
                return;
            }
            try {
                tracker.start(cameraCount);
            } catch (IllegalStateException e) {
                sendStatus(exchange, 409);
                return;
            } catch (IOException e) {
                System.err.println("[GATEWAY] No se pudo lanzar el tracker: " + e.getMessage());
                send(exchange, 500, "text/plain; charset=utf-8", "No se pudo iniciar el tracker".getBytes());
                return;
            }
        } else {
            tracker.requestStop();
        }
        sendStatus(exchange, 200);
    }

    /**
     * Numero de camaras pedido en {@code ?cameras=N}: 1 si no viene, el entero (de 1 a 8) si es valido y -1 si no lo es. Solo
     * se aceptan digitos: el valor llega a una variable de entorno del tracker y nunca debe ser texto libre.
     */
    static int parseCameras(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return 1;
        }
        String[] parts = rawQuery.split("&");
        if (parts.length != 1 || !parts[0].startsWith("cameras=")) {
            return -1;
        }
        String value = parts[0].substring("cameras=".length());
        return value.matches("[1-8]") ? Integer.parseInt(value) : -1;
    }

    private void handleCameras(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if (path.equals("/cameras")) {
            if (!"GET".equals(method)) {
                send(exchange, 405, "text/plain; charset=utf-8", "Metodo no permitido".getBytes());
                return;
            }
            sendCameras(exchange, 200);
            return;
        }
        String idText = path.substring("/cameras/".length());
        if (!idText.matches("[1-9]")) {
            send(exchange, 404, "text/plain; charset=utf-8", "No encontrado".getBytes());
            return;
        }
        if (!"POST".equals(method)) {
            send(exchange, 405, "text/plain; charset=utf-8", "Metodo no permitido".getBytes());
            return;
        }
        if (!fromOurOwnPanel(exchange)) {
            send(exchange, 403, "text/plain; charset=utf-8", "Origen no autorizado".getBytes());
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            send(exchange, 415, "text/plain; charset=utf-8", "Se espera application/json".getBytes());
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            send(exchange, 413, "text/plain; charset=utf-8", "Cuerpo demasiado grande".getBytes());
            return;
        }
        String name;
        try {
            var json = com.google.gson.JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            name = json.get("name").getAsString();
        } catch (RuntimeException e) {
            send(exchange, 400, "text/plain; charset=utf-8", "Se espera {\"name\": \"...\"}".getBytes());
            return;
        }
        try {
            cameras.rename(Integer.parseInt(idText), name);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "text/plain; charset=utf-8", e.getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        sendCameras(exchange, 200);
    }

    /**
     * {@code GET /report}: los ultimos eventos con su detalle, en JSON. Solo lectura y sin parametros del navegador. Como lleva
     * descripciones de personas, exige un {@code Host} esperado (contra el "DNS rebinding", igual que los POST).
     */
    private void handleReport(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "Metodo no permitido".getBytes());
            return;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !allowedOrigins.contains("http://" + host)) {
            send(exchange, 403, "text/plain; charset=utf-8", "Origen no autorizado".getBytes());
            return;
        }
        String json = ReportJson.toJson(report.recent(ReportJson.MAX_ROWS), report::cameraName).toString();
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, 200, "application/json; charset=utf-8", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void sendCameras(HttpExchange exchange, int httpStatus) throws IOException {
        var array = new com.google.gson.JsonArray();
        for (Map.Entry<Integer, String> camera : cameras.names().entrySet()) {
            var json = new com.google.gson.JsonObject();
            json.addProperty("id", camera.getKey());
            json.addProperty("name", camera.getValue());
            array.add(json);
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, httpStatus, "application/json; charset=utf-8", array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Un POST valido trae la cabecera Origin de una pagina del panel y un Host esperado (nada de otros sitios web ni de otros nombres). */
    private boolean fromOurOwnPanel(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String host = exchange.getRequestHeaders().getFirst("Host");
        return origin != null && allowedOrigins.contains(origin) && host != null && allowedOrigins.contains("http://" + host);
    }

    private void sendStatus(HttpExchange exchange, int httpStatus) throws IOException {
        TrackerControl.Status status = tracker.status();
        String json = "{\"state\":\"" + status.state() + "\",\"exitCode\":" + status.exitCode() + "}";
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); // el estado cambia: nunca reutilizar uno viejo
        send(exchange, httpStatus, "application/json; charset=utf-8", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void serveFile(HttpExchange exchange, Path baseDir, String name) throws IOException {
        Path file = baseDir.resolve(name).normalize();
        // solo archivos directamente dentro de la carpeta: rechaza "..", subcarpetas y rutas absolutas
        if (!baseDir.equals(file.getParent()) || !Files.isRegularFile(file)) {
            send(exchange, 404, "text/plain; charset=utf-8", "No encontrado".getBytes());
            return;
        }
        send(exchange, 200, contentType(file.getFileName().toString()), Files.readAllBytes(file));
    }

    static String contentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
