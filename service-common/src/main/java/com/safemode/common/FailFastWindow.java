package com.safemode.common;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ventana de "fallo rapido" para una dependencia: tras una falla, durante un rato corto NO se vuelve a intentar la
 * conexion y se falla al instante; pasada la ventana se prueba de nuevo.
 *
 * <p>Por que hace falta: con un contenedor detenido, cada intento de conexion espera la resolucion del nombre (hasta ~2 s)
 * y ningun timeout de Redis o HTTP lo acota. Sin ventana, CADA peticion paga esa espera mientras la dependencia esta caida;
 * con ella, solo una por ventana. Es una proteccion para quien llama: no cambia la garantia, porque una escritura que no
 * puede verificar el permiso sigue rechazandose (503) y la presencia sigue degradandose.
 *
 * <p>Costo: tras recuperarse la dependencia pueden pasar hasta {@code window} para que se vuelva a usar.
 */
public final class FailFastWindow {

    private static final long NONE = Long.MIN_VALUE;

    private final long windowNanos;
    private final AtomicLong failedAt = new AtomicLong(NONE);

    public FailFastWindow(Duration window) {
        this.windowNanos = window.toNanos();
    }

    /** {@code true} si hubo una falla hace menos de la ventana: no se intenta, se falla al instante. */
    public boolean isOpen() {
        long at = failedAt.get();
        return at != NONE && System.nanoTime() - at < windowNanos;
    }

    public void recordFailure() {
        failedAt.set(System.nanoTime());
    }

    public void recordSuccess() {
        failedAt.set(NONE);
    }
}
