package com.safemode.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Servidor HTTP minimo: entrega la pagina del dashboard ({@code /}) y las fotos de evidencia ({@code /photo/<archivo>}).
 * Solo sirve un archivo por nombre dentro de las dos carpetas configuradas, nunca rutas del disco pedidas por el cliente.
 */
public class DashboardHttpServer {

    private final HttpServer server;
    private final Path dashboardDir;
    private final Path photosDir;

    public DashboardHttpServer(int port, Path dashboardDir, Path photosDir) throws IOException {
        this.dashboardDir = dashboardDir.toAbsolutePath().normalize();
        this.photosDir = photosDir.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
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
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "Metodo no permitido".getBytes());
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                serveFile(exchange, dashboardDir, "index.html");
            } else if (path.startsWith(AlertJson.PHOTO_PREFIX)) {
                serveFile(exchange, photosDir, path.substring(AlertJson.PHOTO_PREFIX.length()));
            } else {
                send(exchange, 404, "text/plain; charset=utf-8", "No encontrado".getBytes());
            }
        } finally {
            exchange.close();
        }
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
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
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
