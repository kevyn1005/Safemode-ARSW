package com.safemode.gateway;

import com.safemode.alerts.AlertEngine;
import com.safemode.alerts.AlertStore;
import com.safemode.presence.PresenceEventStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

// Arranca el motor de alertas + el WebSocket + la pagina del dashboard, sobre la misma base H2 del tracker.
// Terminal 1: el tracker (ObjectPresenceTrackerTest). Terminal 2, desde la raiz del repo:
//   mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.GatewayRunner"
// y abrir http://localhost:8080 en el navegador. Argumento opcional: segundos que queda corriendo (por defecto 600;
// 0 = hasta que lo detengan, que es lo que usa el contenedor de docker-compose).
// Variables de entorno (todas opcionales):
//   SAFEMODE_DB_URL     URL JDBC de la base (por defecto el archivo local del tracker; en Docker, el servicio h2-db)
//   SAFEMODE_BIND_HOST  direccion donde escuchar (por defecto localhost; en Docker, 0.0.0.0 para que llegue el puerto publicado)
public class GatewayRunner {
    private static final String DB_PATH = "./object-presence-tracker/data/presence";
    private static final int HTTP_PORT = 8080;
    private static final int WS_PORT = 8090;

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 600;
        String bindHost = bindHost(System.getenv("SAFEMODE_BIND_HOST"));
        try (PresenceEventStore events = PresenceEventStore.fromEnvironment(DB_PATH);
             AlertStore alerts = AlertStore.fromEnvironment(DB_PATH);
             AlertEngine engine = new AlertEngine(events, alerts)) {
            AlertGateway gateway = new AlertGateway(engine, alerts, bindHost, WS_PORT,
                    Set.of("http://localhost:" + HTTP_PORT, "http://127.0.0.1:" + HTTP_PORT));
            DashboardHttpServer http = new DashboardHttpServer(bindHost, HTTP_PORT, Path.of("security-dashboard"),
                    Path.of("object-presence-tracker", "data", "frames"));
            gateway.startAndWait();
            http.start();
            engine.start(Duration.ofSeconds(1));
            System.out.println("Dashboard: http://localhost:" + HTTP_PORT + "  (WebSocket en ws://localhost:" + WS_PORT + ")");
            System.out.println(seconds > 0 ? "Escuchando durante " + seconds + " s..." : "Escuchando hasta que lo detengan...");
            // Parada unica: al terminar el tiempo, o con docker stop (SIGTERM), que dispara el hook de cierre
            AtomicBoolean stopped = new AtomicBoolean();
            Runnable stopAll = () -> {
                if (stopped.compareAndSet(false, true)) {
                    engine.stop();
                    http.stop();
                    gateway.shutdown();
                }
            };
            Runtime.getRuntime().addShutdownHook(new Thread(stopAll));
            try {
                if (seconds > 0) {
                    Thread.sleep(seconds * 1000L);
                } else {
                    Thread.currentThread().join();
                }
            } finally {
                stopAll.run();
            }
        }
    }

    /** Direccion donde escuchar: la de SAFEMODE_BIND_HOST, o solo esta maquina (localhost) si no esta definida. */
    static String bindHost(String envValue) {
        return envValue == null || envValue.isBlank() ? "localhost" : envValue.trim();
    }
}
