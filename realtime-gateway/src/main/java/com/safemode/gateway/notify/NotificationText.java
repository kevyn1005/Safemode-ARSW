package com.safemode.gateway.notify;

import com.safemode.alerts.Alert;
import com.safemode.alerts.AlertType;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * El texto del aviso. Es SOLO texto y no lleva datos de personas: ni fotos, ni descripciones, ni identidades (el aviso pasa por
 * un servicio de terceros). Dice que paso, en que camara y a que hora, y manda a mirar el panel, donde esta la evidencia.
 */
public final class NotificationText {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_CAMERA_NAME = 40;

    private NotificationText() {}

    /** Ejemplo: {@code SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.} */
    public static String of(Alert alert, String cameraName, ZoneId zone) {
        return "SafeMode: " + phrase(alert.type()) + " en " + cleanCamera(cameraName) + " a las "
                + HOUR.format(alert.eventOccurredAt().atZone(zone)) + ". Revisa el panel para verificar.";
    }

    static String phrase(AlertType type) {
        return switch (type) {
            case POSSIBLE_THEFT -> "posible robo";
            case AMBIGUOUS_REMOVAL -> "retiro ambiguo";
            case OBJECT_VANISHED -> "objeto desaparecido";
            case UNKNOWN_OWNER_REMOVAL -> "retiro sin dueño registrado";
            case POSSIBLE_OWNER_RETURN -> "posible dueño que volvió";
        };
    }

    /** El nombre lo escribe el usuario: sin caracteres de control y con un largo maximo. */
    static String cleanCamera(String name) {
        String clean = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").strip();
        if (clean.isEmpty()) {
            return "la cámara";
        }
        return clean.length() > MAX_CAMERA_NAME ? clean.substring(0, MAX_CAMERA_NAME) : clean;
    }
}
