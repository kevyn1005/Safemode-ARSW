package com.safemode.common;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniHttpServerTest {

    private ServiceMetrics metrics;
    private MiniHttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        metrics = new ServiceMetrics("prueba");
        server = new MiniHttpServer("127.0.0.1", 0, metrics, 4)
                .route("GET", "/api/puestos/{id}", request -> ok(Map.of("id", request.param("id"))))
                .route("PUT", "/api/puestos/{id}", request -> {
                    JsonObject body = Json.parseObject(request.body());
                    if (!body.has("expectedVersion")) {
                        throw new HttpError(409, "version obsoleta");
                    }
                    return MiniHttpServer.Response.json(200, Map.of("ok", true, "operator", String.valueOf(request.header("X-Operator"))));
                })
                .route("GET", "/api/falla", request -> {
                    throw new IllegalStateException("detalle interno que no debe llegar al cliente");
                });
        server.start();
    }

    private static MiniHttpServer.Response ok(Object body) {
        return MiniHttpServer.Response.json(200, body);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + path));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bindsPathParametersAndReadsHeadersCaseInsensitively() throws Exception {
        assertEquals("{\"id\":\"puesto-1\"}", send("GET", "/api/puestos/puesto-1", null).body());
        HttpResponse<String> put = send("PUT", "/api/puestos/puesto-1", "{\"expectedVersion\":1}", "x-operator", "ana");
        assertEquals(200, put.statusCode());
        assertTrue(put.body().contains("\"operator\":\"ana\""));
    }

    @Test
    void mapsHttpErrorsAndMalformedBodiesToTheirStatus() throws Exception {
        assertEquals(409, send("PUT", "/api/puestos/p", "{\"title\":\"x\"}").statusCode());
        assertEquals(400, send("PUT", "/api/puestos/p", "esto no es json").statusCode());
        assertEquals(400, send("PUT", "/api/puestos/p", "").statusCode());
    }

    @Test
    void unknownPathIs404AndWrongMethodIs405() throws Exception {
        assertEquals(404, send("GET", "/no/existe", null).statusCode());
        assertEquals(405, send("DELETE", "/api/puestos/p", null).statusCode());
    }

    @Test
    void unexpectedErrorsAre500WithoutLeakingDetails() throws Exception {
        HttpResponse<String> response = send("GET", "/api/falla", null);
        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("detalle interno"));
    }

    @Test
    void recordsMetricsByRouteTemplateAndSkipsHealthAndMetrics() throws Exception {
        send("GET", "/api/puestos/uno", null);
        send("GET", "/api/puestos/dos", null);
        send("PUT", "/api/puestos/uno", "{}");
        send("GET", "/health", null);

        String text = send("GET", "/metrics", null).body();
        // las dos lecturas con ids distintos caen en UNA sola serie (plantilla), y el 409 se cuenta aparte del 5xx
        assertTrue(text.contains("safemode_http_requests_total{method=\"GET\",route=\"/api/puestos/{id}\",service=\"prueba\",status=\"200\"} 2"), text);
        assertTrue(text.contains("status=\"409\""), text);
        assertFalse(text.contains("route=\"/health\""), "no se mide /health");
        assertFalse(text.contains("route=\"/metrics\""), "no se mide /metrics");
        assertTrue(text.contains("safemode_http_duration_seconds_bucket"), text);
    }

    @Test
    void dependencyGaugeAndEventCountersAreExposed() throws Exception {
        metrics.preregister("presence");
        metrics.dependency("redis", false);
        metrics.conflict();
        metrics.degraded("presence");
        metrics.cache("hit");

        String text = send("GET", "/metrics", null).body();
        assertTrue(text.contains("safemode_dependency_up{dependency=\"redis\",service=\"prueba\"} 0"), text);
        assertTrue(text.contains("safemode_conflicts_total{service=\"prueba\"} 1"), text);
        assertTrue(text.contains("safemode_degraded_total{feature=\"presence\",service=\"prueba\"} 1"), text);
        assertTrue(text.contains("safemode_cache_total{result=\"hit\",service=\"prueba\"} 1"), text);
        assertTrue(text.contains("safemode_cache_total{result=\"miss\",service=\"prueba\"} 0"), "preregistrado en cero");

        metrics.dependency("redis", true);
        assertTrue(send("GET", "/metrics", null).body().contains("safemode_dependency_up{dependency=\"redis\",service=\"prueba\"} 1"));
    }
}
