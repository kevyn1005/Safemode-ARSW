package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore;

import java.time.Duration;

// Corre el motor de alertas contra la misma base H2 del tracker (en otra terminal, mientras el tracker graba).
// Uso: mvn -pl alert-engine exec:java "-Dexec.mainClass=com.safemode.alerts.AlertEngineRunner" "-Dexec.args=120"
// El argumento opcional son los segundos que queda corriendo (por defecto 120).
public class AlertEngineRunner {
    private static final String DB_PATH = "./object-presence-tracker/data/presence";

    public static void main(String[] args) throws InterruptedException {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 120;
        try (PresenceEventStore events = PresenceEventStore.fromEnvironment(DB_PATH);
             AlertStore alerts = AlertStore.fromEnvironment(DB_PATH);
             AlertEngine engine = new AlertEngine(events, alerts)) {
            engine.addListener(alert -> System.out.println("[ALERTA " + alert.severity() + "] " + alert.type() + " - "
                    + alert.message() + (alert.framePath() != null ? " - foto: " + alert.framePath() : "")));
            engine.start(Duration.ofSeconds(2));
            System.out.println("Motor de alertas escuchando durante " + seconds + " s...");
            Thread.sleep(seconds * 1000L);
            System.out.println("Alertas guardadas: " + alerts.listAll().size() + " (pendientes: " + alerts.listPending().size() + ")");
        }
    }
}
