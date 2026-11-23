package com.safemode.access;

/**
 * El operador de un correo y su membresia en un puesto, tal como quedaron en PostgreSQL. {@code created} es verdadero
 * solo si esta llamada dio de alta al operador (una segunda llamada con el mismo correo devuelve lo mismo con {@code false}).
 */
public record Provisioned(String id, String email, String displayName, String puesto, String role, boolean canConfigure,
                          boolean created) {}
