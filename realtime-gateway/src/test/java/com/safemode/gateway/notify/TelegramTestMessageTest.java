package com.safemode.gateway.notify;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramTestMessageTest {

    private static final String TOKEN = "123456789:AAH-fake_TOKEN-for-tests-0123456789";

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void startFakeTelegram() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
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

    private Optional<TelegramChannel> channel(String... chats) {
        return Optional.of(new TelegramChannel(TOKEN, List.of(chats), URI.create("http://localhost:" + server.getAddress().getPort())));
    }

    @Test
    void sinConfiguracionDiceQueVariablesHacenFalta() {
        String result = TelegramTestMessage.run(Optional.empty());

        assertTrue(result.contains("SAFEMODE_TELEGRAM_BOT_TOKEN"));
        assertTrue(result.contains("SAFEMODE_TELEGRAM_CHAT_IDS"));
        assertTrue(result.contains("terminal NUEVA"));
    }

    @Test
    void mandaElMensajeDePruebaACadaChat() {
        String result = TelegramTestMessage.run(channel("111", "222"));

        assertEquals("Mensaje de prueba enviado a 2 chats: revisa Telegram.", result);
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(0).contains("mensaje de prueba"));
    }

    @Test
    void unChatMalDaUnaPistaSinLaClave() {
        status = 400;

        String result = TelegramTestMessage.run(channel("111"));

        assertTrue(result.startsWith("No se pudo enviar a 1 chat: Telegram respondio HTTP 400."), result);
        assertTrue(result.contains("/start"));
        assertFalse(result.contains(TOKEN));
    }

    @Test
    void unaClaveInvalidaDiceQueSeCopieDeNuevo() {
        status = 401;

        String result = TelegramTestMessage.run(channel("111"));

        assertTrue(result.contains("HTTP 401"));
        assertTrue(result.contains("BotFather"));
        assertFalse(result.contains(TOKEN));
    }
}
