package com.safemode.gateway;

import com.google.gson.JsonParser;
import com.safemode.alerts.AlertEngine;
import com.safemode.alerts.AlertStore;
import com.safemode.presence.PresenceEventStore;
import com.safemode.presence.PresenceEventStore.OwnerInfo;
import com.safemode.presence.PresenceEventStore.RemovalInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// Script de medicion manual (no es JUnit): cuanto tarda una alerta desde que el tracker guarda el evento de retiro hasta que
// el panel la recibe por WebSocket. Usa los componentes REALES (base H2, motor de alertas con su intervalo de lectura de 1 s,
// gateway y un cliente WebSocket), con la base en memoria; no toca tus datos ni necesita camara.
//   mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.AlertLatencyBenchmark"
// Argumentos opcionales: numero de pruebas (por defecto 30) y milisegundos de inferencia por fotograma medidos con
// DetectionBenchmark (por defecto 370, yolov8m con 1 camara) para estimar el extremo a extremo.
public class AlertLatencyBenchmark {
    private static final Duration ENGINE_POLL = Duration.ofSeconds(1); // el mismo de GatewayRunner
    private static final int FRAMES_UNSEEN = 3;                         // ObjectTracker.MAX_FRAMES_UNSEEN
    private static final double LOOP_SLEEP_MS = 500;                    // pausa fija del bucle de ObjectPresenceTrackerTest tras cada fotograma

    public static void main(String[] args) throws Exception {
        int trials = args.length > 0 ? Integer.parseInt(args[0].trim()) : 30;
        double inferenceMs = args.length > 1 ? Double.parseDouble(args[1].trim()) : 370;
        String name = "bench_" + UUID.randomUUID().toString().replace("-", "");
        PresenceEventStore events = PresenceEventStore.inMemory(name);
        AlertStore alerts = AlertStore.inMemory(name);
        AlertEngine engine = new AlertEngine(events, alerts);
        AlertGateway gateway = new AlertGateway(engine, alerts, 0, Set.of("http://localhost:8080"));
        gateway.startAndWait();
        engine.start(ENGINE_POLL);

        LinkedBlockingQueue<Long> arrivals = new LinkedBlockingQueue<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + gateway.boundPort()), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            String type = JsonParser.parseString(buffer.toString()).getAsJsonObject().get("type").getAsString();
                            if (type.equals("alert")) {
                                arrivals.add(System.nanoTime());
                            }
                            buffer.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        arrivals.clear();
        Thread.sleep(500);

        OwnerInfo owner = new OwnerInfo(1L, "persona con camisa roja", "data/frames/owner_1.png");
        Random random = new Random(42);
        List<Double> latencies = new ArrayList<>();
        try {
            for (int i = 0; i < trials; i++) {
                // la fase respecto al ciclo de lectura del motor importa (de 0 a 1 s): se reparte al azar
                Thread.sleep(random.nextInt((int) ENGINE_POLL.toMillis()) + 200);
                long t0 = System.nanoTime();
                events.recordRemoved(100 + i, "suitcase", 100, 100, 50, 80, Instant.now(), true, "data/frames/frame_1.png", owner,
                        new RemovalInfo("BY_OTHER", 2L, "persona con camisa azul", "data/frames/remover_2.png"));
                Long t1 = arrivals.poll(10, TimeUnit.SECONDS);
                if (t1 == null) {
                    System.out.println("Prueba " + (i + 1) + ": la alerta NO llego en 10 s");
                    continue;
                }
                latencies.add((t1 - t0) / 1e6);
            }
        } finally {
            socket.abort();
            gateway.shutdown();
            engine.close();
            alerts.close();
            events.close();
        }

        Collections.sort(latencies);
        double mean = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double p95 = latencies.get((int) Math.min(latencies.size() - 1, Math.ceil(latencies.size() * 0.95) - 1));
        double max = latencies.get(latencies.size() - 1);
        System.out.println("=== Latencia evento de retiro -> panel (WebSocket) ===");
        System.out.printf(Locale.ROOT, "Pruebas: %d | intervalo de lectura del motor: %d ms%n", latencies.size(), ENGINE_POLL.toMillis());
        System.out.printf(Locale.ROOT, "media %.0f ms | mediana %.0f | p95 %.0f | min %.0f | max %.0f%n", mean,
                latencies.get(latencies.size() / 2), p95, latencies.get(0), max);

        // El bucle del tracker hace: detectar (inferencia) y luego dormir 500 ms, asi que un "fotograma" dura inferencia + 500 ms,
        // NO 500 ms. Confirmar un retiro necesita FRAMES_UNSEEN fotogramas seguidos sin ver el objeto.
        double cycleMs = inferenceMs + LOOP_SLEEP_MS;
        double confirmMs = FRAMES_UNSEEN * cycleMs;
        System.out.println("\n=== Estimado de extremo a extremo (con inferencia de " + Math.round(inferenceMs) + " ms por fotograma) ===");
        System.out.printf(Locale.ROOT, "Un fotograma del tracker dura ~%.0f ms (inferencia + pausa de %.0f ms): ~%.1f fps reales%n", cycleMs,
                LOOP_SLEEP_MS, 1000 / cycleMs);
        System.out.printf(Locale.ROOT, "Confirmar el retiro (%d fotogramas sin ver el objeto): ~%.0f ms%n", FRAMES_UNSEEN, confirmMs);
        System.out.printf(Locale.ROOT, "Evento -> panel (medido, peor caso p95): ~%.0f ms%n", p95);
        double total = confirmMs + p95;
        System.out.printf(Locale.ROOT, "Suma, peor caso: ~%.0f ms de 5000 ms (%s, margen %.0f ms)%n", total,
                total < 5000 ? "dentro de la meta" : "FUERA de la meta", 5000 - total);
        System.out.println("Con la verificacion por imagen (withRemovalVerification) un objeto tapado puede tardar hasta 12 "
                + "fotogramas extra: ese caso queda fuera de la meta a proposito.");
    }
}
