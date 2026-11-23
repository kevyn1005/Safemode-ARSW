package com.safemode.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Convierte el correo de la sesion en el operador de Gestion de acceso (PostgreSQL), dandolo de alta la primera vez.
 *
 * <p>Reglas (ADR 0005 / 0004):
 * <ul>
 *   <li>Solo se consulta por correos de {@code SAFEMODE_ALLOWED_EMAILS}: cualquier otro devuelve vacio sin llamar a nadie.</li>
 *   <li>Todos los autorizados pueden VER el puesto ({@code role=viewer}); configurar es solo para los de
 *       {@code SAFEMODE_ADMIN_EMAILS} ({@code role=admin}, {@code canConfigure=true}) <b>la primera vez</b>: despues manda la base.
 *       Revocar a alguien se hace en PostgreSQL ({@code PUT /permissions}) y NO se deshace cuando el gateway reinicia, aunque su
 *       correo siga en la lista.</li>
 *   <li>El id del operador de un correo no cambia, asi que se guarda en memoria y no se vuelve a preguntar. El PERMISO nunca se
 *       guarda aqui: lo decide el servicio de colaboracion preguntando a Gestion de acceso en cada escritura.</li>
 *   <li>Falla cerrado: si Gestion de acceso no responde, {@link IllegalStateException} (el panel recibe 503); nunca se inventa un
 *       operador. La clave de administracion viaja solo en una cabecera y no se escribe en los registros.</li>
 * </ul>
 */
public final class AccessDirectory implements OperatorDirectory {

    private final URI provisionUri;
    private final String adminKey;
    private final String puestoId;
    private final Set<String> allowed;
    private final Set<String> admins;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final Map<String, String> known = new ConcurrentHashMap<>();

    /**
     * @param baseUrl  direccion de Gestion de acceso, p. ej. {@code http://access-service:8100}
     * @param adminKey clave de administracion del servicio ({@code X-Lab-Key})
     * @param allowed  correos autorizados a entrar al panel (en minusculas)
     * @param admins   correos que pueden configurar el puesto la primera vez (en minusculas)
     */
    public AccessDirectory(String baseUrl, String adminKey, String puestoId, Set<String> allowed, Set<String> admins) {
        this(baseUrl, adminKey, puestoId, allowed, admins, Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    AccessDirectory(String baseUrl, String adminKey, String puestoId, Set<String> allowed, Set<String> admins,
                    Duration connectTimeout, Duration requestTimeout) {
        URI uri = URI.create(baseUrl.trim());
        boolean httpScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!httpScheme || uri.getHost() == null || !(path.isEmpty() || path.equals("/"))) {
            throw new IllegalArgumentException("La direccion de Gestion de acceso debe ser http(s)://host[:puerto] sin ruta");
        }
        if (adminKey == null || adminKey.isBlank()) {
            throw new IllegalArgumentException("Falta la clave de administracion de Gestion de acceso");
        }
        this.provisionUri = URI.create(uri.getScheme() + "://" + uri.getAuthority() + "/operators/provision");
        this.adminKey = adminKey;
        this.puestoId = puestoId;
        this.allowed = Set.copyOf(allowed);
        this.admins = Set.copyOf(admins);
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public Optional<String> operatorFor(String email) {
        String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(key)) {
            return Optional.empty();
        }
        String id = known.get(key);
        if (id == null) {
            id = provision(key);
            known.put(key, id);
        }
        return Optional.of(id);
    }

    private String provision(String email) {
        boolean admin = admins.contains(email);
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("puesto", puestoId);
        body.addProperty("role", admin ? "admin" : "viewer");
        body.addProperty("canConfigure", admin);
        HttpRequest request = HttpRequest.newBuilder(provisionUri).timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Lab-Key", adminKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Gestion de acceso respondio HTTP " + response.statusCode());
            }
            return JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpio la consulta a Gestion de acceso", e);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            // solo la clase: el mensaje podria traer la direccion interna
            throw new IllegalStateException("Gestion de acceso no disponible (" + e.getClass().getSimpleName() + ")", e);
        }
    }
}
