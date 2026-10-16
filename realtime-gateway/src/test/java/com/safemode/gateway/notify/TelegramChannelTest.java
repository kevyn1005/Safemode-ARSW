package com.safemode.gateway.notify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contra un servidor falso local: nunca sale a la red ni usa una clave real. */
class TelegramChannelTest {

    private static final String TOKEN = "123456789:AAH-fake_TOKEN-for-tests-0123456789";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();   // "METODO ruta | content-type | cuerpo"
    private volatile int status = 200;

    @BeforeEach
    void startFakeTelegram() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " | "
                    + exchange.getRequestHeaders().getFirst("Content-Type") + " | " + body);
            byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeTelegram() {
        server.stop(0);
    }

    private TelegramChannel channel(String... chats) {
        return new TelegramChannel(TOKEN, List.of(chats), URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private JsonObject lastBody() {
        String last = requests.get(requests.size() - 1);
        return JsonParser.parseString(last.substring(last.indexOf(" | ", last.indexOf(" | ") + 3) + 3)).getAsJsonObject();
    }

    @Test
    void mandaElTextoComoJsonPlanoAlChatCorrecto() throws Exception {
        channel("987654321").send("987654321", "SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.");

        assertEquals(1, requests.size());
        assertTrue(requests.get(0).startsWith("POST /bot" + TOKEN + "/sendMessage | application/json | "), requests.get(0));
        JsonObject body = lastBody();
        assertEquals(987654321L, body.get("chat_id").getAsLong());
        assertEquals("SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.", body.get("text").getAsString());
        assertFalse(body.has("parse_mode"), "texto plano: nada de lo que venga en el texto se interpreta");
        assertTrue(body.get("disable_web_page_preview").getAsBoolean());
        assertEquals(Map.of("chat_id", true, "text", true, "disable_web_page_preview", true).keySet(), body.keySet(), "sin fotos ni otros campos");
    }

    @Test
    void unChatDeGrupoQueEmpiezaConMenosSeEnviaComoNumero() throws Exception {
        channel("-1001234567890").send("-1001234567890", "hola");

        assertEquals(-1001234567890L, lastBody().get("chat_id").getAsLong());
    }

    @Test
    void siTelegramRespondeConErrorElMensajeNoLlevaLaClave() {
        status = 401;

        NotificationException error = assertThrows(NotificationException.class, () -> channel("1").send("1", "hola"));

        assertEquals("Telegram respondio HTTP 401", error.getMessage());
        assertFalse(error.getMessage().contains(TOKEN));
    }

    @Test
    void sinConexionElMensajeNoLlevaLaClaveNiLaDireccion() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        TelegramChannel unreachable = new TelegramChannel(TOKEN, List.of("1"), URI.create("http://localhost:" + closedPort));

        NotificationException error = assertThrows(NotificationException.class, () -> unreachable.send("1", "hola"));

        assertTrue(error.getMessage().startsWith("no se pudo conectar con Telegram ("), error.getMessage());
        assertFalse(error.getMessage().contains(TOKEN));
        assertFalse(error.getMessage().contains("localhost"));
    }

    @Test
    void unaClaveConFormatoRaroSeRechazaSinEscribirla() {
        for (String bad : new String[]{"abc/def", "123:abc?x=1", "123:a b", "123:abc/../x", "sin-dos-puntos", ":solo", "", null}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new TelegramChannel(bad, List.of("1"), URI.create("http://localhost:1")), String.valueOf(bad));
            assertFalse(bad != null && !bad.isEmpty() && error.getMessage().contains(bad), "el mensaje no repite la clave");
        }
    }

    @Test
    void sinVariablesElCanalEstaDesactivado() {
        assertEquals(Optional.empty(), TelegramChannel.fromEnvironment(Map.of()));
        assertEquals(Optional.empty(), TelegramChannel.fromEnvironment(Map.of(TelegramChannel.TOKEN_VARIABLE, "   ")));
    }

    @Test
    void conLaClavePeroSinChatsElCanalEstaDesactivado() {
        assertEquals(Optional.empty(), TelegramChannel.fromEnvironment(Map.of(TelegramChannel.TOKEN_VARIABLE, TOKEN)));
        assertEquals(Optional.empty(), TelegramChannel.fromEnvironment(
                Map.of(TelegramChannel.TOKEN_VARIABLE, TOKEN, TelegramChannel.CHATS_VARIABLE, "no-es-un-numero")));
    }

    @Test
    void conUnaClaveDeFormatoRaroElCanalEstaDesactivado() {
        assertEquals(Optional.empty(), TelegramChannel.fromEnvironment(
                Map.of(TelegramChannel.TOKEN_VARIABLE, "123:abc/../x", TelegramChannel.CHATS_VARIABLE, "1")));
    }

    @Test
    void losChatsSeLeenSeparadosPorComasOEspaciosSinRepetirYIgnorandoLosRaros() {
        Optional<TelegramChannel> channel = TelegramChannel.fromEnvironment(
                Map.of(TelegramChannel.TOKEN_VARIABLE, " " + TOKEN + " ", TelegramChannel.CHATS_VARIABLE, "123, -456  abc 123,789"));

        assertTrue(channel.isPresent());
        assertEquals(List.of("123", "-456", "789"), channel.get().recipients());
        assertEquals("telegram", channel.get().name());
    }
}
