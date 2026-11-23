package com.safemode.collab;

/** La fuente valida de permisos: pregunta a Gestion de acceso (PostgreSQL), nunca a una copia. */
@FunctionalInterface
public interface Authority {

    /**
     * @return si el operador puede configurar ese puesto AHORA
     * @throws AuthorityUnavailableException si Gestion no responde (timeout, conexion, 5xx)
     */
    boolean canConfigure(String operator, String puesto);
}
