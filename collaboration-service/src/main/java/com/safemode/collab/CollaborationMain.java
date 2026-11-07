package com.safemode.collab;

import com.mongodb.MongoException;
import com.safemode.collab.decisions.Decisions;
import com.safemode.common.DependencyProbe;
import com.safemode.common.Env;
import com.safemode.common.MiniHttpServer;
import com.safemode.common.NetworkDefaults;
import com.safemode.common.ServiceMetrics;

/**
 * Servicio de colaboracion del panel: la configuracion compartida del puesto (MongoDB), la presencia de operadores y la
 * cache de permisos (Redis). Pregunta a Gestion de acceso (PostgreSQL) quien puede escribir.
 *
 * Variables de entorno: MONGO_URI, REDIS_HOST, REDIS_PORT, ACCESS_URL, LAB_ADMIN_KEY, DECISIONS_MODE (solucion | base),
 * PORT, BIND_HOST y los tiempos de espera MONGO_TIMEOUT_MS, REDIS_TIMEOUT_MS, ACCESS_CONNECT_TIMEOUT_MS,
 * ACCESS_REQUEST_TIMEOUT_MS (cortos a proposito: con una dependencia caida se responde en milisegundos).
 */
public class CollaborationMain {

    public static void main(String[] args) throws Exception {
        NetworkDefaults.shortNegativeDnsCache();   // antes de cualquier conexion: recuperacion rapida de un nombre que volvio a resolverse
        Decisions decisions = Decisions.forMode(Env.get("DECISIONS_MODE", "solucion"));
        if (decisions.mode().equals("base")) {
            System.out.println("[collaboration] *** MODO BASE: se conservan a proposito los defectos del punto de partida "
                    + "(edicion perdida, permiso viejo, presencia sin vencimiento). Solo para el laboratorio. ***");
        }

        PuestoStore puestos = new PuestoStore(Env.get("MONGO_URI", "mongodb://localhost:27017"), "safemode",
                Env.getInt("MONGO_TIMEOUT_MS", 1500));
        seedWithRetries(puestos, 60);

        RedisPresenceStore redis = new RedisPresenceStore(Env.get("REDIS_HOST", "localhost"), Env.getInt("REDIS_PORT", 6379),
                Env.getInt("REDIS_TIMEOUT_MS", 300));
        AccessClient access = new AccessClient(Env.get("ACCESS_URL", "http://localhost:8100"),
                Env.getInt("ACCESS_CONNECT_TIMEOUT_MS", 500), Env.getInt("ACCESS_REQUEST_TIMEOUT_MS", 1500));

        ServiceMetrics metrics = new ServiceMetrics("collaboration");
        metrics.preregister("presence");
        new DependencyProbe(metrics)
                .add("mongodb", puestos::ping)
                .add("redis", redis::ping)
                .add("access", access::isUp)
                .start(3);

        MiniHttpServer server = new CollaborationApi(puestos, redis, access, decisions, metrics,
                Env.get("LAB_ADMIN_KEY", "lab-admin"))
                .build(Env.get("BIND_HOST", "0.0.0.0"), Env.getInt("PORT", 8110));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            redis.close();
            puestos.close();
        }));
        System.out.println("[collaboration] Escuchando en el puerto " + server.boundPort() + " · decisiones: " + decisions.mode());
        Thread.currentThread().join();
    }

    private static void seedWithRetries(PuestoStore puestos, int attempts) throws InterruptedException {
        for (int i = 1; ; i++) {
            try {
                puestos.seedIfAbsent();
                return;
            } catch (MongoException e) {
                if (i >= attempts) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }
}
