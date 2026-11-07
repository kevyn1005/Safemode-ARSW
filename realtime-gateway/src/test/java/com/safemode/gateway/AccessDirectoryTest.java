package com.safemode.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El correo de la sesion -> operador de Gestion de acceso: solo autorizados, alta la primera vez, memoria y fallar cerrado. */
class AccessDirectoryTest {

    private HttpServer access;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile int replyStatus = 200;
    private volatile String replyBody = "{\"id\":\"ana\",\"created\":true}";
    private AccessDirectory directory;

    @BeforeEach
    void setUp() throws IOException {
        access = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        access.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " key=" + exchange.getRequestHeaders().getFirst("X-Lab-Key") + " body=" + body);
            byte[] bytes = replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(replyStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        access.start();
        directory = new AccessDirectory(baseUrl(), "clave-de-prueba", "puesto-1",
                Set.of("ana@example.com", "luis@example.com"), Set.of("ana@example.com"));
    }

    @AfterEach
    void tearDown() {
        access.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + access.getAddress().getPort();
    }

    @Test
    void unAdministradorSeDaDeAltaConPermisoDeConfigurar() {
        assertEquals(Optional.of("ana"), directory.operatorFor("ana@example.com"));
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).startsWith("POST /operators/provision key=clave-de-prueba "));
        assertTrue(seen.get(0).contains("\"role\":\"admin\"") && seen.get(0).contains("\"canConfigure\":true"), seen.get(0));
        assertTrue(seen.get(0).contains("\"puesto\":\"puesto-1\""));
    }

    @Test
    void unAutorizadoQueNoEsAdministradorSoloPuedeVer() {
        replyBody = "{\"id\":\"luis\"}";
        assertEquals(Optional.of("luis"), directory.operatorFor("Luis@Example.com"));
        assertTrue(seen.get(0).contains("\"email\":\"luis@example.com\""), "el correo se normaliza a minusculas");
        assertTrue(seen.get(0).contains("\"role\":\"viewer\"") && seen.get(0).contains("\"canConfigure\":false"), seen.get(0));
    }

    @Test
    void unCorreoFueraDeLaListaNoLlamaANadie() {
        assertEquals(Optional.empty(), directory.operatorFor("intruso@example.com"));
        assertEquals(Optional.empty(), directory.operatorFor(null));
        assertEquals(Optional.empty(), directory.operatorFor(""));
        assertTrue(seen.isEmpty(), "no se da de alta a quien no esta autorizado: " + seen);
    }

    @Test
    void elIdDeUnOperadorSeRecuerdaYNoSeVuelveAPreguntar() {
        directory.operatorFor("ana@example.com");
        directory.operatorFor("ana@example.com");
        directory.operatorFor("ANA@example.com");
        assertEquals(1, seen.size());
    }

    @Test
    void siGestionDeAccesoFallaSeLanzaLaFallaYNoSeRecuerdaNada() {
        replyStatus = 503;
        replyBody = "{\"error\":\"PostgreSQL no disponible\"}";
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> directory.operatorFor("ana@example.com"));
        assertTrue(e.getMessage().contains("HTTP 503"));
        assertFalse(e.getMessage().contains("clave-de-prueba"), "la clave nunca aparece en un mensaje de error");

        replyStatus = 200; // se recupera: la siguiente consulta vuelve a preguntar (no quedo un fallo guardado)
        replyBody = "{\"id\":\"ana\"}";
        assertEquals(Optional.of("ana"), directory.operatorFor("ana@example.com"));
        assertEquals(2, seen.size());
    }

    @Test
    void unaRespuestaSinIdOMalFormadaEsUnaFalla() {
        replyBody = "{\"otro\":1}";
        assertThrows(IllegalStateException.class, () -> directory.operatorFor("ana@example.com"));
        replyBody = "no es json";
        assertThrows(IllegalStateException.class, () -> directory.operatorFor("ana@example.com"));
    }

    @Test
    void conGestionDeAccesoCaidaSeLanzaLaFallaSinRevelarLaDireccion() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        AccessDirectory down = new AccessDirectory("http://127.0.0.1:" + port, "clave", "puesto-1",
                Set.of("ana@example.com"), Set.of());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> down.operatorFor("ana@example.com"));
        assertTrue(e.getMessage().contains("no disponible"));
        assertFalse(e.getMessage().contains("127.0.0.1"));
    }

    @Test
    void laDireccionYLaClaveSeValidanAlArrancar() {
        Set<String> none = Set.of();
        assertThrows(IllegalArgumentException.class, () -> new AccessDirectory("ftp://acceso:8100", "k", "puesto-1", none, none));
        assertThrows(IllegalArgumentException.class, () -> new AccessDirectory("http://acceso:8100/x", "k", "puesto-1", none, none));
        assertThrows(IllegalArgumentException.class, () -> new AccessDirectory("http://acceso:8100", " ", "puesto-1", none, none));
        assertThrows(IllegalArgumentException.class, () -> new AccessDirectory("http://acceso:8100", null, "puesto-1", none, none));
    }
}
