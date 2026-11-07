package com.safemode.access;

/**
 * Lo que la autoridad responde sobre un operador en un puesto. {@code member=false} significa que no tiene membresia
 * (para escribir es lo mismo que no tener permiso).
 */
public record Permission(String operator, String puesto, boolean member, String role, boolean canConfigure) {}
