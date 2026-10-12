package com.safemode.gateway.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manda un mensaje de prueba a los chats configurados, para comprobar Telegram sin cámara y sin esperar una alerta. Lee la clave y
 * los chats de las variables de entorno (nunca como argumento) y no escribe ninguna de las dos.
 * <pre>
 *   mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.notify.TelegramTestMessage"
 * </pre>
 */
public final class TelegramTestMessage {

    static final String TEXT = "SafeMode: mensaje de prueba. Si lo ves, los avisos por Telegram funcionan.";

    private TelegramTestMessage() {}

    public static void main(String[] args) {
        System.out.println(run(TelegramChannel.fromEnvironment(System.getenv())));
    }

    /** Manda la prueba por el canal (si lo hay) y devuelve lo que hay que decirle al usuario. */
    static String run(Optional<TelegramChannel> channel) {
        if (channel.isEmpty()) {
            return "Telegram no esta configurado: define " + TelegramChannel.TOKEN_VARIABLE + " y " + TelegramChannel.CHATS_VARIABLE
                    + " (abre una terminal NUEVA despues de usar setx). Pasos en el README, seccion \"Avisos de alertas\".";
        }
        int sent = 0;
        List<String> failures = new ArrayList<>();
        for (String chat : channel.get().recipients()) {
            try {
                channel.get().send(chat, TEXT);
                sent++;
            } catch (NotificationException e) {
                failures.add(e.getMessage());
            }
        }
        StringBuilder result = new StringBuilder();
        if (sent > 0) {
            result.append("Mensaje de prueba enviado a ").append(sent).append(sent == 1 ? " chat" : " chats").append(": revisa Telegram.");
        }
        if (!failures.isEmpty()) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append("No se pudo enviar a ").append(failures.size()).append(failures.size() == 1 ? " chat: " : " chats: ")
                    .append(String.join("; ", failures)).append('.');
            if (failures.stream().anyMatch(f -> f.contains("HTTP 400") || f.contains("HTTP 403"))) {
                result.append(" Revisa que le hayas escrito /start a tu bot y que el numero del chat sea el correcto.");
            }
            if (failures.stream().anyMatch(f -> f.contains("HTTP 401") || f.contains("HTTP 404"))) {
                result.append(" La clave del bot no es valida: copiala de nuevo desde BotFather.");
            }
        }
        return result.toString();
    }
}
