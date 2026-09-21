package com.safemode.gateway;

import com.safemode.alerts.AlertEngine;
import com.safemode.alerts.AlertStore;
import com.safemode.presence.PresenceEventStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

// Arranca el motor de alertas + el WebSocket + la pagina del dashboard, sobre la misma base H2 del tracker.
// Terminal 1: el tracker (ObjectPresenceTrackerTest). Terminal 2, desde la raiz del repo:
//   mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.GatewayRunner"
// y abrir http://localhost:8080 en el navegador. Argumentos opcionales: segundos que queda corriendo (por defecto 600).
public class GatewayRunner {
    private static final String DB_PATH = "./object-presence-tracker/data/presence";
    private static final int HTTP_PORT = 8080;
    private static final int WS_PORT = 8090;

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 600;
        try (PresenceEventStore events = new PresenceEventStore(DB_PATH);
             AlertStore alerts = AlertStore.forFile(DB_PATH);
             AlertEngine engine = new AlertEngine(events, alerts)) {
            AlertGateway gateway = new AlertGateway(engine, alerts, WS_PORT,
                    Set.of("http://localhost:" + HTTP_PORT, "http://127.0.0.1:" + HTTP_PORT));
            DashboardHttpServer http = new DashboardHttpServer(HTTP_PORT, Path.of("security-dashboard"),
                    Path.of("object-presence-tracker", "data", "frames"));
            gateway.startAndWait();
            http.start();
            engine.start(Duration.ofSeconds(1));
            System.out.println("Dashboard: http://localhost:" + HTTP_PORT + "  (WebSocket en ws://localhost:" + WS_PORT + ")");
            System.out.println("Escuchando durante " + seconds + " s...");
            try {
                Thread.sleep(seconds * 1000L);
            } finally {
                engine.stop();
                http.stop();
                gateway.shutdown();
            }
        }
    }
}
