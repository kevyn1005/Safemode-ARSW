package com.safemode.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityTest {
    private HttpServer server;
    private final AtomicBoolean ready = new AtomicBoolean(true);

    @BeforeEach
    void setUp() throws Exception {
        server = Observability.start("127.0.0.1", 0, ready::get);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private HttpResponse<String> get(String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void metricsExponeFormatoPrometheus() throws Exception {
        HttpResponse<String> response = get("/metrics");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertTrue(response.body().contains("jvm_memory_used_bytes"));
    }

    @Test
    void readyResponde200CuandoEstaListo() throws Exception {
        HttpResponse<String> response = get("/ready");
        assertEquals(200, response.statusCode());
        assertEquals("ready", response.body());
    }

    @Test
    void readyResponde503CuandoNoEstaListo() throws Exception {
        ready.set(false);
        HttpResponse<String> response = get("/ready");
        assertEquals(503, response.statusCode());
        assertEquals("not ready", response.body());
    }

    private static final String DB_UP = "safemode_dependency_up{dependency=\"%s\",service=\"gateway\"} %s";

    @Test
    void laBaseApareceComoDependenciaConElMismoNombreQueLosServiciosDePersistencia() throws Exception {
        AtomicBoolean databaseUp = new AtomicBoolean(true);
        ScheduledExecutorService watcher = Observability.watchDependency("db-prueba-sube", databaseUp::get, Duration.ofMillis(50));
        try {
            assertTrue(get("/metrics").body().contains(DB_UP.formatted("db-prueba-sube", "1.0")),
                    "la primera medicion es inmediata: el primer scrape ya dice la verdad");

            databaseUp.set(false);
            awaitMetric(DB_UP.formatted("db-prueba-sube", "0.0"));

            databaseUp.set(true); // la base se recupera (los almacenes se reconectan solos) y el gauge vuelve a 1
            awaitMetric(DB_UP.formatted("db-prueba-sube", "1.0"));
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void unaComprobacionQueLanzaExcepcionCuentaComoDependenciaCaidaYNoMataElPlanificador() throws Exception {
        AtomicBoolean explodes = new AtomicBoolean(true);
        ScheduledExecutorService watcher = Observability.watchDependency("db-prueba-falla", () -> {
            if (explodes.get()) {
                throw new IllegalStateException("la base no contesta");
            }
            return true;
        }, Duration.ofMillis(50));
        try {
            assertTrue(get("/metrics").body().contains(DB_UP.formatted("db-prueba-falla", "0.0")));
            explodes.set(false);
            awaitMetric(DB_UP.formatted("db-prueba-falla", "1.0")); // sigue midiendo despues de la excepcion
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void elScrapeNoEsperaALaBaseElEstadoSeMideEnSegundoPlano() throws Exception {
        AtomicBoolean slow = new AtomicBoolean(false);
        ScheduledExecutorService watcher = Observability.watchDependency("db-prueba-lenta", () -> {
            if (slow.get()) {
                try {
                    Thread.sleep(8000); // mucho mas que el limite de abajo: si el scrape esperara, tardaria casi todo esto
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // shutdownNow() la corta al terminar la prueba
                }
            }
            return true;
        }, Duration.ofMillis(50));
        try {
            assertEquals(200, get("/metrics").statusCode()); // calienta el cliente HTTP: el arranque en frio no es lo que se mide
            slow.set(true);
            Thread.sleep(300); // el hilo de fondo ya esta atascado dentro de la comprobacion lenta
            long started = System.nanoTime();
            HttpResponse<String> response = get("/metrics");
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertEquals(200, response.statusCode());
            assertFalse(millis > 4000, "el scrape tardo " + millis + " ms: esta esperando a la comprobacion lenta (8 s)");
        } finally {
            watcher.shutdownNow();
        }
    }

    private void awaitMetric(String line) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (get("/metrics").body().contains(line)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no aparecio en /metrics: " + line);
    }
}