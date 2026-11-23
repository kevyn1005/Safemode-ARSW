package com.safemode.collab;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.safemode.common.FailFastWindow;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente de Gestion de acceso. Los tiempos de espera son cortos y explicitos: si Gestion no contesta, la escritura
 * debe fallar pronto con 503 (fail-closed), no quedarse esperando varios segundos. Cualquier cosa distinta de una
 * respuesta 200 valida (conexion rechazada, tiempo agotado, 5xx, cuerpo ilegible) se informa como
 * {@link AuthorityUnavailableException}: ante la duda NO se concede.
 */
public class AccessClient implements Authority {

    private final HttpClient client;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final FailFastWindow failFast;

    public AccessClient(String baseUrl, int connectTimeoutMs, int requestTimeoutMs) {
        this(baseUrl, connectTimeoutMs, requestTimeoutMs, Duration.ofSeconds(2));
    }

    /** @param failFastWindow lo que se rechaza al instante tras una falla antes de volver a intentar */
    public AccessClient(String baseUrl, int connectTimeoutMs, int requestTimeoutMs, Duration failFastWindow) {
        this.failFast = new FailFastWindow(failFastWindow);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
    }

    @Override
    public boolean canConfigure(String operator, String puesto) {
        JsonObject body = parse(send(HttpRequest.newBuilder(URI.create(baseUrl + "/permissions/" + puesto + "/" + operator)).GET()));
        if (!body.has("canConfigure") || !body.get("canConfigure").isJsonPrimitive()) {
            throw new AuthorityUnavailableException("Respuesta de Gestion sin canConfigure", null);
        }
        return body.get("canConfigure").getAsBoolean();
    }

    /** Restablece los datos ficticios de Gestion (solo para repetir pruebas). */
    public void reset(String adminKey) {
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/lab/reset")).header("X-Lab-Key", adminKey)
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** Sondeo de la metrica de dependencia: Gestion esta viva si responde su /health. */
    public boolean isUp() {
        try {
            send(HttpRequest.newBuilder(URI.create(baseUrl + "/health")).GET());
            return true;
        } catch (AuthorityUnavailableException e) {
            return false;
        }
    }

    private String send(HttpRequest.Builder request) {
        // Con Gestion caida, cada intento espera la resolucion del nombre (hasta ~2 s). Tras una falla se rechaza al instante
        // durante una ventana corta: solo una peticion por ventana paga la espera. Sigue siendo fail-closed (503).
        if (failFast.isOpen()) {
            throw new AuthorityUnavailableException("Gestion no disponible (fallo reciente)", null);
        }
        try {
            HttpResponse<String> response = client.send(request.timeout(requestTimeout).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) {
                failFast.recordFailure();
            }
            if (response.statusCode() != 200) {
                throw new AuthorityUnavailableException("Gestion respondio HTTP " + response.statusCode(), null);
            }
            failFast.recordSuccess();
            return response.body();
        } catch (IOException e) {
            failFast.recordFailure();
            throw new AuthorityUnavailableException("Gestion no disponible: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthorityUnavailableException("Consulta a Gestion interrumpida", e);
        }
    }

    private static JsonObject parse(String text) {
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new AuthorityUnavailableException("Respuesta ilegible de Gestion", e);
        }
    }
}
