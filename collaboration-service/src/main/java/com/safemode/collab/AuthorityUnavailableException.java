package com.safemode.collab;

/**
 * La autoridad de permisos (Gestion de acceso / PostgreSQL) no pudo responder. Para una escritura esto significa
 * 503 y NO guardar: no se convierte una falla en una concesion ("fail-closed").
 */
public class AuthorityUnavailableException extends RuntimeException {

    public AuthorityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
