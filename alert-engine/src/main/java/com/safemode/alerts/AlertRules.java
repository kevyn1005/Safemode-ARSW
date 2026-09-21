package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore.StoredEvent;
import com.safemode.presence.RemovalKind;

import java.util.Map;
import java.util.Optional;

/**
 * Decide si un evento de presencia merece una alerta. Solo los retiros (REMOVED) pueden generarla, y el retiro normal
 * por el dueno no. La frase dice que ocurrio, sin describir a las personas: la alerta se crea al instante y el color
 * local puede ser aproximado (o faltar) hasta que llega la descripcion por IA; el dashboard las muestra aparte, con la foto.
 */
public final class AlertRules {

    /** Lo que se decidio para un evento: el tipo, la urgencia y la frase que vera el vigilante. */
    public record Decision(AlertType type, Severity severity, String message) {}

    private static final int MAX_MESSAGE_LENGTH = 500;
    /** Nombre del objeto en espanol y su genero, para concordar el articulo y el participio ("la mochila", "el bolso"). */
    private record Noun(String name, boolean feminine) {
        String the() {
            return feminine ? "la" : "el";
        }

        String a() {
            return feminine ? "una" : "un";
        }

        String removed() {
            return feminine ? "retirada" : "retirado";
        }
    }

    private static final Map<String, Noun> NOUNS_ES = Map.of(
            "backpack", new Noun("mochila", true), "handbag", new Noun("bolso", false), "suitcase", new Noun("maleta", true));

    private AlertRules() {}

    public static Optional<Decision> evaluate(StoredEvent event) {
        if (!"REMOVED".equals(event.eventType()) || event.removalKind() == null) {
            return Optional.empty();
        }
        RemovalKind kind;
        try {
            kind = RemovalKind.valueOf(event.removalKind());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Noun object = NOUNS_ES.getOrDefault(event.className(), new Noun(event.className(), false));
        return switch (kind) {
            case BY_OWNER -> Optional.empty();
            case BY_OTHER -> Optional.of(decision(AlertType.POSSIBLE_THEFT, Severity.HIGH,
                    "Posible robo: " + object.the() + " " + object.name() + " fue " + object.removed() + " por alguien distinto del dueño"));
            case OWNER_AND_OTHER_NEAR -> Optional.of(decision(AlertType.AMBIGUOUS_REMOVAL, Severity.MEDIUM,
                    "Retiro ambiguo: " + object.the() + " " + object.name() + " se retiró con el dueño y otra persona cerca"));
            case NO_ONE_NEAR -> Optional.of(decision(AlertType.OBJECT_VANISHED, Severity.MEDIUM,
                    capitalize(object.the()) + " " + object.name() + " desapareció sin nadie cerca"));
            case OWNER_UNKNOWN -> Optional.of(decision(AlertType.UNKNOWN_OWNER_REMOVAL, Severity.LOW,
                    "Retiraron " + object.a() + " " + object.name() + " que no tenía dueño registrado"));
        };
    }

    private static String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static Decision decision(AlertType type, Severity severity, String message) {
        String text = message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
        return new Decision(type, severity, text);
    }
}
