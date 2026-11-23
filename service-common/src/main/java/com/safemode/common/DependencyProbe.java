package com.safemode.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Sondea cada pocos segundos si las dependencias (PostgreSQL, MongoDB, Redis, otro servicio) responden y publica el
 * resultado en {@code safemode_dependency_up}. Es lo que permite ver en Grafana que Redis cayo mientras el servicio
 * sigue con {@code up=1}: Prometheus solo sabe que pudo leer las metricas, no que el servicio pueda hacer su trabajo.
 */
public final class DependencyProbe implements AutoCloseable {

    private final ServiceMetrics metrics;
    private final Map<String, BooleanSupplier> probes = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dependency-probe");
        thread.setDaemon(true);
        return thread;
    });

    public DependencyProbe(ServiceMetrics metrics) {
        this.metrics = metrics;
    }

    /** Agrega una dependencia. El sondeo debe tener su propio limite de tiempo (clientes con timeout corto). */
    public DependencyProbe add(String name, BooleanSupplier probe) {
        probes.put(name, probe);
        return this;
    }

    /** Hace un primer sondeo ya y despues uno cada {@code periodSeconds}. */
    public DependencyProbe start(int periodSeconds) {
        scheduler.scheduleWithFixedDelay(this::probeAll, 0, periodSeconds, TimeUnit.SECONDS);
        return this;
    }

    void probeAll() {
        probes.forEach((name, probe) -> {
            boolean up;
            try {
                up = probe.getAsBoolean();
            } catch (RuntimeException e) {
                up = false;
            }
            metrics.dependency(name, up);
        });
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
