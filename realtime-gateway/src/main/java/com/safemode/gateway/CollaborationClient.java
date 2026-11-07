package com.safemode.gateway;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cliente HTTP del servicio de colaboracion. Las rutas son FIJAS ({@code /api/puestos/<puesto>}, {@code /api/permissions/...},
 * {@code /api/presence/...}): del navegador solo llega el cuerpo de un cambio, que el servicio valida campo por campo. La
 * identidad viaja en {@code X-Operator} y solo si cumple el formato de identificador (nada que pueda inyectar cabeceras).
 *
 * <p>Fallar cerrado: con tiempos cortos (2 s para conectar, 3 s por respuesta), si el servicio no responde la respuesta es 503
 * y nunca se inventa un permiso. Solo se reenvian los codigos que el panel debe ver ({@code 200, 400, 403, 404, 409, 503});
 * cualquier otro (incluido un 401 del servicio, que el panel confundiria con "sesion vencida") se vuelve 502.
 */
public final class CollaborationClient implements PuestoApi {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_-]{1,40}");
    private static final Set<Integer> FORWARDED = Set.of(200, 400, 403, 404, 409, 503);
    private static final int MAX_REPLY_BYTES = 256 * 1024;

    private final URI base;
    private final String puestoId;
    private final HttpClient http;
    private final Duration requestTimeout;

    /** @param baseUrl direccion del servicio, p. ej. {@code http://collaboration-service:8110}; {@code puestoId} p. ej. {@code puesto-1} */
    public CollaborationClient(String baseUrl, String puestoId) {
        this(baseUrl, puestoId, Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    CollaborationClient(String baseUrl, String puestoId, Duration connectTimeout, Duration requestTimeout) {
        URI uri = URI.create(baseUrl.trim());
        boolean httpScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!httpScheme || uri.getHost() == null || !(path.isEmpty() || path.equals("/"))) {
            throw new IllegalArgumentException("La direccion del servicio de colaboracion debe ser http(s)://host[:puerto] sin ruta");
        }
        if (!IDENTIFIER.matcher(puestoId).matches()) {
            throw new IllegalArgumentException("Identificador de puesto invalido: use minusculas, digitos, '-' o '_' (maximo 40)");
        }
        this.base = URI.create(uri.getScheme() + "://" + uri.getAuthority());
        this.puestoId = puestoId;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public Reply puesto() {
        return call("GET", "/api/puestos/" + puestoId, null, null);
    }

    @Override
    public Reply permission(String operator) {
        return call("GET", "/api/permissions/" + puestoId, operator, null);
    }

    @Override
    public Reply save(String operator, String body) {
        return call("PUT", "/api/puestos/" + puestoId, operator, body);
    }

    @Override
    public Reply touchPresence(String operator) {
        return call("POST", "/api/presence/" + puestoId, operator, null);
    }

    @Override
    public Reply presence() {
        return call("GET", "/api/presence/" + puestoId, null, null);
    }

    private Reply call(String method, String path, String operator, String body) {
        if (operator != null && !IDENTIFIER.matcher(operator).matches()) {
            return error(403, "Operador no valido");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (operator != null) {
            request.header("X-Operator", operator);
        }
        switch (method) {
            case "PUT" -> request.header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            case "POST" -> request.POST(HttpRequest.BodyPublishers.noBody());
            default -> request.GET();
        }
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_REPLY_BYTES + 1);
                if (bytes.length > MAX_REPLY_BYTES || !FORWARDED.contains(response.statusCode())) {
                    System.err.println("[PUESTO] Respuesta inesperada del servicio de colaboracion: HTTP " + response.statusCode());
                    return error(502, "Respuesta inesperada del servicio de colaboracion");
                }
                return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (IOException | RuntimeException e) {
            // solo la clase: el mensaje podria traer la direccion interna del servicio
            System.err.println("[PUESTO] No se pudo consultar el servicio de colaboracion: " + e.getClass().getSimpleName());
            return unavailable();
        }
    }

    private static Reply unavailable() {
        return error(503, "El servicio de colaboracion no esta disponible");
    }

    static Reply error(int status, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        return new Reply(status, json.toString());
    }
}
