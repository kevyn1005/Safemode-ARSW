package com.safemode.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP minimo con rutas por plantilla ({@code /api/puestos/{id}}), igual de sencillo que el
 * {@code DashboardHttpServer} del gateway (sin frameworks). Hace por todos los servicios lo que no deben repetir:
 * <ul>
 *   <li>responde {@code /metrics} (Prometheus) y {@code /health} (vivo);</li>
 *   <li>mide cada peticion con la plantilla de la ruta, el metodo y el codigo (salvo {@code /health} y {@code /metrics});</li>
 *   <li>convierte {@link HttpError} en su codigo y cualquier otro error en un 500 sin filtrar detalles al cliente.</li>
 * </ul>
 */
public final class MiniHttpServer implements AutoCloseable {

    /** Un manejador de ruta: devuelve la respuesta o lanza {@link HttpError}. */
    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    /** La peticion ya separada: parametros de la ruta, consulta, cabeceras (en minusculas) y cuerpo. */
    public record Request(String method, String path, Map<String, String> pathParams, Map<String, String> query,
                          Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String param(String name) {
            return pathParams.get(name);
        }
    }

    public record Response(int status, String contentType, byte[] body) {

        public static Response json(int status, Object value) {
            return new Response(status, "application/json; charset=utf-8",
                    Json.GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
        }

        public static Response text(int status, String text) {
            return new Response(status, "text/plain; version=0.0.4; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record Route(String method, String pattern, String[] segments, Handler handler) {}

    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final HttpServer server;
    private final ServiceMetrics metrics;
    private final ExecutorService pool;
    private final List<Route> routes = new ArrayList<>();

    /** @param port 0 = un puerto libre (pruebas); ver {@link #boundPort()} */
    public MiniHttpServer(String host, int port, ServiceMetrics metrics, int threads) throws IOException {
        this.metrics = metrics;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.pool = Executors.newFixedThreadPool(threads);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        route("GET", "/health", request -> Response.json(200, Map.of("service", metrics.service(), "alive", true)));
        route("GET", "/metrics", request -> Response.text(200, metrics.scrape()));
    }

    /** Agrega una ruta; la plantilla usa {@code {nombre}} para los segmentos variables. Antes de {@link #start()}. */
    public MiniHttpServer route(String method, String pattern, Handler handler) {
        routes.add(new Route(method, pattern, split(pattern), handler));
        return this;
    }

    public void start() {
        server.start();
    }

    public int boundPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String routeLabel = "unmatched";
        int status = 500;
        try {
            Response response;
            try {
                Matched matched = match(method, path);
                routeLabel = matched.label();
                if (matched.route() == null) {
                    response = Response.json(matched.pathExists() ? 405 : 404,
                            Map.of("error", matched.pathExists() ? "Metodo no permitido" : "No encontrado"));
                } else {
                    byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                    if (body.length > MAX_BODY_BYTES) {
                        throw new HttpError(413, "Cuerpo demasiado grande");
                    }
                    Request request = new Request(method, path, matched.params(), parseQuery(exchange.getRequestURI().getRawQuery()),
                            lowerCaseHeaders(exchange), new String(body, StandardCharsets.UTF_8));
                    response = matched.route().handler().handle(request);
                }
            } catch (HttpError e) {
                response = Response.json(e.status(), Map.of("error", e.getMessage()));
            } catch (Exception e) {
                // el detalle va al registro del servidor; al cliente no se le filtran clases ni mensajes internos
                System.err.println("[" + metrics.service() + "] Error no previsto en " + method + " " + path + ": " + e);
                response = Response.json(500, Map.of("error", "Error interno"));
            }
            status = response.status();
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, response.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response.body());
            }
        } finally {
            exchange.close();
            if (!path.equals("/health") && !path.equals("/metrics")) {
                metrics.http(method, routeLabel, status, System.nanoTime() - started);
            }
        }
    }

    private record Matched(Route route, String label, Map<String, String> params, boolean pathExists) {}

    private Matched match(String method, String path) {
        String[] actual = split(path);
        String pathLabel = null;
        for (Route route : routes) {
            Map<String, String> params = bind(route.segments(), actual);
            if (params == null) {
                continue;
            }
            if (route.method().equals(method)) {
                return new Matched(route, route.pattern(), params, true);
            }
            pathLabel = route.pattern();
        }
        return new Matched(null, pathLabel == null ? "unmatched" : pathLabel, Map.of(), pathLabel != null);
    }

    private static Map<String, String> bind(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i].startsWith("{") && pattern[i].endsWith("}")) {
                params.put(pattern[i].substring(1, pattern[i].length() - 1), actual[i]);
            } else if (!pattern[i].equals(actual[i])) {
                return null;
            }
        }
        return params;
    }

    private static String[] split(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.toArray(new String[0]);
    }

    private static Map<String, String> lowerCaseHeaders(HttpExchange exchange) {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        return headers;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            query.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }
}
