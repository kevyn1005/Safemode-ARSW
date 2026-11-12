package com.safemode.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/** Validaciones de entrada que comparten los servicios: nada que llegue por HTTP se usa sin revisarlo antes. */
public final class Guards {

    /** Identificadores cortos (operadores, puestos): minusculas, digitos, guion y guion bajo. Nada de texto libre. */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    private Guards() {}

    /** Devuelve el identificador si es valido; {@link HttpError} 400 si no. */
    public static String identifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new HttpError(400, what + " invalido: use minusculas, digitos, '-' o '_' (maximo 40)");
        }
        return value;
    }

    /**
     * Las operaciones de administracion del escenario exigen la clave del laboratorio en {@code X-Lab-Key}. Se compara en
     * tiempo constante. Es una clave de practica, no autenticacion de produccion.
     */
    public static void requireAdminKey(MiniHttpServer.Request request, String expected) {
        String given = request.header("X-Lab-Key");
        boolean ok = given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throw new HttpError(403, "Clave de administracion invalida");
        }
    }
}
