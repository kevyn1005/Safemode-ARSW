package com.safemode.common;

/**
 * Una respuesta de error con su codigo HTTP. Los servicios la lanzan desde un manejador y {@link MiniHttpServer} la
 * convierte en {@code {"error": "..."}}. Un 403 o un 409 NO es una falla del servidor: es una respuesta correcta que
 * rechaza una operacion (y por eso las metricas los separan de los 5xx).
 */
public final class HttpError extends RuntimeException {

    private final int status;

    public HttpError(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
