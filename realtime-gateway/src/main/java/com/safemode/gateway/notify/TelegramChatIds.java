package com.safemode.gateway.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ayuda para averiguar el numero del chat al que avisar: despues de escribirle {@code /start} a tu bot en Telegram, muestra los
 * chats que le han escrito. Lee la clave de {@code SAFEMODE_TELEGRAM_BOT_TOKEN} (nunca como argumento ni se imprime).
 * <pre>
 *   mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.notify.TelegramChatIds"
 * </pre>
 */
public final class TelegramChatIds {

    /** Un chat que le escribio al bot. */
    record Chat(long id, String type, String name) {}

    private TelegramChatIds() {}

    public static void main(String[] args) throws Exception {
        String token = System.getenv(TelegramChannel.TOKEN_VARIABLE);
        if (token == null || !token.strip().matches("[0-9]+:[A-Za-z0-9_-]+")) {
            System.out.println("Define primero " + TelegramChannel.TOKEN_VARIABLE + " con la clave que te dio BotFather (sin pegarla en el chat).");
            return;
        }
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(TelegramChannel.DEFAULT_API + "/bot" + token.strip() + "/getUpdates?limit=100"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            System.out.println("No se pudo conectar con Telegram (" + e.getClass().getSimpleName() + ").");   // el mensaje puede traer la URL con la clave
            return;
        }
        if (response.statusCode() != 200) {
            System.out.println("Telegram respondio HTTP " + response.statusCode() + ": revisa la clave del bot.");
            return;
        }
        List<Chat> chats = parse(response.body());
        if (chats.isEmpty()) {
            System.out.println("Todavia nadie le ha escrito al bot: abre tu bot en Telegram, escribe /start y vuelve a ejecutar esto.");
            return;
        }
        System.out.println("Chats que le han escrito al bot (usa el numero en " + TelegramChannel.CHATS_VARIABLE + "):");
        for (Chat chat : chats) {
            System.out.println("  " + chat.id() + "  (" + chat.type() + (chat.name().isEmpty() ? "" : ", " + chat.name()) + ")");
        }
    }

    /** Los chats distintos de la respuesta de {@code getUpdates}, en el orden en que aparecen. */
    static List<Chat> parse(String json) {
        Map<Long, Chat> chats = new LinkedHashMap<>();
        JsonArray updates = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("result");
        if (updates == null) {
            return List.of();
        }
        for (JsonElement update : updates) {
            JsonObject object = update.getAsJsonObject();
            for (String kind : new String[]{"message", "edited_message", "channel_post"}) {
                JsonElement message = object.get(kind);
                if (message == null || !message.isJsonObject() || !message.getAsJsonObject().has("chat")) {
                    continue;
                }
                JsonObject chat = message.getAsJsonObject().getAsJsonObject("chat");
                long id = chat.get("id").getAsLong();
                String name = text(chat, "title");
                if (name.isEmpty()) {
                    name = text(chat, "first_name");
                }
                chats.putIfAbsent(id, new Chat(id, text(chat, "type"), name));
                break;
            }
        }
        return new ArrayList<>(chats.values());
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
