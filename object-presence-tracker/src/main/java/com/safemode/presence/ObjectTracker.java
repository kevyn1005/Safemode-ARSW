package com.safemode.presence;

import com.safemode.vision.ObjectDetector.Detection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recibe, frame a frame, las detecciones de vision-detection-service y les
 * da continuidad (un id estable) para decidir cuando un objeto (mochila,
 * maleta, bolso) queda "en reposo" en la banca y cuando una persona lo
 * retira de la escena.
 *
 * No hace vision por computador: solo correlaciona cajas (bounding boxes)
 * entre frames consecutivos, primero por solapamiento (IoU, la misma idea
 * que ya usa ObjectDetector para su supresion de no-maximos) y, si eso no
 * encuentra nada, por cercania de centro. El segundo intento hace falta
 * porque vision-detection-service corre a pocos fps (ver
 * VisionDetectionTest: 2 fps): un objeto se puede desplazar bastante de un
 * frame al siguiente sin dejar de ser "el mismo", y con solo IoU esos
 * casos se perderian (se creaba un objeto nuevo en vez de reconocer que el
 * de antes se movio/lo retiraron).
 *
 * Cada transicion relevante (registrado en reposo / retirado) se guarda a
 * traves de {@link PresenceEventStore} con su timestamp exacto.
 */
public class ObjectTracker {

    /** Clases que SafeMode cuida (coincide con RELEVANT_CLASSES de ObjectDetector, sin "person"). */
    private static final Set<String> OBJECT_CLASSES = Set.of("backpack", "handbag", "suitcase");
    private static final String PERSON_CLASS = "person";

    /** Que tanto debe solaparse una caja con la del frame anterior para considerarse "el mismo objeto". */
    private static final double MATCH_IOU_THRESHOLD = 0.3;
    /** Si no hay solapamiento, que tan cerca deben estar los centros para seguir considerandolo el mismo objeto. */
    private static final double MAX_FALLBACK_DISTANCE_PX = 350;
    /** Ruido de deteccion que todavia cuenta como "quieto" (en pixeles). */
    private static final int MOVEMENT_TOLERANCE_PX = 25;
    /** Cuanto tiempo debe estar quieto un objeto nuevo para registrarlo en reposo. */
    private static final Duration REST_DURATION = Duration.ofSeconds(3);
    /** Frames seguidos sin verlo antes de asumir que ya no esta en la escena. */
    private static final int MAX_FRAMES_UNSEEN = 3;
    /** Que tan cerca debe estar una persona del objeto para anotar "persona cerca" en el retiro. */
    private static final double PERSON_PROXIMITY_PX = 80;

    private final Map<Long, TrackedObject> tracked = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final PresenceEventStore store;
    private final Clock clock;

    public ObjectTracker(PresenceEventStore store) {
        this(store, Clock.systemUTC());
    }

    /** Constructor para pruebas: permite inyectar un reloj controlado. */
    ObjectTracker(PresenceEventStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Procesa las detecciones de un frame. Se espera que se llame una vez
     * por cada frame que entrega vision-detection-service, en orden.
     */
    public void onFrame(List<Detection> detections) {
        Instant now = clock.instant();

        List<Detection> objectDetections = detections.stream()
                .filter(d -> OBJECT_CLASSES.contains(d.className()))
                .toList();
        List<Detection> personDetections = detections.stream()
                .filter(d -> PERSON_CLASS.equals(d.className()))
                .toList();

        Set<Long> matchedIds = new HashSet<>();

        for (Detection det : objectDetections) {
            TrackedObject best = findBestMatch(det, matchedIds);

            if (best == null) {
                TrackedObject t = new TrackedObject(nextId.getAndIncrement(), det.className(),
                        det.x(), det.y(), det.width(), det.height(), now);
                tracked.put(t.getId(), t);
                matchedIds.add(t.getId());
                continue;
            }

            matchedIds.add(best.getId());
            updatePosition(best, det, personDetections, now);
        }

        handleUnseenObjects(matchedIds, personDetections, now);
    }

    /** Objetos que este frame no volvio a detectar: cuentan frames perdidos y, si aplica, se marcan retirados. */
    private void handleUnseenObjects(Set<Long> matchedIds, List<Detection> personDetections, Instant now) {
        for (TrackedObject t : new ArrayList<>(tracked.values())) {
            if (matchedIds.contains(t.getId())) {
                continue;
            }
            t.incrementFramesUnseen();
            if (t.getFramesUnseen() < MAX_FRAMES_UNSEEN) {
                continue;
            }
            if (t.getState() == TrackedObject.State.AT_REST) {
                markRemoved(t, personDetections, now);
            }
            // si nunca llego a reposo y desaparecio, no era relevante: se descarta en silencio
            tracked.remove(t.getId());
        }
    }

    private TrackedObject findBestMatch(Detection det, Set<Long> alreadyMatched) {
        TrackedObject bestByIou = null;
        double bestIou = MATCH_IOU_THRESHOLD;
        for (TrackedObject t : tracked.values()) {
            if (alreadyMatched.contains(t.getId()) || !t.getClassName().equals(det.className())) {
                continue;
            }
            double iou = iou(t.getX(), t.getY(), t.getWidth(), t.getHeight(),
                    det.x(), det.y(), det.width(), det.height());
            if (iou > bestIou) {
                bestIou = iou;
                bestByIou = t;
            }
        }
        if (bestByIou != null) {
            return bestByIou;
        }

        // Sin solapamiento: segundo intento por cercania de centro (ver
        // el porque en el comentario de la clase).
        TrackedObject bestByDistance = null;
        double bestDistance = MAX_FALLBACK_DISTANCE_PX;
        for (TrackedObject t : tracked.values()) {
            if (alreadyMatched.contains(t.getId()) || !t.getClassName().equals(det.className())) {
                continue;
            }
            double distance = distanceCenters(det, t);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestByDistance = t;
            }
        }
        return bestByDistance;
    }

    private void updatePosition(TrackedObject t, Detection det, List<Detection> personDetections, Instant now) {
        boolean stillInPlace = Math.abs(det.x() - t.getX()) <= MOVEMENT_TOLERANCE_PX
                && Math.abs(det.y() - t.getY()) <= MOVEMENT_TOLERANCE_PX;

        t.setPosition(det.x(), det.y(), det.width(), det.height());
        t.resetFramesUnseen();

        if (!stillInPlace) {
            if (t.getState() == TrackedObject.State.AT_REST) {
                // se movio (poco o mucho) estando en reposo: se trata como un retiro
                markRemoved(t, personDetections, now);
                tracked.remove(t.getId());
            } else {
                t.setRestSinceAt(now); // reinicia el conteo de quietud
            }
            return;
        }

        if (t.getState() == TrackedObject.State.NEW
                && Duration.between(t.getRestSinceAt(), now).compareTo(REST_DURATION) >= 0) {
            markAtRest(t, now);
        }
    }

    private void markAtRest(TrackedObject t, Instant now) {
        t.setState(TrackedObject.State.AT_REST);
        store.recordRegistered(t.getId(), t.getClassName(), t.getX(), t.getY(), t.getWidth(), t.getHeight(), now);
    }

    private void markRemoved(TrackedObject t, List<Detection> personDetections, Instant now) {
        t.setState(TrackedObject.State.REMOVED);
        boolean personNearby = personDetections.stream()
                .anyMatch(p -> distanceCenters(p, t) <= PERSON_PROXIMITY_PX);
        store.recordRemoved(t.getId(), t.getClassName(), t.getX(), t.getY(), t.getWidth(), t.getHeight(), now, personNearby);
    }

    private double distanceCenters(Detection det, TrackedObject obj) {
        double px = det.x() + det.width() / 2.0;
        double py = det.y() + det.height() / 2.0;
        double ox = obj.getX() + obj.getWidth() / 2.0;
        double oy = obj.getY() + obj.getHeight() / 2.0;
        return Math.hypot(px - ox, py - oy);
    }

    private double iou(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        int x1 = Math.max(ax, bx);
        int y1 = Math.max(ay, by);
        int x2 = Math.min(ax + aw, bx + bw);
        int y2 = Math.min(ay + ah, by + bh);
        int inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        int union = aw * ah + bw * bh - inter;
        return union == 0 ? 0 : (double) inter / union;
    }
}
