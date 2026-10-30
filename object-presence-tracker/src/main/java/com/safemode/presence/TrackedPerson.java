package com.safemode.presence;

/**
 * Persona vista a traves de varios frames. Solo existe para darle un id
 * estable mientras la sesion este corriendo (no es reconocimiento facial ni
 * reidentificacion: si sale de escena mucho rato, al volver es una persona nueva).
 */
class TrackedPerson {

    private final long id;
    private int x;
    private int y;
    private int width;
    private int height;
    private int framesUnseen = 0;
    private PersonAppearance appearance;

    TrackedPerson(long id, int x, int y, int width, int height) {
        this.id = id;
        setPosition(x, y, width, height);
    }

    long getId() {
        return id;
    }

    int getX() {
        return x;
    }

    int getY() {
        return y;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
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

    /** Como se vio a esta persona por ultima vez (null si no hay imagen). */
    PersonAppearance getAppearance() {
        return appearance;
    }

    void setAppearance(PersonAppearance appearance) {
        this.appearance = appearance;
    }

    void resetFramesUnseen() {
        this.framesUnseen = 0;
    }

    void incrementFramesUnseen() {
        this.framesUnseen++;
    }
}
