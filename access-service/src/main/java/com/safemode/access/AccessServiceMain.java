package com.safemode.access;

import com.google.gson.JsonObject;
import com.safemode.common.DependencyProbe;
import com.safemode.common.Env;
import com.safemode.common.Guards;
import com.safemode.common.HttpError;
import com.safemode.common.Json;
import com.safemode.common.MiniHttpServer;
import com.safemode.common.MiniHttpServer.Response;
import com.safemode.common.NetworkDefaults;
import com.safemode.common.ServiceMetrics;

import java.util.Map;

/**
 * Servicio de Gestion de acceso: la AUTORIDAD que dice quien puede configurar un puesto. PostgreSQL es la fuente
 * valida; ningun otro servicio debe decidir una escritura con una copia (una cache puede estar vieja hasta 60 s).
 *
 * <pre>
 *   GET  /permissions/{puesto}/{operador}   lectura por clave primaria (publica en esta practica)
 *   PUT  /permissions/{puesto}/{operador}   {"canConfigure": false}  concede o revoca (X-Lab-Key)
 *   POST /operators/provision               da de alta al operador de un correo y su membresia, sin cambiar lo que ya hay (X-Lab-Key)
 *   POST /lab/reset                         restablece los datos ficticios (X-Lab-Key)
 * </pre>
 * Variables de entorno: PGURL, PGUSER, PGPASSWORD, LAB_ADMIN_KEY, PORT, BIND_HOST.
 */
public class AccessServiceMain {

    public static void main(String[] args) throws Exception {
        NetworkDefaults.shortNegativeDnsCache();   // antes de cualquier conexion: recuperacion rapida de un nombre que volvio a resolverse
        String jdbcUrl = Env.get("PGURL", "jdbc:postgresql://localhost:5432/safemode");
        AccessRepository repository = new AccessRepository(jdbcUrl, Env.get("PGUSER", "safemode"),
                Env.get("PGPASSWORD", "lab-postgres"), Env.getInt("PG_CONNECT_TIMEOUT_MS", 1500));
        repository.init(60);

        ServiceMetrics metrics = new ServiceMetrics("access");
        new DependencyProbe(metrics).add("postgres", repository::isUp).start(3);

        MiniHttpServer server = build(repository, metrics, Env.get("LAB_ADMIN_KEY", "lab-admin"),
                Env.get("BIND_HOST", "0.0.0.0"), Env.getInt("PORT", 8100));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            repository.close();
        }));
        System.out.println("[access] Gestion de acceso escuchando en el puerto " + server.boundPort() + " (PostgreSQL: " + jdbcUrl + ")");
        Thread.currentThread().join();
    }

    static MiniHttpServer build(AccessRepository repository, ServiceMetrics metrics, String adminKey, String host, int port)
            throws java.io.IOException {
        MiniHttpServer server = new MiniHttpServer(host, port, metrics, 16);
        server.route("GET", "/permissions/{puesto}/{operator}", request -> {
            String puesto = Guards.identifier(request.param("puesto"), "puesto");
            String operator = Guards.identifier(request.param("operator"), "operador");
            return Response.json(200, guard(() -> repository.permission(puesto, operator)));
        });
        server.route("PUT", "/permissions/{puesto}/{operator}", request -> {
            Guards.requireAdminKey(request, adminKey);
            String puesto = Guards.identifier(request.param("puesto"), "puesto");
            String operator = Guards.identifier(request.param("operator"), "operador");
            JsonObject body = Json.parseObject(request.body());
            if (!body.has("canConfigure") || !body.get("canConfigure").isJsonPrimitive()
                    || !body.get("canConfigure").getAsJsonPrimitive().isBoolean()) {
                throw new HttpError(400, "Se espera {\"canConfigure\": true|false}");
            }
            boolean canConfigure = body.get("canConfigure").getAsBoolean();
            return guard(() -> repository.setCanConfigure(puesto, operator, canConfigure))
                    .map(permission -> Response.json(200, permission))
                    .orElseThrow(() -> new HttpError(404, "El operador no es miembro de ese puesto"));
        });
        server.route("POST", "/operators/provision", request -> {
            Guards.requireAdminKey(request, adminKey);
            JsonObject body = Json.parseObject(request.body());
            String email = text(body, "email", 5, 120).toLowerCase(java.util.Locale.ROOT);
            if (!EMAIL.matcher(email).matches()) {
                throw new HttpError(400, "email no es un correo valido");
            }
            String displayName = body.has("displayName") ? text(body, "displayName", 1, 80) : email.substring(0, email.indexOf('@'));
            String puesto = Guards.identifier(text(body, "puesto", 1, 40), "puesto");
            String role = text(body, "role", 1, 20);
            if (!ROLES.contains(role)) {
                throw new HttpError(400, "role debe ser admin, operator o viewer");
            }
            if (!body.has("canConfigure") || !body.get("canConfigure").isJsonPrimitive()
                    || !body.get("canConfigure").getAsJsonPrimitive().isBoolean()) {
                throw new HttpError(400, "Se espera canConfigure true|false");
            }
            boolean canConfigure = body.get("canConfigure").getAsBoolean();
            return guard(() -> repository.provision(email, displayName, puesto, role, canConfigure))
                    .map(provisioned -> Response.json(200, provisioned))
                    .orElseThrow(() -> new HttpError(404, "El puesto no existe"));
        });
        server.route("POST", "/lab/reset", request -> {
            Guards.requireAdminKey(request, adminKey);
            guard(() -> {
                repository.reset();
                return null;
            });
            return Response.json(200, Map.of("reset", true));
        });
        return server;
    }

    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("[^\\s@]{1,64}@[^\\s@]{1,100}");
    private static final java.util.Set<String> ROLES = java.util.Set.of("admin", "operator", "viewer");

    /** Un campo de texto obligatorio del cuerpo, sin caracteres de control y con largo acotado. */
    private static String text(JsonObject body, String field, int min, int max) {
        if (!body.has(field) || !body.get(field).isJsonPrimitive() || !body.get(field).getAsJsonPrimitive().isString()) {
            throw new HttpError(400, field + " debe ser texto");
        }
        String clean = body.get(field).getAsString().replaceAll("\\p{Cntrl}", " ").strip();
        if (clean.length() < min || clean.length() > max) {
            throw new HttpError(400, field + " debe tener de " + min + " a " + max + " caracteres");
        }
        return clean;
    }

    /** Una falla de PostgreSQL es 503 (dependencia no disponible), no un error de programacion (500). */
    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalStateException e) {
            throw new HttpError(503, "PostgreSQL no disponible");
        }
    }
}
