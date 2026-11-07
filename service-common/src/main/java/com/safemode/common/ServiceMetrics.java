package com.safemode.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Las metricas que Prometheus recoge de cada servicio. Los nombres llevan el prefijo {@code safemode_} (el mismo de las
 * metricas del gateway):
 * <ul>
 *   <li>{@code safemode_http_requests_total{service,method,route,status}}: respuestas HTTP. La ruta es la PLANTILLA
 *       ({@code /api/puestos/{id}}), nunca el id real: un id por etiqueta crearia una serie por cada valor.</li>
 *   <li>{@code safemode_http_duration_seconds_bucket}: histograma de latencia (el P95 se estima con sus cubetas).</li>
 *   <li>{@code safemode_dependency_up{service,dependency}}: 1 si la dependencia responde, 0 si no. Solo prueba
 *       conectividad; NO dice que el resultado de una operacion sea correcto ni que este autorizado.</li>
 *   <li>{@code safemode_cache_total{service,result}}: consultas de la cache de permisos (hit, miss, error).</li>
 *   <li>{@code safemode_conflicts_total}: ediciones rechazadas por version (409 esperados bajo edicion concurrente).</li>
 *   <li>{@code safemode_degraded_total{feature}}: operaciones que respondieron, pero degradadas (presencia sin Redis).</li>
 *   <li>{@code safemode_history_writes_total{service,result}}: entradas del historial de cambios del puesto ({@code ok} o
 *       {@code error}); un {@code error} es una entrada de auditoria perdida (el cambio ya habia quedado).</li>
 * </ul>
 * Los contadores de eventos raros se registran en cero al arrancar: sin eso, el primer evento no tendria muestra anterior
 * y {@code rate()} de Prometheus lo perderia.
 */
public final class ServiceMetrics {

    private static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
            Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5)};

    private final String service;
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final Map<String, AtomicInteger> dependencyGauges = new ConcurrentHashMap<>();

    public ServiceMetrics(String service) {
        this.service = service;
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
    }

    public String service() {
        return service;
    }

    /** Registra la respuesta de una peticion (el servidor lo hace solo; no cuenta /health ni /metrics). */
    public void http(String method, String route, int status, long elapsedNanos) {
        registry.counter("safemode.http.requests", "service", service, "method", method, "route", route,
                "status", Integer.toString(status)).increment();
        Timer.builder("safemode.http.duration")
                .tags("service", service, "method", method, "route", route)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /** Una edicion rechazada por version obsoleta. */
    public void conflict() {
        registry.counter("safemode.conflicts", "service", service).increment();
    }

    /** Una operacion que respondio con una funcion degradada (por ejemplo, presencia sin Redis). */
    public void degraded(String feature) {
        registry.counter("safemode.degraded", "service", service, "feature", feature).increment();
    }

    /** Una entrada del historial de cambios del puesto: {@code ok} o {@code error} (se escribe DESPUES del cambio). */
    public void history(String result) {
        registry.counter("safemode.history.writes", "service", service, "result", result).increment();
    }

    /** Resultado de una consulta a la cache de permisos: {@code hit}, {@code miss} o {@code error}. */
    public void cache(String result) {
        registry.counter("safemode.cache", "service", service, "result", result).increment();
    }

    /** Deja los contadores de eventos raros en cero para que Prometheus los vea desde el primer scrape. */
    public void preregister(String... featuresDegradable) {
        registry.counter("safemode.conflicts", "service", service);
        for (String result : new String[] {"ok", "error"}) {
            registry.counter("safemode.history.writes", "service", service, "result", result);
        }
        for (String result : new String[] {"hit", "miss", "error"}) {
            registry.counter("safemode.cache", "service", service, "result", result);
        }
        for (String feature : featuresDegradable) {
            registry.counter("safemode.degraded", "service", service, "feature", feature);
        }
    }

    /** Estado de una dependencia: 1 = responde, 0 = caida. La actualiza {@link DependencyProbe}. */
    public void dependency(String name, boolean up) {
        dependencyGauges.computeIfAbsent(name, n -> {
            AtomicInteger holder = new AtomicInteger();
            registry.gauge("safemode.dependency.up", Tags.of("service", service, "dependency", n), holder);
            return holder;
        }).set(up ? 1 : 0);
    }

    /** Texto en el formato de exposicion de Prometheus. */
    public String scrape() {
        return registry.scrape();
    }

    /** Contador por nombre, para pruebas. */
    Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }
}
