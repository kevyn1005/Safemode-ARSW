package com.safemode.gateway;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Expone /metrics (Prometheus) y /ready (sonda funcional) en un puerto aparte. */
public final class Observability {

    public static final PrometheusMeterRegistry REGISTRY =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    private Observability() {}

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