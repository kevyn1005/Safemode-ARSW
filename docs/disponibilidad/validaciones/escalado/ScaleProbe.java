import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Sondeo del prototipo de escalado (docker-compose.escalado.yml): dos replicas del gateway detras del balanceador.
 * Mide, con el balanceador real y PostgreSQL real, lo que el documento de disponibilidad afirma:
 *   1. el balanceador reparte y la sesion emitida por una replica vale en la otra (sesiones compartidas),
 *   2. los operadores quedan repartidos entre las dos replicas,
 *   3. una alerta nueva llega a TODOS los operadores, de cualquier replica, exactamente una vez,
 *   4. una replica cae de golpe: el trafico HTTP no falla, los operadores se reconectan solos (como lo hace el panel) y una alerta
 *      creada justo durante la caida no se pierde,
 *   5. una replica termina sola y Docker la reinicia: el balanceador la reincorpora sin intervencion.
 * Uso: java -cp gson.jar ScaleProbe.java http-base ws-base cookie proyecto-compose
 * La "sesion" es una fila de prueba que el script inserto en la base de ESTE prototipo; nada de esto toca el stack normal.
 */
public class ScaleProbe {

    static final String ORIGIN = "http://localhost:8080";   // el unico origen que el gateway acepta de un navegador
    static final int OPERATORS = 6;
    static final long PANEL_RETRY_MS = 2000;                 // RealtimeSession.RETRY_MS del panel

    static String httpBase, wsBase, cookie, project;
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final List<String[]> checks = new CopyOnWriteArrayList<>();

    // ------------------------------------------------------------------------------------------------ utilidades

    static String secs(long nanos) {
        return String.format("%.2f s", nanos / 1e9);
    }

    static void log(String line) {
        System.out.println(line);
        System.out.flush();
    }

    static void check(String name, boolean ok, String detail) {
        checks.add(new String[] {ok ? "OK" : "FALLA", name, detail});
        log((ok ? "  [OK]    " : "  [FALLA] ") + name + " -> " + detail);
    }

    static String container(String service, int n) {
        return project + "-" + service + "-" + n;
    }

    /** Ejecuta un comando y devuelve su salida combinada. */
    static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(60, TimeUnit.SECONDS);
        return out.trim();
    }

    static String runQuiet(String... cmd) {
        try {
            return run(cmd);
        } catch (Exception e) {
            return "";
        }
    }

    static int parseIntOr(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    record Reply(long atNs, int status, String servedBy, long latencyNs) {
    }

    static Reply get(String path, String cookieHeader) {
        long t0 = System.nanoTime();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(httpBase + path)).timeout(Duration.ofSeconds(3));
            if (cookieHeader != null) {
                b.header("Cookie", cookieHeader);
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(t0, r.statusCode(), r.headers().firstValue("X-Served-By").orElse("-"), System.nanoTime() - t0);
        } catch (Exception e) {
            return new Reply(t0, -1, e.getClass().getSimpleName(), System.nanoTime() - t0);
        }
    }

    static String shortName(String hostPort) {
        int i = hostPort.indexOf("realtime-gateway-");
        return i < 0 ? hostPort : "replica-" + hostPort.substring(i + "realtime-gateway-".length()).replaceAll(":.*", "");
    }

    /** Inserta un retiro BY_OTHER (robo) como lo haria el tracker; devuelve el instante (nanoTime) justo antes de lanzar el INSERT. */
    static long insertTheft(int trackedObjectId) throws Exception {
        String sql = "INSERT INTO object_presence_event (tracked_object_id, class_name, event_type, occurred_at, pos_x, pos_y, width, height, "
                + "person_nearby, frame_path, owner_person_id, owner_description, owner_crop_path, removal_kind, remover_person_id, "
                + "remover_description, remover_crop_path, camera_id) VALUES (" + trackedObjectId + ", 'suitcase', 'REMOVED', now(), 100, 100, 50, 80, "
                + "true, 'data/frames/prueba.png', 1, 'persona de prueba', 'data/frames/prueba_owner.png', 'BY_OTHER', 2, 'otra persona de prueba', "
                + "'data/frames/prueba_remover.png', 1)";
        long t0 = System.nanoTime();
        String out = run("docker", "exec", container("postgres", 1), "psql", "-U", "safemode", "-d", "safemode", "-q", "-c", sql);
        if (!out.isEmpty()) {
            log("    psql: " + out);
        }
        return t0;
    }

    static String wsSessions(int replica) {
        try {
            String metrics = run("docker", "exec", container("realtime-gateway", replica), "curl", "-s", "localhost:9464/metrics");
            for (String line : metrics.split("\n")) {
                if (line.startsWith("safemode_ws_sessions")) {
                    return line.substring(line.lastIndexOf(' ') + 1).replace(".0", "");
                }
            }
        } catch (Exception e) {
            return "?";
        }
        return "?";
    }

    // ------------------------------------------------------------------------------------------------ un operador (cliente WebSocket)

    /** Se comporta como el panel: si el socket se cae espera 2 s, comprueba la sesion en /auth/me y reconecta por el balanceador. */
    static final class Operator implements WebSocket.Listener {
        final int n;
        final List<long[]> alerts = new CopyOnWriteArrayList<>();           // {alertId, instante de recepcion}: alertas recibidas EN VIVO
        final Map<Long, Boolean> inSnapshot = new ConcurrentHashMap<>();    // alertas vistas en algun snapshot (al conectar o reconectar)
        final AtomicInteger snapshots = new AtomicInteger();
        final AtomicBoolean stopping = new AtomicBoolean();
        volatile long lostAtNs;
        volatile long reconnectedAfterNs = -1;
        volatile boolean connected;
        volatile WebSocket socket;
        private final StringBuilder buffer = new StringBuilder();

        Operator(int n) {
            this.n = n;
        }

        void connect(boolean isReconnect) {
            Thread t = new Thread(() -> {
                boolean first = true;
                while (!stopping.get()) {
                    try {
                        if (isReconnect && first) {
                            Thread.sleep(PANEL_RETRY_MS);
                        }
                        first = false;
                        if (isReconnect && get("/auth/me", cookie).status() != 200) {
                            continue;   // el panel solo reconecta si la sesion sigue viva
                        }
                        socket = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", ORIGIN).header("Cookie", cookie)
                                .connectTimeout(Duration.ofSeconds(2)).buildAsync(URI.create(wsBase), this).get(3, TimeUnit.SECONDS);
                        return;
                    } catch (Exception e) {
                        try {
                            Thread.sleep(PANEL_RETRY_MS);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }, "operador-" + n);
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            connected = true;
            if (lostAtNs != 0 && reconnectedAfterNs < 0) {
                reconnectedAfterNs = System.nanoTime() - lostAtNs;
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                JsonObject message = JsonParser.parseString(buffer.toString()).getAsJsonObject();
                buffer.setLength(0);
                String type = message.get("type").getAsString();
                if (type.equals("snapshot")) {
                    snapshots.incrementAndGet();
                    JsonArray list = message.getAsJsonArray("alerts");
                    for (JsonElement e : list) {
                        inSnapshot.put(e.getAsJsonObject().get("id").getAsLong(), true);
                    }
                } else if (type.equals("alert")) {
                    alerts.add(new long[] {message.getAsJsonObject("alert").get("id").getAsLong(), System.nanoTime()});
                }
            }
            webSocket.request(1);
            return null;
        }

        private void lost() {
            if (stopping.get() || !connected) {
                return;
            }
            connected = false;
            lostAtNs = System.nanoTime();
            reconnectedAfterNs = -1;
            connect(true);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            lost();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            lost();
        }

        /** Alertas recibidas en vivo con id > baseline. */
        List<Long> live(long baseline) {
            List<Long> ids = new ArrayList<>();
            for (long[] a : alerts) {
                if (a[0] > baseline) {
                    ids.add(a[0]);
                }
            }
            return ids;
        }

        /** Ya vio alguna alerta con id > baseline: en vivo o en el snapshot al reconectar. */
        boolean knows(long baseline) {
            if (!live(baseline).isEmpty()) {
                return true;
            }
            for (Long id : inSnapshot.keySet()) {
                if (id > baseline) {
                    return true;
                }
            }
            return false;
        }

        void stop() {
            stopping.set(true);
            WebSocket s = socket;
            if (s != null) {
                s.abort();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ un fallo de replica

    /**
     * Provoca la caida de una replica y mide: reconexion de sus operadores, una alerta creada JUSTO durante la caida, y la
     * reincorporacion de la replica. {@code auto}: la replica termina sola y Docker la reinicia; si no, se la mata (docker kill) y se
     * arranca a mano. Devuelve el id de la ultima alerta que se creo.
     */
    static long failureRound(String title, char tag, int replica, List<Operator> operators, List<Reply> traffic, long lastAlertId,
                             int trackedObject, boolean auto) throws Exception {
        log("\n== " + title);
        String name = container("realtime-gateway", replica);
        for (Operator op : operators) {
            op.lostAtNs = 0;
            op.reconnectedAfterNs = -1;
        }
        int onVictim = parseIntOr(wsSessions(replica));
        String restartsBefore = runQuiet("docker", "inspect", "-f", "{{.RestartCount}}", name);
        long tFail = System.nanoTime();
        if (auto) {
            run("docker", "exec", name, "kill", "-TERM", "1");
        } else {
            run("docker", "kill", name);
        }
        log("   replica " + replica + " caida; operadores conectados a ella: " + onVictim);

        // Una alerta que se crea JUSTO ahora, con los operadores de la replica caida todavia desconectados
        Thread.sleep(500);
        final long baseline = lastAlertId;
        insertTheft(trackedObject);
        boolean allKnow = waitFor(() -> operators.stream().allMatch(o -> o.knows(baseline)), 15_000);
        Thread.sleep(2500);   // tiempo de sobra para que apareciera un duplicado
        boolean noDuplicates = true;
        int viaSnapshot = 0;
        long newId = -1;
        for (Operator op : operators) {
            List<Long> ids = op.live(baseline);
            noDuplicates &= ids.size() <= 1;
            if (ids.isEmpty() && op.knows(baseline)) {
                viaSnapshot++;
            }
            for (Long id : ids) {
                newId = Math.max(newId, id);
            }
            for (Long id : op.inSnapshot.keySet()) {
                if (id > baseline) {
                    newId = Math.max(newId, id);
                }
            }
        }
        long affected = operators.stream().filter(o -> o.lostAtNs != 0).count();
        boolean allBack = waitFor(() -> operators.stream().allMatch(o -> o.connected), 20_000);
        long slowestBack = operators.stream().mapToLong(o -> o.reconnectedAfterNs).max().orElse(-1);
        log("   operadores desconectados por la caida: " + affected + "; el que mas tardo en volver: " + secs(slowestBack)
                + " (incluye la espera de 2 s del panel)");
        log("   la alerta creada durante la caida: " + (operators.size() - viaSnapshot) + " operadores la recibieron en vivo, " + viaSnapshot
                + " la vieron en el snapshot al reconectar");
        check(tag + "a. los operadores de la replica caida se reconectan solos por el balanceador", affected == onVictim && allBack,
                affected + " desconectados de " + onVictim + "; el mas lento tardo " + secs(slowestBack));
        check(tag + "b. la alerta creada durante la caida no se pierde: la ven los " + operators.size() + " operadores, ninguno la recibe duplicada",
                allKnow && noDuplicates, "en vivo " + (operators.size() - viaSnapshot) + ", por snapshot " + viaSnapshot);

        // La replica vuelve
        long tUp;
        if (auto) {
            boolean back = waitFor(() -> "true".equals(runQuiet("docker", "inspect", "-f", "{{.State.Running}}", name))
                    && !restartsBefore.equals(runQuiet("docker", "inspect", "-f", "{{.RestartCount}}", name)), 30_000);
            tUp = System.nanoTime();
            check(tag + "c. Docker reinicia la replica sin intervencion (restart: unless-stopped)", back,
                    back ? "reinicios " + restartsBefore + " -> " + runQuiet("docker", "inspect", "-f", "{{.RestartCount}}", name) + "; en marcha " + secs(tUp - tFail) + " tras la caida"
                            : "no se reinicio en 30 s");
        } else {
            run("docker", "start", name);
            tUp = System.nanoTime();
            log("   la replica se arranca a mano (con docker kill, Docker lo trata como una parada manual y NO la reinicia)");
        }
        final long from = tUp;
        final String needle = name + ":";
        boolean rejoined = waitFor(() -> traffic.stream().anyMatch(r -> r.atNs() > from && r.servedBy().contains(needle)), 60_000);
        long rejoinNs = -1;
        if (rejoined) {
            synchronized (traffic) {
                for (Reply r : traffic) {
                    if (r.atNs() > from && r.servedBy().contains(needle)) {
                        rejoinNs = r.atNs() - tFail;
                        break;
                    }
                }
            }
        }
        check(tag + "d. el balanceador reincorpora a la replica solo (vuelve a atender trafico)", rejoined,
                rejoined ? "atiende de nuevo " + secs(rejoinNs) + " despues de la caida" : "no volvio en 60 s");
        return newId < 0 ? lastAlertId : newId;
    }

    // ------------------------------------------------------------------------------------------------ el recorrido

    public static void main(String[] args) throws Exception {
        httpBase = args[0];
        wsBase = args[1];
        cookie = args[2];
        project = args[3];

        log("== 1. Reparto y sesiones compartidas (la cookie se creo UNA sola vez, en la base; las dos replicas la deben reconocer)");
        Map<String, Integer> served = new TreeMap<>();
        int ok200 = 0;
        for (int i = 0; i < 30; i++) {
            Reply r = get("/auth/me", cookie);
            if (r.status() == 200) {
                ok200++;
            }
            served.merge(shortName(r.servedBy()), 1, Integer::sum);
        }
        log("   30 peticiones con sesion -> " + served);
        check("1a. las 30 peticiones con sesion valida responden 200 sin importar la replica", ok200 == 30, ok200 + "/30");
        check("1b. el balanceador reparte entre las dos replicas", served.size() == 2 && served.values().stream().allMatch(v -> v >= 10), served.toString());
        int unauth = 0;
        for (int i = 0; i < 5; i++) {
            unauth += get("/auth/me", null).status() == 401 ? 1 : 0;
            unauth += get("/auth/me", "safemode_session=inventada" + i).status() == 401 ? 1 : 0;
        }
        check("1c. sin cookie o con cookie inventada responde 401 (las dos replicas)", unauth == 10, unauth + "/10");

        log("\n== 2. Operadores conectados por el WebSocket del balanceador");
        List<Operator> operators = new ArrayList<>();
        for (int i = 1; i <= OPERATORS; i++) {
            Operator op = new Operator(i);
            operators.add(op);
            op.connect(false);
        }
        boolean allConnected = waitFor(() -> operators.stream().allMatch(o -> o.snapshots.get() >= 1), 10_000);
        int s1 = parseIntOr(wsSessions(1)), s2 = parseIntOr(wsSessions(2));
        log("   sockets abiertos por replica (metrica safemode_ws_sessions): replica-1=" + s1 + " replica-2=" + s2);
        check("2a. los " + OPERATORS + " operadores se conectan y reciben su snapshot", allConnected,
                operators.stream().filter(o -> o.snapshots.get() >= 1).count() + "/" + OPERATORS);
        check("2b. los sockets quedan repartidos entre las dos replicas", s1 >= 1 && s2 >= 1 && s1 + s2 == OPERATORS, "replica-1=" + s1 + " replica-2=" + s2);

        log("\n== 3. Una alerta nueva llega a todos los operadores, de cualquier replica, una sola vez");
        final long none = 0;
        long t0 = insertTheft(101);
        boolean all = waitFor(() -> operators.stream().allMatch(o -> !o.live(none).isEmpty()), 10_000);
        Thread.sleep(3000);   // tiempo de sobra para que apareciera un duplicado (el barrido entre replicas corre cada 0,5 s)
        List<String> lat = new ArrayList<>();
        long first = Long.MAX_VALUE, worst = 0, alertId = -1;
        boolean sameAlert = true, exactlyOnce = true;
        for (Operator op : operators) {
            List<Long> ids = op.live(none);
            exactlyOnce &= ids.size() == 1;
            if (!ids.isEmpty()) {
                if (alertId < 0) {
                    alertId = ids.get(0);
                }
                sameAlert &= ids.get(0) == alertId;
                long dt = op.alerts.get(0)[1] - t0;
                lat.add("op" + op.n + "=" + secs(dt));
                first = Math.min(first, dt);
                worst = Math.max(worst, dt);
            }
        }
        log("   latencia desde antes de lanzar el INSERT (incluye ~0,3 s del 'docker exec'): " + lat);
        check("3a. los " + OPERATORS + " operadores reciben la alerta nueva", all, "alerta #" + alertId);
        check("3b. cada operador la recibe exactamente una vez y es la misma alerta", exactlyOnce && sameAlert, "sin duplicados");
        check("3c. llega a todos en menos de 5 s (el requisito del proyecto)", all && worst < 5_000_000_000L, "el primero " + secs(first) + ", el ultimo " + secs(worst));

        // Trafico HTTP continuo de 4 "usuarios" (una peticion cada 50 ms cada uno) durante TODOS los fallos: es lo que mide la disponibilidad observada.
        List<Reply> traffic = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean trafficOn = new AtomicBoolean(true);
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            Thread t = new Thread(() -> {
                while (trafficOn.get()) {
                    traffic.add(get("/auth/me", cookie));
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "trafico-" + w);
            t.start();
            workers.add(t);
        }
        Thread.sleep(2000);
        long trafficFrom = System.nanoTime();

        long lastAlertId = alertId;
        lastAlertId = failureRound("4. Cae la replica 1 de golpe (docker kill = SIGKILL, sin cierre ordenado)", '4', 1, operators, traffic, lastAlertId, 201, false);
        failureRound("5. La replica 2 termina sola (SIGTERM interno) y Docker la reinicia (restart: unless-stopped)", '5', 2, operators, traffic, lastAlertId, 202, true);

        trafficOn.set(false);
        for (Thread t : workers) {
            t.join();
        }
        List<Long> lats = new ArrayList<>();
        long total = 0, good = 0, slowest = 0;
        synchronized (traffic) {
            for (Reply r : traffic) {
                if (r.atNs() < trafficFrom) {
                    continue;
                }
                total++;
                slowest = Math.max(slowest, r.latencyNs());
                if (r.status() == 200) {
                    good++;
                    lats.add(r.latencyNs());
                }
            }
        }
        Collections.sort(lats);
        long p50 = lats.isEmpty() ? 0 : lats.get(lats.size() / 2);
        long p99 = lats.isEmpty() ? 0 : lats.get((int) (lats.size() * 0.99));
        log("\n== 6. Disponibilidad observada durante los dos fallos (trafico continuo de 4 usuarios, uno cada 50 ms)");
        log("   " + total + " peticiones, " + good + " respondieron 200, " + (total - good) + " fallaron -> " + String.format("%.3f", 100.0 * good / total) + " %");
        log("   latencia p50 " + String.format("%.0f ms", p50 / 1e6) + ", p99 " + String.format("%.0f ms", p99 / 1e6) + ", la mas lenta " + secs(slowest));
        check("6a. ninguna peticion HTTP fallo durante los dos fallos de replica", total == good, (total - good) + " fallos de " + total
                + " (" + String.format("%.3f", 100.0 * good / total) + " %)");
        check("6b. la peticion mas lenta quedo por debajo del tiempo limite del cliente (3 s)", slowest < 3_000_000_000L, "la mas lenta " + secs(slowest));

        for (Operator op : operators) {
            op.stop();
        }

        log("\n== Resumen");
        long failures = checks.stream().filter(c -> c[0].equals("FALLA")).count();
        for (String[] c : checks) {
            log(String.format("  %-7s %s", c[0], c[1]));
        }
        log("RESULTADO: " + (failures == 0 ? "OK" : "FALLA") + " (" + (checks.size() - failures) + "/" + checks.size() + ")");
        System.exit(failures == 0 ? 0 : 1);
    }
}
