package com.safemode.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

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
    private final Path dashboardDir;
    private final Path photosDir;
    private final TrackerControl tracker;     // null = sin boton de vigilancia (las rutas /tracker/* dan 404)
    private final Set<String> allowedOrigins;

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
            if (tracker != null && path.startsWith("/tracker/")) {
                handleTracker(exchange, path);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "Metodo no permitido".getBytes());
                return;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                serveFile(exchange, dashboardDir, "index.html");
            } else if (path.equals("/camera-share.js") || path.equals("/tracker-control.js")) {
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
            try {
                tracker.start();
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
