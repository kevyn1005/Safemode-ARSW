package com.safemode.gateway;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Expone /metrics (Prometheus) y /ready (sonda funcional) en un puerto aparte.
 *
 * <p>Ademas publica el estado de las dependencias del gateway con el MISMO nombre que usan los servicios de persistencia
 * ({@code safemode_dependency_up{service,dependency}}, ADR 0005): un solo panel de Grafana muestra si responden la base de
 * eventos, Redis, MongoDB y la autoridad de permisos. Como ellos, solo prueba conectividad: que la base responda no dice
 * que cada resultado sea correcto.
 */
public final class Observability {

    public static final PrometheusMeterRegistry REGISTRY =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    /** El gauge solo guarda una referencia debil a su valor: se conserva aqui para que no lo recoja el recolector. */
    private static final Map<String, AtomicInteger> DEPENDENCIES = new ConcurrentHashMap<>();

    private Observability() {}

    /**
     * Publica {@code safemode_dependency_up{service="gateway",dependency=...}} (1 = responde, 0 = caida) y la vuelve a medir
     * cada {@code every} en un hilo aparte: asi un scrape de Prometheus nunca espera a que la base conteste. La primera
     * medicion es inmediata, para que el primer scrape ya diga la verdad. Devuelve el planificador (para detenerlo).
     */
    public static ScheduledExecutorService watchDependency(String dependency, BooleanSupplier check, Duration every) {
        AtomicInteger up = DEPENDENCIES.computeIfAbsent(dependency, name -> {
            AtomicInteger holder = new AtomicInteger();
            REGISTRY.gauge("safemode.dependency.up", Tags.of("service", "gateway", "dependency", name), holder);
            return holder;
        });
        up.set(probe(check));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "dependencia-" + dependency);
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> up.set(probe(check)), every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
        return scheduler;
    }

    private static int probe(BooleanSupplier check) {
        try {
            return check.getAsBoolean() ? 1 : 0;
        } catch (RuntimeException e) {
            return 0; // una comprobacion que falla es una dependencia caida, no un error del gateway
        }
    }

    public static HttpServer start(String host, int port, BooleanSupplier ready) throws IOException {
        new JvmMemoryMetrics().bindTo(REGISTRY);
        new JvmGcMetrics().bindTo(REGISTRY);
        new JvmThreadMetrics().bindTo(REGISTRY);
        new ProcessorMetrics().bindTo(REGISTRY);

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/metrics", ex -> {
            byte[] body = REGISTRY.scrape().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });

        server.createContext("/ready", ex -> {
            boolean ok = ready.getAsBoolean();
            byte[] body = (ok ? "ready" : "not ready").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ok ? 200 : 503, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });

        server.start();
        return server;
    }
}