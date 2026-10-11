package com.safemode.gateway;

/** Lo que el panel puede hacerle al tracker: ver si corre, iniciarlo y detenerlo. */
public interface TrackerControl {

    enum State {
        /** No hay ningun tracker lanzado desde el panel. */
        STOPPED,
        /** Corriendo. */
        RUNNING,
        /** Se pidio detenerlo y esta cerrando (espera las descripciones por IA pendientes). */
        STOPPING,
        /** Termino solo, con error (no lo detuvo el operador). */
        CRASHED
    }

    /** Estado actual; {@code exitCode} solo tiene sentido en {@link State#CRASHED} (si no, es -1). */
    record Status(State state, int exitCode) {
    }

    Status status();

    /** Lanza el tracker. {@link IllegalStateException} si ya hay uno corriendo o cerrandose. */
    void start() throws java.io.IOException;

    /** Pide detenerlo de forma ordenada y vuelve enseguida; no hace nada si no esta corriendo. */
    void requestStop();
}
