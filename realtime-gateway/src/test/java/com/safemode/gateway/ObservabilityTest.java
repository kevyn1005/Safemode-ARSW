package com.safemode.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}