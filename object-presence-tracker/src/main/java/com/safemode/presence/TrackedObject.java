package com.safemode.presence;

import java.time.Instant;

/**
 * Estado de seguimiento de un objeto (mochila/maleta/bolso) a traves de
 * varios frames. El {@link ObjectTracker} es el unico que lo crea y lo
 * modifica; hacia afuera solo se exponen lecturas.
 */
public class TrackedObject {

    public enum State {
        /** Visto por primera vez; todavia no lleva suficiente tiempo quieto. */
        NEW,
        /** Quieto el tiempo suficiente: ya se registro el evento de reposo. */
        AT_REST,
        /** Desaparecio (o se movio bruscamente) despues de estar en reposo. */
        REMOVED
    }

    private final long id;
    private final String className;

    private int x;
    private int y;
    private int width;
    private int height;

    private State state = State.NEW;
    private Instant restSinceAt;
    private int framesUnseen = 0;

    TrackedObject(long id, String className, int x, int y, int width, int height, Instant now) {
        this.id = id;
        this.className = className;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.restSinceAt = now;
    }

    public long getId() {
        return id;
    }

    public String getClassName() {
        return className;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public State getState() {
        return state;
    }

    Instant getRestSinceAt() {
        return restSinceAt;
    }

    int getFramesUnseen() {
        return framesUnseen;
    }

    void setPosition(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    void setState(State state) {
        this.state = state;
    }

    void setRestSinceAt(Instant restSinceAt) {
        this.restSinceAt = restSinceAt;
    }

    void resetFramesUnseen() {
        this.framesUnseen = 0;
    }

    void incrementFramesUnseen() {
        this.framesUnseen++;
    }
}
