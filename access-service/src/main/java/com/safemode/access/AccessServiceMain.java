package com.safemode.access;

import com.google.gson.JsonObject;
import com.safemode.common.DependencyProbe;
import com.safemode.common.Env;
import com.safemode.common.Guards;
import com.safemode.common.HttpError;
import com.safemode.common.Json;
import com.safemode.common.MiniHttpServer;
import com.safemode.common.MiniHttpServer.Response;
import com.safemode.common.ServiceMetrics;

import java.util.Map;

/**
 * Servicio de Gestion de acceso: la AUTORIDAD que dice quien puede configurar un puesto. PostgreSQL es la fuente
 * valida; ningun otro servicio debe decidir una escritura con una copia (una cache puede estar vieja hasta 60 s).
 *
 * <pre>
 *   GET  /permissions/{puesto}/{operador}   lectura por clave primaria (publica en esta practica)
 *   PUT  /permissions/{puesto}/{operador}   {"canConfigure": false}  concede o revoca (X-Lab-Key)
 *   POST /lab/reset                         restablece los datos ficticios (X-Lab-Key)
 * </pre>
 * Variables de entorno: PGURL, PGUSER, PGPASSWORD, LAB_ADMIN_KEY, PORT, BIND_HOST.
 */
public class AccessServiceMain {

    public static void main(String[] args) throws Exception {
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

    /** Una falla de PostgreSQL es 503 (dependencia no disponible), no un error de programacion (500). */
    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalStateException e) {
            throw new HttpError(503, "PostgreSQL no disponible");
        }
    }
}
