package com.safemode.collab;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El cliente de Gestion: lo que NO se puede verificar nunca se convierte en una concesion, y una caida no se paga en cada peticion. */
class AccessClientTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "{\"canConfigure\":true}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AccessClient client(Duration window) {
        return new AccessClient("http://127.0.0.1:" + server.getAddress().getPort(), 500, 1000, window);
    }

    @Test
    void readsTheAuthoritativeAnswer() {
        AccessClient client = client(Duration.ofMillis(100));
        assertTrue(client.canConfigure("luis", "puesto-1"));
        body = "{\"canConfigure\":false}";
        assertFalse(client.canConfigure("luis", "puesto-1"), "una revocacion se ve en la siguiente consulta: no hay cache aqui");
    }

    @Test
    void anythingItCannotVerifyIsUnavailableNeverAGrant() {
        AccessClient client = client(Duration.ofMillis(100));
        body = "no es json";
        assertThrows(AuthorityUnavailableException.class, () -> client.canConfigure("luis", "puesto-1"));
        body = "{\"otra\":true}";
        assertThrows(AuthorityUnavailableException.class, () -> client.canConfigure("luis", "puesto-1"));
        status = 404;
        body = "{\"canConfigure\":true}";
        assertThrows(AuthorityUnavailableException.class, () -> client.canConfigure("luis", "puesto-1"),
                "un 404 con cuerpo que dice true NO concede");
    }

    @Test
    void afterAServerErrorItFailsFastForTheWindowThenTriesAgain() throws InterruptedException {
        AccessClient client = client(Duration.ofMillis(150));
        status = 500;
        assertThrows(AuthorityUnavailableException.class, () -> client.canConfigure("luis", "puesto-1"));
        assertEquals(1, hits.get());

        // dentro de la ventana no se toca la red: se falla al instante (una sola peticion por ventana paga la espera)
        assertThrows(AuthorityUnavailableException.class, () -> client.canConfigure("luis", "puesto-1"));
        assertEquals(1, hits.get(), "no debe haber llegado otra peticion al servidor");

        // Gestion se recupera: pasada la ventana se vuelve a preguntar y responde
        status = 200;
        Thread.sleep(220);
        assertTrue(client.canConfigure("luis", "puesto-1"));
        assertEquals(2, hits.get());
    }

    @Test
    void anUnreachableServerIsUnavailable() {
        AccessClient client = client(Duration.ofMillis(100));
        server.stop(0);
        assertThrows(AuthorityUnavailableException.class, () -> client.canConfigure("luis", "puesto-1"));
        assertFalse(client.isUp());
    }
}
