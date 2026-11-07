package com.safemode.collab;

import com.google.gson.JsonObject;
import com.mongodb.MongoException;
import com.mongodb.client.model.Updates;
import com.safemode.collab.decisions.Decisions;
import com.safemode.common.Guards;
import com.safemode.common.HttpError;
import com.safemode.common.Json;
import com.safemode.common.MiniHttpServer;
import com.safemode.common.MiniHttpServer.Request;
import com.safemode.common.MiniHttpServer.Response;
import com.safemode.common.ServiceMetrics;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Las rutas del servicio de colaboracion. Este archivo es infraestructura: el orden de las comprobaciones y la
 * traduccion a codigos HTTP. Lo que se defiende (version, autoridad, TTL) esta en {@link Decisions}.
 *
 * <pre>
 *   GET  /api/puestos/{id}        configuracion del puesto (publica en esta practica)
 *   PUT  /api/puestos/{id}        {expectedVersion, title?, preferences?, cameras?}  200 | 400 | 401 | 403 | 404 | 409 | 503
 *   GET  /api/permissions/{id}    permiso para PINTAR la pantalla (con cache de 60 s; no sirve para escribir)
 *   POST /api/presence/{id}       heartbeat: presencia con vencimiento de 20 s; 200 aunque Redis no este
 *   GET  /api/presence/{id}       quien esta en linea
 *   POST /api/lab/reset           restablece el escenario (X-Lab-Key)
 * </pre>
 * La identidad es la cabecera {@code X-Operator}: simulada, sin autenticacion de produccion (el login es el
 * ADR 0004, pendiente). Las respuestas 403 y 409 son rechazos CORRECTOS, no fallas del servidor.
 */
public final class CollaborationApi {

    static final int PRESENCE_TTL_SECONDS = 20;
    static final int PERMISSION_CACHE_TTL_SECONDS = 60;

    private final PuestoStore puestos;
    private final PresenceStore store;
    private final Authority authority;
    private final AccessClient access;
    private final Decisions decisions;
    private final ServiceMetrics metrics;
    private final String adminKey;

    public CollaborationApi(PuestoStore puestos, PresenceStore store, AccessClient access, Decisions decisions,
                            ServiceMetrics metrics, String adminKey) {
        this.puestos = puestos;
        this.store = store;
        this.authority = access;
        this.access = access;
        this.decisions = decisions;
        this.metrics = metrics;
        this.adminKey = adminKey;
    }

    public MiniHttpServer build(String host, int port) throws IOException {
        MiniHttpServer server = new MiniHttpServer(host, port, metrics, 32);
        // a traves del proxy del frontend: /api/health llega aqui
        // ademas dice con que decisiones corre (solucion | base): cada JSON de evidencia queda marcado con su modo
        server.route("GET", "/api/health", request -> Response.json(200,
                Map.of("service", metrics.service(), "alive", true, "decisions", decisions.mode())));
        server.route("GET", "/api/puestos/{id}", this::getPuesto);
        server.route("PUT", "/api/puestos/{id}", this::putPuesto);
        server.route("GET", "/api/permissions/{id}", this::getPermission);
        server.route("POST", "/api/presence/{id}", this::touchPresence);
        server.route("GET", "/api/presence/{id}", this::listPresence);
        server.route("POST", "/api/lab/reset", this::reset);
        return server;
    }

    // ---- configuracion del puesto (MongoDB) -------------------------------------------------------------------

    private Response getPuesto(Request request) {
        String id = Guards.identifier(request.param("id"), "puesto");
        Document doc = mongo(() -> puestos.find(id)).orElseThrow(() -> new HttpError(404, "Puesto inexistente"));
        return Response.json(200, publicView(doc));
    }

    private Response putPuesto(Request request) {
        String id = Guards.identifier(request.param("id"), "puesto");
        String operator = operator(request);
        JsonObject body = Json.parseObject(request.body());
        long expectedVersion = PuestoValidator.expectedVersion(body);
        Bson changes = PuestoValidator.changes(body);

        // 1) AUTORIDAD: puede escribir este operador AHORA? (TODO 2)
        Boolean cached = decisions.usesPermissionCacheForWrites() ? cachedPermission(id, operator) : null;
        boolean allowed;
        try {
            allowed = decisions.permissionForWrite(operator, id, cached, authority);
        } catch (AuthorityUnavailableException e) {
            // no se pudo verificar: se rechaza, no se concede por falta de informacion
            throw new HttpError(503, "No se pudo verificar el permiso (Gestion de acceso no disponible); no se guardo nada");
        }
        if (!allowed) {
            throw new HttpError(403, "El operador no tiene permiso para configurar este puesto");
        }

        // 2) VERSION: guardar solo si nadie cambio el documento desde que se leyo (TODO 1)
        Bson update = Updates.combine(changes, Updates.set("updatedBy", operator), Updates.set("updatedAt", Instant.now().toString()));
        Document saved = mongo(() -> decisions.savePuesto(puestos.collection(), id, expectedVersion, update));
        if (saved == null) {
            Document current = mongo(() -> puestos.find(id)).orElseThrow(() -> new HttpError(404, "Puesto inexistente"));
            metrics.conflict();
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("error", "La version cambio: otra edicion se guardo antes. Recargue y vuelva a intentar");
            conflict.put("expectedVersion", expectedVersion);
            conflict.put("currentVersion", current.get("version"));
            return Response.json(409, conflict);
        }
        return Response.json(200, publicView(saved));
    }

    // ---- permiso para la pantalla (cache en Redis) ------------------------------------------------------------

    private Response getPermission(Request request) {
        String id = Guards.identifier(request.param("id"), "puesto");
        String operator = operator(request);
        Boolean cached = cachedPermission(id, operator);
        String source = "cache";
        boolean value;
        if (cached != null) {
            value = cached;
        } else {
            try {
                value = authority.canConfigure(operator, id);
            } catch (AuthorityUnavailableException e) {
                throw new HttpError(503, "Gestion de acceso no disponible");
            }
            source = "authority";
            storePermission(id, operator, value);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("operator", operator);
        view.put("puesto", id);
        view.put("canConfigure", value);
        view.put("source", source);
        view.put("note", "Solo para mostrar la pantalla: puede estar desactualizado hasta " + PERMISSION_CACHE_TTL_SECONDS
                + " s. La escritura se decide en el servidor.");
        return Response.json(200, view);
    }

    /** La copia en cache, o {@code null} si no hay o Redis no responde. Cuenta hit, miss o error. */
    private Boolean cachedPermission(String puesto, String operator) {
        try {
            Optional<String> value = store.get(permissionKey(puesto, operator));
            if (value.isPresent()) {
                metrics.cache("hit");
                return "1".equals(value.get());
            }
            metrics.cache("miss");
            return null;
        } catch (StoreUnavailableException e) {
            metrics.cache("error");
            return null;
        }
    }

    private void storePermission(String puesto, String operator, boolean value) {
        try {
            store.setWithTtl(permissionKey(puesto, operator), value ? "1" : "0", PERMISSION_CACHE_TTL_SECONDS);
        } catch (StoreUnavailableException e) {
            // la cache es opcional: sin Redis simplemente no se guarda
        }
    }

    // ---- presencia (Redis, efimera) ---------------------------------------------------------------------------

    private Response touchPresence(Request request) {
        String id = Guards.identifier(request.param("id"), "puesto");
        String operator = operator(request);
        boolean recorded;
        try {
            recorded = decisions.touchPresence(store, presenceKey(id, operator), PRESENCE_TTL_SECONDS);
        } catch (StoreUnavailableException e) {
            // solo ocurre con la version base, que no atrapa la falla de Redis
            throw new HttpError(503, "Redis no disponible");
        }
        if (!recorded) {
            metrics.degraded("presence");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("available", recorded);
        view.put("operator", operator);
        view.put("puesto", id);
        return Response.json(200, view);
    }

    private Response listPresence(Request request) {
        String id = Guards.identifier(request.param("id"), "puesto");
        Map<String, Object> view = new LinkedHashMap<>();
        try {
            String prefix = presenceKey(id, "");
            List<String> operators = store.keys(prefix + "*").stream()
                    .map(key -> key.substring(prefix.length())).sorted().toList();
            view.put("available", true);
            view.put("operators", operators);
        } catch (StoreUnavailableException e) {
            metrics.degraded("presence");
            view.put("available", false);
            view.put("operators", List.of());
        }
        return Response.json(200, view);
    }

    // ---- administracion del escenario -------------------------------------------------------------------------

    private Response reset(Request request) {
        Guards.requireAdminKey(request, adminKey);
        mongo(() -> {
            puestos.reset();
            return null;
        });
        try {
            store.deleteByPattern("presence:*");
            store.deleteByPattern("permission:*");
        } catch (StoreUnavailableException e) {
            // sin Redis no hay nada temporal que borrar
        }
        try {
            access.reset(adminKey);
        } catch (AuthorityUnavailableException e) {
            throw new HttpError(503, "Gestion de acceso no disponible: no se restablecieron los permisos");
        }
        return Response.json(200, Map.of("reset", true));
    }

    // ---- utilidades -------------------------------------------------------------------------------------------

    private static String operator(Request request) {
        String value = request.header("X-Operator");
        if (value == null || value.isBlank()) {
            throw new HttpError(401, "Falta la cabecera X-Operator (identidad simulada del laboratorio)");
        }
        return Guards.identifier(value.trim(), "X-Operator");
    }

    /** Una falla de MongoDB es 503 (dependencia no disponible): el documento es el dato principal y no se degrada. */
    private static <T> T mongo(Supplier<T> action) {
        try {
            return action.get();
        } catch (MongoException e) {
            throw new HttpError(503, "MongoDB no disponible");
        }
    }

    static String presenceKey(String puesto, String operator) {
        return "presence:" + puesto + ":" + operator;
    }

    static String permissionKey(String puesto, String operator) {
        return "permission:" + puesto + ":" + operator;
    }

    /** El documento tal como lo ve el cliente: el {@code _id} de Mongo se llama {@code id}. */
    static Map<String, Object> publicView(Document doc) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", doc.get("_id"));
        view.put("title", doc.get("title"));
        view.put("cameras", doc.get("cameras"));
        view.put("preferences", doc.get("preferences"));
        view.put("version", doc.get("version"));
        view.put("updatedBy", doc.get("updatedBy"));
        view.put("updatedAt", doc.get("updatedAt"));
        return view;
    }
}
