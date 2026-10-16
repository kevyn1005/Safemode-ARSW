package com.safemode.gateway.notify;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Avisa por un bot de Telegram ({@code sendMessage}). La clave del bot y los chats vienen SOLO de variables de entorno
 * ({@code SAFEMODE_TELEGRAM_BOT_TOKEN} y {@code SAFEMODE_TELEGRAM_CHAT_IDS}); nunca se escriben en el codigo ni en los registros.
 * <p>
 * Mientras no haya inicio de sesion, los chats a avisar son una lista fija: cuando exista el login cada usuario vinculara el suyo
 * (ADR 0004). El mensaje es texto plano (sin {@code parse_mode}) para que nada de lo que venga en el texto se interprete.
 */
public final class TelegramChannel implements NotificationChannel {

    public static final String TOKEN_VARIABLE = "SAFEMODE_TELEGRAM_BOT_TOKEN";
    public static final String CHATS_VARIABLE = "SAFEMODE_TELEGRAM_CHAT_IDS";
    static final URI DEFAULT_API = URI.create("https://api.telegram.org");

    // la clave va dentro de la URL: solo se aceptan los caracteres que usa Telegram, nunca "/", "?", espacios ni nada que cambie la ruta
    private static final Pattern TOKEN = Pattern.compile("[0-9]+:[A-Za-z0-9_-]+");
    private static final Pattern CHAT_ID = Pattern.compile("-?[0-9]{1,18}");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String token;
    private final List<String> chatIds;
    private final URI apiBase;
    private final HttpClient http;

    public TelegramChannel(String token, List<String> chatIds) {
        this(token, chatIds, DEFAULT_API);
    }

    /** Con otra direccion de la API (las pruebas usan un servidor falso local). */
    TelegramChannel(String token, List<String> chatIds, URI apiBase) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("La clave del bot de Telegram no tiene el formato esperado");
        }
        this.token = token;
        this.chatIds = List.copyOf(chatIds);
        this.apiBase = apiBase;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    }

    /**
     * El canal si estan definidas la clave y al menos un chat valido; si no, vacio. Los chats con formato raro se ignoran (se
     * avisa cuantos, sin escribir su valor) y la clave con formato raro desactiva el canal sin escribirla.
     */
    public static Optional<TelegramChannel> fromEnvironment(Map<String, String> env) {
        String token = env.get(TOKEN_VARIABLE) == null ? "" : env.get(TOKEN_VARIABLE).strip();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        if (!TOKEN.matcher(token).matches()) {
            System.err.println("[NOTIF] " + TOKEN_VARIABLE + " no tiene el formato de una clave de bot (numeros:letras); Telegram queda desactivado.");
            return Optional.empty();
        }
        List<String> chats = parseChatIds(env.get(CHATS_VARIABLE));
        if (chats.isEmpty()) {
            System.err.println("[NOTIF] Falta " + CHATS_VARIABLE + " (el chat al que avisar); Telegram queda desactivado. "
                    + "Para averiguarlo: mvn -pl realtime-gateway exec:java \"-Dexec.mainClass=com.safemode.gateway.notify.TelegramChatIds\"");
            return Optional.empty();
        }
        return Optional.of(new TelegramChannel(token, chats));
    }

    /** Los chats separados por comas o espacios; ignora (y cuenta) los que no son un numero. */
    static List<String> parseChatIds(String raw) {
        List<String> chats = new ArrayList<>();
        int ignored = 0;
        if (raw != null) {
            for (String part : raw.split("[,\\s]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (CHAT_ID.matcher(part).matches()) {
                    if (!chats.contains(part)) {
                        chats.add(part);
                    }
                } else {
                    ignored++;
                }
            }
        }
        if (ignored > 0) {
            System.err.println("[NOTIF] " + ignored + " chat(s) de " + CHATS_VARIABLE + " se ignoraron: deben ser numeros.");
        }
        return chats;
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public List<String> recipients() {
        return chatIds;
    }

    @Override
    public void send(String chatId, String text) throws NotificationException {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", Long.parseLong(chatId));
        body.addProperty("text", text);
        body.addProperty("disable_web_page_preview", true);
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/bot" + token + "/sendMessage"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new NotificationException("Telegram respondio HTTP " + response.statusCode());
            }
        } catch (IOException e) {
            // el mensaje de la excepcion puede traer la URL (con la clave): solo se dice de que tipo fue
            throw new NotificationException("no se pudo conectar con Telegram (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationException("el aviso por Telegram se interrumpio");
        }
    }
}
