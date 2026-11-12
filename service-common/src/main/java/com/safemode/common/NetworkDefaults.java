package com.safemode.common;

import java.security.Security;

/** Ajustes de red de la JVM que los servicios aplican al arrancar, antes de cualquier conexion. */
public final class NetworkDefaults {

    private NetworkDefaults() {}

    /**
     * Cache NEGATIVA de DNS de 1 s (por defecto la JVM guarda 10 s que un nombre "no existe"). Un contenedor detenido deja de
     * resolverse; al volver a iniciarse, con 10 s de cache la JVM seguiria diciendo "no existe" hasta 10 s despues de que
     * el servicio ya responde (medido: el primer guardado tras restaurar Gestion daba 503). Debe llamarse antes del primer
     * uso de la red.
     */
    public static void shortNegativeDnsCache() {
        Security.setProperty("networkaddress.cache.negative.ttl", "1");
    }
}
