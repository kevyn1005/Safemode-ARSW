package com.safemode.collab;

import java.util.List;
import java.util.Optional;

/**
 * Datos temporales con vencimiento (hoy en Redis): presencia de operadores y cache de permisos. Todas las operaciones
 * lanzan {@link StoreUnavailableException} si el almacen no responde.
 */
public interface PresenceStore {

    /** Valor y vencimiento en UNA sola escritura ({@code SET clave valor EX segundos}): no puede quedar una clave eterna. */
    void setWithTtl(String key, String value, int ttlSeconds);

    /** Escribe sin vencimiento. Solo lo usa la version base del laboratorio, para mostrar el defecto. */
    void set(String key, String value);

    Optional<String> get(String key);

    /** Claves que cumplen el patron ({@code SCAN}, no {@code KEYS}: no bloquea al servidor). */
    List<String> keys(String pattern);

    void deleteByPattern(String pattern);

    boolean ping();
}
