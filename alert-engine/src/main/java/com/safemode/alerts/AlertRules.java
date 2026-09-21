package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore.StoredEvent;
import com.safemode.presence.RemovalKind;

import java.util.Map;
import java.util.Optional;

/**
 * Decide si un evento de presencia merece una alerta. Solo los retiros (REMOVED) pueden generarla, y el retiro normal
 * por el dueno no. La descripcion de quien se llevo el objeto viene del tracker; aqui solo se traduce a una frase.
 */
public final class AlertRules {

    /** Lo que se decidio para un evento: el tipo, la urgencia y la frase que vera el vigilante. */
    public record Decision(AlertType type, Severity severity, String message) {}

    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final Map<String, String> CLASS_NAMES_ES = Map.of(
            "backpack", "mochila", "handbag", "bolso", "suitcase", "maleta");

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
        String object = CLASS_NAMES_ES.getOrDefault(event.className(), event.className());
        String owner = event.ownerDescription() != null ? " (dueño: " + event.ownerDescription() + ")" : "";
        String remover = describeRemover(event);

        return switch (kind) {
            case BY_OWNER -> Optional.empty();
            case BY_OTHER -> Optional.of(decision(AlertType.POSSIBLE_THEFT, Severity.HIGH,
                    "Posible robo: la " + object + " fue retirada por alguien distinto del dueño" + remover + owner));
            case OWNER_AND_OTHER_NEAR -> Optional.of(decision(AlertType.AMBIGUOUS_REMOVAL, Severity.MEDIUM,
                    "Retiro ambiguo: la " + object + " se retiró con el dueño y otra persona cerca" + remover + owner));
            case NO_ONE_NEAR -> Optional.of(decision(AlertType.OBJECT_VANISHED, Severity.MEDIUM,
                    "La " + object + " desapareció sin nadie cerca" + owner));
            case OWNER_UNKNOWN -> Optional.of(decision(AlertType.UNKNOWN_OWNER_REMOVAL, Severity.LOW,
                    "Retiraron una " + object + " que no tenía dueño registrado" + remover));
        };
    }

    private static String describeRemover(StoredEvent event) {
        if (event.removerDescription() == null) {
            return "";
        }
        return ": " + event.removerDescription();
    }

    private static Decision decision(AlertType type, Severity severity, String message) {
        String text = message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
        return new Decision(type, severity, text);
    }
}
