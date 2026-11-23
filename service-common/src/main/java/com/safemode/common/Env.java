package com.safemode.common;

/** Lectura de variables de entorno con valor por defecto (la configuracion de los servicios viaja por Compose). */
public final class Env {

    private Env() {}

    public static String get(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public static int getInt(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("La variable " + name + " debe ser un entero y es: " + value);
        }
    }
}
