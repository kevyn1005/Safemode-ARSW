package com.safemode.presence;

import java.util.Locale;

/**
 * Como se usa el modelo de verificacion de retiro ({@link RemovalClassifier}) dentro del {@link ObjectTracker}.
 * <ul>
 *   <li>{@link #OFF}: no se usa; el seguimiento decide como siempre (por defecto).</li>
 *   <li>{@link #SHADOW}: "modo sombra": el modelo calcula su probabilidad y se muestra por consola, pero decide la regla de
 *       siempre. No cambia ninguna alerta: sirve para ver al modelo trabajar y medirlo sin riesgo.</li>
 *   <li>{@link #ACTIVE}: decide el modelo (desde el segundo frame sin ver el objeto) en lugar de la regla.</li>
 * </ul>
 */
public enum RemovalModelMode {
    OFF, SHADOW, ACTIVE;

    /** Lee el valor de {@code SAFEMODE_REMOVAL_MODEL}: vacio o null es {@link #OFF}; algo que no se entiende lanza un error claro. */
    public static RemovalModelMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> OFF;
            case "shadow" -> SHADOW;
            case "active" -> ACTIVE;
            default -> throw new IllegalArgumentException(
                    "SAFEMODE_REMOVAL_MODEL debe ser off, shadow o active, no \"" + value.trim() + "\"");
        };
    }
}
