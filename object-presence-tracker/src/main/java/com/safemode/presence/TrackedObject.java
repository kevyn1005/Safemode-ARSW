package com.safemode.presence;

import com.safemode.presence.PresenceEventStore.OwnerInfo;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private static final class Sighting {
        int frames;
        BufferedImage crop;
        Rectangle objectInCrop;
        BufferedImage evidenceCrop;
    }

    private final Map<Long, Sighting> nearPersons = new LinkedHashMap<>();
    private OwnerInfo owner;
    private BufferedImage ownerCrop;
    private long registeredEventId = -1;
    private long removedEventId = -1;
    private String aiDescription;

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

    /** Anota que esta persona estuvo cerca del objeto en este frame (mientras el objeto es NEW). */
    void recordNearPerson(long personId, BufferedImage crop, Rectangle objectInCrop, BufferedImage evidenceCrop) {
        Sighting s = nearPersons.computeIfAbsent(personId, k -> new Sighting());
        s.frames++;
        if (crop != null) {
            s.crop = crop;
            s.objectInCrop = objectInCrop;
            s.evidenceCrop = evidenceCrop;
        }
    }

    /** Recorte que abarca a la persona y al objeto juntos (foto de evidencia), o null. */
    BufferedImage evidenceCropOf(long personId) {
        Sighting s = nearPersons.get(personId);
        return s == null ? null : s.evidenceCrop;
    }

    /** Id de la persona que mas frames estuvo cerca del objeto, o null si nadie lo estuvo. */
    Long mostFrequentNearPersonId() {
        Long best = null;
        int bestFrames = 0;
        for (Map.Entry<Long, Sighting> e : nearPersons.entrySet()) {
            if (e.getValue().frames > bestFrames) {
                bestFrames = e.getValue().frames;
                best = e.getKey();
            }
        }
        return best;
    }

    BufferedImage cropOf(long personId) {
        Sighting s = nearPersons.get(personId);
        return s == null ? null : s.crop;
    }

    /** Zona que ocupaba este objeto dentro del recorte de la persona (para no confundir el objeto con su ropa). */
    Rectangle objectInCropOf(long personId) {
        Sighting s = nearPersons.get(personId);
        return s == null ? null : s.objectInCrop;
    }

    void clearSightings() {
        nearPersons.clear();
    }

    /** Recorte del dueno, guardado solo hasta enviarlo a describir (para no retener imagenes). */
    void setOwnerCrop(BufferedImage crop) {
        this.ownerCrop = crop;
    }

    BufferedImage takeOwnerCrop() {
        BufferedImage crop = ownerCrop;
        ownerCrop = null;
        return crop;
    }

    synchronized void setRegisteredEventId(long eventId) {
        this.registeredEventId = eventId;
    }

    /**
     * Anota el id de la fila de retiro y devuelve la descripcion por IA si ya habia llegado (para
     * copiarla a esa fila), o null. Junto con {@link #attachAiDescription} garantiza que, llegue
     * la descripcion antes o despues del retiro, las dos filas la reciban.
     */
    synchronized String registerRemovedEvent(long eventId) {
        this.removedEventId = eventId;
        return aiDescription;
    }

    /** Guarda la descripcion por IA y devuelve los ids de las filas ya insertadas que hay que actualizar. */
    synchronized long[] attachAiDescription(String text) {
        this.aiDescription = text;
        return removedEventId >= 0
                ? new long[]{registeredEventId, removedEventId}
                : new long[]{registeredEventId};
    }

    OwnerInfo getOwner() {
        return owner;
    }

    void setOwner(OwnerInfo owner) {
        this.owner = owner;
    }
}
