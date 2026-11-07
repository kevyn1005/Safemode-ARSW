package com.safemode.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El cliente del servicio de colaboracion contra un servidor falso: rutas fijas, identidad, codigos y fallar cerrado. */
class CollaborationClientTest {

    private HttpServer upstream;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile int replyStatus = 200;
    private volatile String replyBody = "{\"ok\":true}";
    private volatile long replyDelayMillis = 0;
    private CollaborationClient client;

    @BeforeEach
    void setUp() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " op=" + exchange.getRequestHeaders().getFirst("X-Operator")
                    + " ct=" + exchange.getRequestHeaders().getFirst("Content-Type")
                    + " body=" + body);
            if (replyDelayMillis > 0) {
                try {
                    Thread.sleep(replyDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(replyStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
        client = new CollaborationClient(baseUrl(), "puesto-1");
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    @Test
    void lasRutasSonFijasYLaIdentidadViajaEnXOperator() {
        client.puesto();
        client.permission("ana");
        client.save("ana", "{\"expectedVersion\":2,\"title\":\"T\"}");
        client.touchPresence("luis");
        client.presence();

        assertEquals(List.of(
                "GET /api/puestos/puesto-1 op=null ct=null body=",
                "GET /api/permissions/puesto-1 op=ana ct=null body=",
                "PUT /api/puestos/puesto-1 op=ana ct=application/json body={\"expectedVersion\":2,\"title\":\"T\"}",
                "POST /api/presence/puesto-1 op=luis ct=null body=",
                "GET /api/presence/puesto-1 op=null ct=null body="), new ArrayList<>(seen));
    }

    @Test
    void reenviaLosCodigosQueElPanelDebeVer() {
        for (int status : new int[] {200, 400, 403, 404, 409, 503}) {
            replyStatus = status;
            replyBody = "{\"s\":" + status + "}";
            PuestoApi.Reply reply = client.save("ana", "{}");
            assertEquals(status, reply.status());
            assertEquals("{\"s\":" + status + "}", reply.body());
        }
    }

    @Test
    void unCodigoInesperadoSeVuelve502UnoDe401NuncaPasaPorqueElPanelLoTomariaPorSesionVencida() {
        for (int status : new int[] {401, 500, 502, 204}) {
            replyStatus = status;
            replyBody = status == 204 ? "" : "{\"detalle\":\"interno\"}";
            PuestoApi.Reply reply = client.puesto();
            assertEquals(502, reply.status(), "HTTP " + status);
            assertTrue(reply.body().contains("Respuesta inesperada"));
            assertTrue(!reply.body().contains("interno"), "no se filtra el detalle interno del servicio");
        }
    }

    @Test
    void unaRespuestaGiganteSeRechazaCon502() {
        replyBody = "x".repeat(300 * 1024);
        assertEquals(502, client.puesto().status());
    }

    @Test
    void conElServicioCaidoSeResponde503AlInstanteYNoSeInventaNada() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        CollaborationClient down = new CollaborationClient("http://127.0.0.1:" + port, "puesto-1");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            PuestoApi.Reply reply = down.save("ana", "{}");
            assertEquals(503, reply.status());
            assertTrue(reply.body().contains("no esta disponible"));
            assertTrue(!reply.body().contains("127.0.0.1"), "el mensaje no revela la direccion interna");
        });
    }

    @Test
    void siElServicioTardaMasDeLoPermitidoSeResponde503() {
        CollaborationClient impatient = new CollaborationClient(baseUrl(), "puesto-1", Duration.ofSeconds(1), Duration.ofMillis(150));
        replyDelayMillis = 800;

        assertEquals(503, impatient.puesto().status());
    }

    @Test
    void unaIdentidadConFormaDeInyeccionNoSeEnvia() {
        PuestoApi.Reply reply = client.save("ana\r\nX-Evil: 1", "{}");
        assertEquals(403, reply.status());
        assertEquals(403, client.permission("ANA").status(), "solo minusculas, digitos, '-' y '_'");
        assertEquals(403, client.touchPresence("").status());
        assertTrue(seen.isEmpty(), "nada llego al servicio: " + seen);
    }

    @Test
    void laDireccionYElPuestoSeValidanAlArrancar() {
        assertThrows(IllegalArgumentException.class, () -> new CollaborationClient("ftp://colaboracion:8110", "puesto-1"));
        assertThrows(IllegalArgumentException.class, () -> new CollaborationClient("http://colaboracion:8110/api", "puesto-1"));
        assertThrows(IllegalArgumentException.class, () -> new CollaborationClient("colaboracion:8110", "puesto-1"));
        assertThrows(IllegalArgumentException.class, () -> new CollaborationClient("http://colaboracion:8110", "Puesto 1"));
        assertThrows(IllegalArgumentException.class, () -> new CollaborationClient("http://colaboracion:8110", "../x"));
        new CollaborationClient("http://colaboracion:8110/", "puesto-1"); // una barra final es valida
    }
}
