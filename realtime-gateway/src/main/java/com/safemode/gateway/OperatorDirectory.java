package com.safemode.gateway;

import java.util.Optional;

/**
 * Traduce el correo de la sesion de Google al identificador de operador que entienden los servicios de persistencia
 * ({@code ana}, {@code luis}...: minusculas, digitos, {@code -} o {@code _}). El correo ya paso la lista de autorizados
 * ({@code SAFEMODE_ALLOWED_EMAILS}); aqui solo se busca quien es en Gestion de acceso.
 */
@FunctionalInterface
public interface OperatorDirectory {

    /** El operador de ese correo, o vacio si no esta dado de alta. {@link IllegalStateException} si no se pudo consultar. */
    Optional<String> operatorFor(String email);
}
