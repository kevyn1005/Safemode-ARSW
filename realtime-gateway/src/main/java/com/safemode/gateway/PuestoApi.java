package com.safemode.gateway;

/**
 * El servicio de colaboracion (configuracion compartida del puesto en MongoDB y presencia en Redis, ADR 0005) visto desde el
 * gateway. El navegador NO habla con ese servicio: pasa por el gateway, que pone la identidad del operador (sale de la
 * sesion de Google, nunca del navegador) y reenvia la respuesta tal cual.
 *
 * <p>Ningun metodo lanza excepciones: si el servicio no responde, devuelven una {@link Reply} 503 (se rechaza, no se
 * concede por falta de informacion). Los rechazos correctos (400, 403, 404, 409) se devuelven con su codigo: un 409 es
 * "otra edicion se guardo antes", no una falla.
 */
public interface PuestoApi {

    /** Estado HTTP y cuerpo JSON (ya serializado) que el gateway le devuelve al panel. */
    record Reply(int status, String body) {}

    /** La configuracion del puesto: titulo, preferencias, camaras con su zona y la version. */
    Reply puesto();

    /** Si el operador puede configurar el puesto, SOLO para pintar la pantalla (con cache): no autoriza escrituras. */
    Reply permission(String operator);

    /** Guarda un cambio ({@code expectedVersion} mas lo que cambia). La autoridad la consulta el servicio en cada escritura. */
    Reply save(String operator, String body);

    /** Latido de presencia del operador (vence a los 20 s). Responde 200 aunque Redis no este (degradada). */
    Reply touchPresence(String operator);

    /** Quien esta en linea ahora. */
    Reply presence();

    /**
     * Los cambios del puesto, del mas nuevo al mas viejo: hasta {@code limit} (1 a 50) de las versiones MENORES que {@code before}
     * ({@code null} = desde la ultima). La respuesta trae {@code nextBefore} para pedir la pagina siguiente (paginacion por cursor).
     */
    Reply history(Long before, int limit);
}
