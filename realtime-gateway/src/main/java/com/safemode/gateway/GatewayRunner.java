package com.safemode.gateway;

import com.safemode.alerts.AlertEngine;
import com.safemode.alerts.AlertStore;
import com.safemode.gateway.notify.NotificationService;
import com.safemode.presence.PresenceEventStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

// Arranca el motor de alertas + el WebSocket + la pagina del dashboard, sobre la misma base H2 del tracker.
// Terminal 1: el tracker (ObjectPresenceTrackerTest). Terminal 2, desde la raiz del repo:
//   mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.GatewayRunner"
// y abrir http://localhost:8080 en el navegador. Argumento opcional: segundos que queda corriendo (por defecto 600;
// 0 = hasta que lo detengan, que es lo que usa el contenedor de docker-compose).
// Variables de entorno (todas opcionales):
//   SAFEMODEmvn -pl realtime-gateway -am install -DskipTests
//$env:SAFEMODE_MODEL = "object-presence-tracker/data/models/yolov8m.onnx"
//$env:SAFEMODE_GOOGLE_CLIENT_ID = "613354160826-8u7smi9mvpukirucgkbm9jsirvm8ofvi.apps.googleusercontent.com"
//$env:SAFEMODE_ALLOWED_EMAILS = "majuliroca@gmail.com,alejaperezxd@gmail.com,kevynforero@gmail.com"
//mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.GatewayRunner"_DB_URL     URL JDBC de la base (por defecto el archivo local del tracker; en Docker, el servicio h2-db)
//   SAFEMODE_BIND_HOST  direccion donde escuchar (por defecto localhost; en Docker, 0.0.0.0 para que llegue el puerto publicado)
// Boton "Iniciar vigilancia" del panel: lanza el tracker como proceso hijo (ver TrackerProcess). Solo se ofrece si el
// gateway escucha unicamente en esta maquina (no en un contenedor ni abierto a la red) y se corre desde la raiz del repo.
public class GatewayRunner {
    private static final String DB_PATH = "./object-presence-tracker/data/presence";
    private static final int HTTP_PORT = 8080;
    private static final int WS_PORT = 8090;

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 600;
        String bindHost = bindHost(System.getenv("SAFEMODE_BIND_HOST"));
        String googleClientId = System.getenv("SAFEMODE_GOOGLE_CLIENT_ID");
        Set<String> allowedEmails = AuthService.parseEmails(System.getenv("SAFEMODE_ALLOWED_EMAILS"));
        if (googleClientId == null || googleClientId.isBlank() || allowedEmails.isEmpty()) {
            throw new IllegalStateException("Faltan SAFEMODE_GOOGLE_CLIENT_ID y/o SAFEMODE_ALLOWED_EMAILS: "
                    + "el panel solo permite iniciar sesion con Google (ver ADR 0004)");
        }
        AuthService auth = AuthService.google(googleClientId.trim(), allowedEmails);
        try (PresenceEventStore events = PresenceEventStore.fromEnvironment(DB_PATH);
             AlertStore alerts = AlertStore.fromEnvironment(DB_PATH);
             AlertEngine engine = new AlertEngine(events, alerts)) {
            Set<String> panelOrigins = Set.of("http://localhost:" + HTTP_PORT, "http://127.0.0.1:" + HTTP_PORT);
            AlertGateway gateway = new AlertGateway(engine, alerts, bindHost, WS_PORT, panelOrigins).withAuth(auth);
            // Avisos de las alertas nuevas altas y medias por Telegram (clave y chats solo por variables de entorno; ver ADR 0004)
            NotificationService notifications = NotificationService.fromEnvironment(System.getenv(),
                    alert -> engine.evidenceOf(alert).map(event -> engine.cameraName(event.cameraId())).orElse(null));
            if (notifications.isActive()) {
                engine.addListener(notifications::onAlert);
                System.out.println("Avisos de alertas altas y medias por: " + notifications.describe());
            } else {
                System.out.println("Avisos por Telegram: desactivados (define SAFEMODE_TELEGRAM_BOT_TOKEN y SAFEMODE_TELEGRAM_CHAT_IDS para activarlos)");
            }
            TrackerProcess tracker = trackerButtonAllowed(bindHost) ? newTrackerProcess() : null;
            DashboardHttpServer http = new DashboardHttpServer(bindHost, HTTP_PORT, Path.of("security-dashboard"),
                    Path.of("object-presence-tracker", "data", "frames"), tracker, panelOrigins)
                    .withCameraDirectory(new CameraDirectory() {
                        @Override
                        public java.util.Map<Integer, String> names() {
                            return engine.cameraNames();
                        }

                        @Override
                        public void rename(int cameraId, String name) {
                            engine.renameCamera(cameraId, name);
                            gateway.broadcastCameras();
                        }
                    })
                    .withEventReport(new EventReport() {
                        @Override
                        public java.util.List<PresenceEventStore.StoredEvent> recent(int limit) {
                            return engine.recentEvents(limit);
                        }

                        @Override
                        public String cameraName(int cameraId) {
                            return engine.cameraName(cameraId);
                        }
                    })
                    .withAuth(auth);
            gateway.startAndWait();
            http.start();
            engine.start(Duration.ofMillis(250));
            System.out.println("Inicio de sesion con Google: " + allowedEmails.size() + " correo(s) autorizado(s)");
            System.out.println(seconds > 0 ? "Escuchando durante " + seconds + " s..." : "Escuchando hasta que lo detengan...");
            // Parada unica: al terminar el tiempo, o con docker stop (SIGTERM), que dispara el hook de cierre
            AtomicBoolean stopped = new AtomicBoolean();
            Runnable stopAll = () -> {
                if (stopped.compareAndSet(false, true)) {
                    if (tracker != null) {
                        tracker.stopAndWait();
                    }
                    notifications.close();
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

    /** El boton solo se ofrece con el gateway escuchando unicamente en esta maquina. */
    static boolean trackerButtonAllowed(String bindHost) {
        return bindHost.equals("localhost") || bindHost.equals("127.0.0.1");
    }

    /** Tracker con la fuente "ventana compartida desde el navegador", sin limite de tiempo y parada ordenada por entrada estandar. */
    private static TrackerProcess newTrackerProcess() {
        return new TrackerProcess(TrackerProcess.mavenCommand(), Path.of("."),
                Map.of("SAFEMODE_FRAME_SOURCE", "browser", "SAFEMODE_STOP_ON_STDIN", "1"),
                Path.of("object-presence-tracker", "data", "tracker.log"), Duration.ofSeconds(90));
    }

    /** Direccion donde escuchar: la de SAFEMODE_BIND_HOST, o solo esta maquina (localhost) si no esta definida. */
    static String bindHost(String envValue) {
        return envValue == null || envValue.isBlank() ? "localhost" : envValue.trim();
    }
}
