package com.safemode.collab;

/**
 * Un almacen (hoy Redis) no respondio: conexion rechazada, tiempo agotado o nombre que ya no resuelve. Es una falla de
 * DEPENDENCIA, no un error de programacion: por eso los adaptadores traducen solo las excepciones del cliente a esta, y
 * los errores de codigo (NullPointerException, etc.) siguen siendo errores y no se esconden como "degradacion".
 */
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
