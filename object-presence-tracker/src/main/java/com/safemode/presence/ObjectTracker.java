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

public class ObjectTracker {

    private static final Set<String> OBJECT_CLASSES = Set.of("backpack", "handbag", "suitcase");
    private static final String PERSON_CLASS = "person";

    private static final double MATCH_IOU_THRESHOLD = 0.3;
    private static final double MAX_FALLBACK_DISTANCE_PX = 350;
    private static final int MOVEMENT_TOLERANCE_PX = 25;
    private static final Duration REST_DURATION = Duration.ofSeconds(3);
    private static final int MAX_FRAMES_UNSEEN = 3;
    private static final double PERSON_PROXIMITY_PX = 80;

    private final Map<Long, TrackedObject> tracked = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final PresenceEventStore store;
    private final Clock clock;

    public ObjectTracker(PresenceEventStore store) {
        this(store, Clock.systemUTC());
    }

    ObjectTracker(PresenceEventStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void onFrame(List<Detection> detections) {
        Instant now = clock.instant();

        List<Detection> objectDetections = detections.stream()
                .filter(d -> OBJECT_CLASSES.contains(d.className()))
                .toList();
        List<Detection> personDetections = detections.stream()
                .filter(d -> PERSON_CLASS.equals(d.className()))
                .toList();

        System.out.println("[DEBUG] Frame: " + objectDetections.size() + " objeto(s), "
                + personDetections.size() + " persona(s) detectadas. Objetos rastreados actualmente: "
                + tracked.size());

        Set<Long> matchedIds = new HashSet<>();

        for (Detection det : objectDetections) {
            TrackedObject best = findBestMatch(det, matchedIds);

            if (best == null) {
                TrackedObject t = new TrackedObject(nextId.getAndIncrement(), det.className(),
                        det.x(), det.y(), det.width(), det.height(), now);
                tracked.put(t.getId(), t);
                matchedIds.add(t.getId());
                System.out.println("[DEBUG]   -> Nuevo objeto #" + t.getId() + " (" + det.className()
                        + ") en (" + det.x() + "," + det.y() + ")");
                continue;
            }

            matchedIds.add(best.getId());
            System.out.println("[DEBUG]   -> Match con objeto #" + best.getId()
                    + " (estado=" + best.getState() + ", pos anterior=(" + best.getX() + "," + best.getY() + "))"
                    + " nueva pos=(" + det.x() + "," + det.y() + ")");
            updatePosition(best, det, personDetections, now);
        }

        handleUnseenObjects(matchedIds, personDetections, now);
    }

    private void handleUnseenObjects(Set<Long> matchedIds, List<Detection> personDetections, Instant now) {
        for (TrackedObject t : new ArrayList<>(tracked.values())) {
            if (matchedIds.contains(t.getId())) {
                continue;
            }
            t.incrementFramesUnseen();
            System.out.println("[DEBUG]   -> Objeto #" + t.getId() + " no visto este frame ("
                    + t.getFramesUnseen() + "/" + MAX_FRAMES_UNSEEN + ")");
            if (t.getFramesUnseen() < MAX_FRAMES_UNSEEN) {
                continue;
            }
            if (t.getState() == TrackedObject.State.AT_REST) {
                markRemoved(t, personDetections, now);
            } else {
                System.out.println("[DEBUG]   -> Objeto #" + t.getId()
                        + " descartado SIN GUARDAR (nunca llegó a AT_REST, estado=" + t.getState() + ")");
            }
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
            System.out.println("[DEBUG]   -> Objeto #" + t.getId() + " se movió más de " + MOVEMENT_TOLERANCE_PX
                    + "px, se reinicia el contador de quietud (estado=" + t.getState() + ")");
            if (t.getState() == TrackedObject.State.AT_REST) {
                markRemoved(t, personDetections, now);
                tracked.remove(t.getId());
            } else {
                t.setRestSinceAt(now);
            }
            return;
        }

        Duration quietFor = Duration.between(t.getRestSinceAt(), now);
        System.out.println("[DEBUG]   -> Objeto #" + t.getId() + " quieto hace " + quietFor.toMillis() + "ms (necesita "
                + REST_DURATION.toMillis() + "ms)");

        if (t.getState() == TrackedObject.State.NEW && quietFor.compareTo(REST_DURATION) >= 0) {
            markAtRest(t, now);
        }
    }

    private void markAtRest(TrackedObject t, Instant now) {
        t.setState(TrackedObject.State.AT_REST);
        System.out.println("[DEBUG]   -> ¡Objeto #" + t.getId() + " marcado EN REPOSO! Guardando en BD...");
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