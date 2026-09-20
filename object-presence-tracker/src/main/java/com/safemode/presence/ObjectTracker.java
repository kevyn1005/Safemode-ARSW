package com.safemode.presence;

import ai.onnxruntime.OrtException;
import com.safemode.presence.PresenceEventStore.OwnerInfo;
import com.safemode.vision.ObjectDetector.Detection;
import com.safemode.vision.PoseEstimator;
import com.safemode.vision.PoseEstimator.Keypoint;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public class ObjectTracker {

    private static final Set<String> OBJECT_CLASSES = Set.of("backpack", "handbag", "suitcase");
    private static final String PERSON_CLASS = "person";

    private static final double MATCH_IOU_THRESHOLD = 0.3;
    private static final double MAX_FALLBACK_DISTANCE_PX = 350;
    private static final int MOVEMENT_TOLERANCE_PX = 25;
    private static final Duration REST_DURATION = Duration.ofSeconds(3);
    private static final int MAX_FRAMES_UNSEEN = 3;
    private static final double PERSON_PROXIMITY_PX = 80;

    // Dueno probable: distancia del centro del objeto al rectangulo de la persona (0 si esta dentro).
    private static final double OWNER_PROXIMITY_PX = 100;
    private static final double MAX_PERSON_FALLBACK_DISTANCE_PX = 150;
    private static final int MAX_PERSON_FRAMES_UNSEEN = 5;

    private final Map<Long, TrackedObject> tracked = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, TrackedPerson> trackedPersons = new LinkedHashMap<>();
    private final AtomicLong nextPersonId = new AtomicLong(1);
    private final PresenceEventStore store;
    private final Clock clock;
    private final Path frameStorageDir;
    private final Function<BufferedImage, Keypoint[]> poseFinder;
    private OwnerVisionDescriber visionDescriber;
    private Executor aiExecutor;
    private ExecutorService ownedAiPool;

    public ObjectTracker(PresenceEventStore store) {
        this(store, Clock.systemUTC(), Path.of("object-presence-tracker", "data", "frames"));
    }

    ObjectTracker(PresenceEventStore store, Clock clock) {
        this(store, clock, Path.of("object-presence-tracker", "data", "frames"));
    }

    /**
     * Igual que el constructor basico, pero con un modelo de pose que ubica el torso del dueno
     * para describir el color de su camisa con mas precision. Puede ser {@code null}.
     */
    public ObjectTracker(PresenceEventStore store, PoseEstimator poseEstimator) {
        this(store, Clock.systemUTC(), Path.of("object-presence-tracker", "data", "frames"), asPoseFinder(poseEstimator));
    }

    /** Constructor para pruebas: permite ademas elegir donde se guardan las imagenes (ej. un directorio temporal). */
    ObjectTracker(PresenceEventStore store, Clock clock, Path frameStorageDir) {
        this(store, clock, frameStorageDir, null);
    }

    /** Constructor para pruebas con un buscador de pose falso (no necesita el modelo .onnx). */
    ObjectTracker(PresenceEventStore store, Clock clock, Path frameStorageDir,
                  Function<BufferedImage, Keypoint[]> poseFinder) {
        this.store = store;
        this.clock = clock;
        this.frameStorageDir = frameStorageDir;
        this.poseFinder = poseFinder;
    }

    private static Function<BufferedImage, Keypoint[]> asPoseFinder(PoseEstimator poseEstimator) {
        if (poseEstimator == null) {
            return null;
        }
        return crop -> {
            try {
                return poseEstimator.estimate(crop);
            } catch (OrtException e) {
                System.err.println("No se pudo estimar la pose del dueno: " + e.getMessage());
                return null;
            }
        };
    }

    /**
     * Procesa las detecciones de un frame. El {@code frame} completo se
     * recibe para poder guardar una foto exacta del momento cuando un
     * objeto se registra en reposo o se retira (evidencia para el centro
     * de alertas). Puede ser {@code null} (por ejemplo en pruebas que no
     * usan camara real): en ese caso simplemente no se guarda imagen.
     */
    public void onFrame(BufferedImage frame, List<Detection> detections) {
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

        List<Long> personIds = matchPersons(personDetections);
        Set<Long> matchedIds = new HashSet<>();

        for (Detection det : objectDetections) {
            TrackedObject best = findBestMatch(det, matchedIds);

            if (best == null) {
                TrackedObject t = new TrackedObject(nextId.getAndIncrement(), det.className(),
                        det.x(), det.y(), det.width(), det.height(), now);
                tracked.put(t.getId(), t);
                matchedIds.add(t.getId());
                updateOwnerCandidate(t, det, personDetections, personIds, frame);
                System.out.println("[DEBUG]   -> Nuevo objeto #" + t.getId() + " (" + det.className()
                        + ") en (" + det.x() + "," + det.y() + ")");
                continue;
            }

            matchedIds.add(best.getId());
            System.out.println("[DEBUG]   -> Match con objeto #" + best.getId()
                    + " (estado=" + best.getState() + ", pos anterior=(" + best.getX() + "," + best.getY() + "))"
                    + " nueva pos=(" + det.x() + "," + det.y() + ")");
            if (best.getState() == TrackedObject.State.NEW) {
                updateOwnerCandidate(best, det, personDetections, personIds, frame);
            }
            updatePosition(best, det, personDetections, now, frame);
        }

        handleUnseenObjects(matchedIds, personDetections, now, frame);
    }

    private void handleUnseenObjects(Set<Long> matchedIds, List<Detection> personDetections, Instant now, BufferedImage frame) {
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
                markRemoved(t, personDetections, now, frame);
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

    private void updatePosition(TrackedObject t, Detection det, List<Detection> personDetections, Instant now, BufferedImage frame) {
        boolean stillInPlace = Math.abs(det.x() - t.getX()) <= MOVEMENT_TOLERANCE_PX
                && Math.abs(det.y() - t.getY()) <= MOVEMENT_TOLERANCE_PX;

        t.setPosition(det.x(), det.y(), det.width(), det.height());
        t.resetFramesUnseen();

        if (!stillInPlace) {
            System.out.println("[DEBUG]   -> Objeto #" + t.getId() + " se movió más de " + MOVEMENT_TOLERANCE_PX
                    + "px, se reinicia el contador de quietud (estado=" + t.getState() + ")");
            if (t.getState() == TrackedObject.State.AT_REST) {
                markRemoved(t, personDetections, now, frame);
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
            markAtRest(t, now, frame);
        }
    }

    private void markAtRest(TrackedObject t, Instant now, BufferedImage frame) {
        t.setState(TrackedObject.State.AT_REST);
        System.out.println("[DEBUG]   -> ¡Objeto #" + t.getId() + " marcado EN REPOSO! Guardando en BD...");
        String framePath = saveFrameSnapshot(frame, t.getId(), "REGISTERED_AT_REST", now);
        OwnerInfo owner = resolveOwner(t, now);
        t.setOwner(owner);
        long eventId = store.recordRegistered(t.getId(), t.getClassName(), t.getX(), t.getY(), t.getWidth(), t.getHeight(), now, framePath, owner);
        t.setRegisteredEventId(eventId);
        requestAiDescription(t, t.takeOwnerCrop(), t.takeOwnerPose());
    }

    private void markRemoved(TrackedObject t, List<Detection> personDetections, Instant now, BufferedImage frame) {
        t.setState(TrackedObject.State.REMOVED);
        // distancia del centro del objeto a la CAJA de la persona: una persona sentada o cargando el
        // objeto tiene una caja enorme y su centro queda lejos aunque este tocandolo
        double ox = t.getX() + t.getWidth() / 2.0;
        double oy = t.getY() + t.getHeight() / 2.0;
        boolean personNearby = personDetections.stream()
                .anyMatch(p -> distanceToBox(ox, oy, p) <= PERSON_PROXIMITY_PX);
        String framePath = saveFrameSnapshot(frame, t.getId(), "REMOVED", now);
        long eventId = store.recordRemoved(t.getId(), t.getClassName(), t.getX(), t.getY(), t.getWidth(), t.getHeight(), now, personNearby, framePath, t.getOwner());
        String aiDescription = t.registerRemovedEvent(eventId);
        if (aiDescription != null) {
            store.updateOwnerAiDescription(eventId, aiDescription);
        }
    }

    /** Activa (opcionalmente) la descripcion del dueno con un modelo de vision; devuelve este mismo tracker. */
    public ObjectTracker withOwnerVisionDescriber(OwnerVisionDescriber describer) {
        this.visionDescriber = describer;
        return this;
    }

    /** Para pruebas: ejecuta las descripciones con un Executor propio (ej. uno sincrono o controlado a mano). */
    void useAiExecutor(Executor executor) {
        this.aiExecutor = executor;
    }

    /** Espera a que terminen las descripciones pendientes; llamar antes de cerrar la base de datos. */
    public void awaitPendingDescriptions(Duration timeout) {
        if (ownedAiPool == null) {
            return;
        }
        ownedAiPool.shutdown();
        try {
            if (!ownedAiPool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                System.err.println("[IA] Quedaron descripciones sin terminar; se cancelan.");
                ownedAiPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized Executor aiExecutor() {
        if (aiExecutor == null) {
            // 2 hilos: una llamada lenta al servicio no debe retrasar la descripcion del siguiente dueno
            ownedAiPool = Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "owner-vision");
                thread.setDaemon(true);
                return thread;
            });
            aiExecutor = ownedAiPool;
        }
        return aiExecutor;
    }

    /** Si la descripcion ya trae un dato de tatuajes (lo que se ve, o "ninguno"); "no se ve" ya se omite al armarla. */
    static boolean hasTattooInfo(String description) {
        if (description == null) {
            return false;
        }
        for (String part : description.split("; ")) {
            if (part.startsWith("tatuajes:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pone el dato de tatuajes del recorte de antebrazos en la descripcion, reemplazando el que
     * hubiera dado la primera respuesta (con la persona entera el brazo es diminuto y suele decir
     * "no se ve"). Si no hay dato de tatuajes, deja la descripcion como estaba.
     */
    static String mergeTattoos(String description, String tattoos) {
        if (tattoos == null || tattoos.isBlank()) {
            return description;
        }
        List<String> parts = new ArrayList<>();
        if (description != null) {
            for (String part : description.split("; ")) {
                if (!part.startsWith("tatuajes:")) {
                    parts.add(part);
                }
            }
        }
        parts.add("tatuajes: " + tattoos.trim());
        return String.join("; ", parts);
    }

    /** Pide la descripcion del dueno en un hilo aparte: puede tardar segundos y no debe frenar los frames. */
    private void requestAiDescription(TrackedObject t, BufferedImage ownerCrop, Keypoint[] pose) {
        OwnerVisionDescriber describer = visionDescriber;
        if (describer == null || ownerCrop == null) {
            return;
        }
        aiExecutor().execute(() -> {
            try {
                String text = describer.describe(ownerCrop);
                // Segunda llamada solo si la primera respondio pero no dijo nada de tatuajes ("no se ve"):
                // con la persona entera el brazo es diminuto. Si ya hay dato (incluido "ninguno") no se gasta otra llamada.
                // Si la primera fallo del todo, el servicio probablemente no responde: tampoco se insiste.
                if (text != null && !hasTattooInfo(text) && pose != null) {
                    BufferedImage armsCrop = ArmsCropper.armsCrop(ownerCrop, pose);
                    if (armsCrop != null) {
                        System.out.println("[IA] Objeto #" + t.getId() + ": los tatuajes no quedaron claros; se revisan los antebrazos");
                        text = mergeTattoos(text, describer.describeArms(armsCrop));
                    }
                }
                if (text == null || text.isBlank()) {
                    return;
                }
                for (long eventId : t.attachAiDescription(text)) {
                    store.updateOwnerAiDescription(eventId, text);
                }
                System.out.println("[IA] Objeto #" + t.getId() + " - dueño: " + text);
            } catch (RuntimeException e) {
                System.err.println("[IA] No se pudo guardar la descripcion del dueno: " + e.getMessage());
            }
        });
    }

    /**
     * Asigna un id estable a cada persona detectada en este frame (IoU, con
     * respaldo por distancia entre centros). Devuelve una lista de ids alineada
     * por indice con {@code personDetections}. Las personas que no se ven se
     * descartan tras {@link #MAX_PERSON_FRAMES_UNSEEN} frames.
     */
    private List<Long> matchPersons(List<Detection> personDetections) {
        List<Long> ids = new ArrayList<>();
        Set<Long> matched = new HashSet<>();

        for (Detection det : personDetections) {
            TrackedPerson person = findBestPersonMatch(det, matched);
            if (person == null) {
                person = new TrackedPerson(nextPersonId.getAndIncrement(), det.x(), det.y(), det.width(), det.height());
                trackedPersons.put(person.getId(), person);
            } else {
                person.setPosition(det.x(), det.y(), det.width(), det.height());
            }
            person.resetFramesUnseen();
            matched.add(person.getId());
            ids.add(person.getId());
        }

        for (TrackedPerson p : new ArrayList<>(trackedPersons.values())) {
            if (matched.contains(p.getId())) {
                continue;
            }
            p.incrementFramesUnseen();
            if (p.getFramesUnseen() > MAX_PERSON_FRAMES_UNSEEN) {
                trackedPersons.remove(p.getId());
            }
        }
        return ids;
    }

    private TrackedPerson findBestPersonMatch(Detection det, Set<Long> alreadyMatched) {
        TrackedPerson bestByIou = null;
        double bestIou = MATCH_IOU_THRESHOLD;
        for (TrackedPerson p : trackedPersons.values()) {
            if (alreadyMatched.contains(p.getId())) {
                continue;
            }
            double iou = iou(p.getX(), p.getY(), p.getWidth(), p.getHeight(),
                    det.x(), det.y(), det.width(), det.height());
            if (iou > bestIou) {
                bestIou = iou;
                bestByIou = p;
            }
        }
        if (bestByIou != null) {
            return bestByIou;
        }

        TrackedPerson bestByDistance = null;
        double bestDistance = MAX_PERSON_FALLBACK_DISTANCE_PX;
        for (TrackedPerson p : trackedPersons.values()) {
            if (alreadyMatched.contains(p.getId())) {
                continue;
            }
            double distance = Math.hypot(
                    (det.x() + det.width() / 2.0) - (p.getX() + p.getWidth() / 2.0),
                    (det.y() + det.height() / 2.0) - (p.getY() + p.getHeight() / 2.0));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestByDistance = p;
            }
        }
        return bestByDistance;
    }

    /** Anota, en un objeto todavia NEW, la persona mas cercana (si hay alguna dentro del radio de dueno). */
    private void updateOwnerCandidate(TrackedObject t, Detection objDet, List<Detection> persons,
                                      List<Long> personIds, BufferedImage frame) {
        double ox = objDet.x() + objDet.width() / 2.0;
        double oy = objDet.y() + objDet.height() / 2.0;

        int nearestIdx = -1;
        double nearest = OWNER_PROXIMITY_PX;
        for (int i = 0; i < persons.size(); i++) {
            double d = distanceToBox(ox, oy, persons.get(i));
            if (d <= nearest) {
                nearest = d;
                nearestIdx = i;
            }
        }
        if (nearestIdx >= 0) {
            Detection person = persons.get(nearestIdx);
            // el recorte empieza en la esquina (x,y) de la persona, recortada al borde del frame
            Rectangle objectInCrop = new Rectangle(objDet.x() - Math.max(0, person.x()),
                    objDet.y() - Math.max(0, person.y()), objDet.width(), objDet.height());
            // foto de evidencia: persona y objeto juntos (el objeto no siempre cae dentro de la caja de la persona)
            int ux = Math.min(person.x(), objDet.x());
            int uy = Math.min(person.y(), objDet.y());
            int ux2 = Math.max(person.x() + person.width(), objDet.x() + objDet.width());
            int uy2 = Math.max(person.y() + person.height(), objDet.y() + objDet.height());
            t.recordNearPerson(personIds.get(nearestIdx), cropRegion(frame, person.x(), person.y(), person.width(), person.height()),
                    objectInCrop, cropRegion(frame, ux, uy, ux2 - ux, uy2 - uy));
        }
    }

    /** Distancia de un punto a un rectangulo (0 si el punto queda dentro). */
    private double distanceToBox(double px, double py, Detection box) {
        double dx = Math.max(Math.max(box.x() - px, 0), px - (box.x() + box.width()));
        double dy = Math.max(Math.max(box.y() - py, 0), py - (box.y() + box.height()));
        return Math.hypot(dx, dy);
    }

    /** Copia de una region del frame recortada a sus bordes (no retiene el frame completo en memoria), o null si no hay imagen. */
    private BufferedImage cropRegion(BufferedImage frame, int rx, int ry, int rw, int rh) {
        if (frame == null) {
            return null;
        }
        int x = Math.max(0, rx);
        int y = Math.max(0, ry);
        int w = Math.min(frame.getWidth(), rx + rw) - x;
        int h = Math.min(frame.getHeight(), ry + rh) - y;
        if (w <= 0 || h <= 0) {
            return null;
        }
        BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(frame.getSubimage(x, y, w, h), 0, 0, null);
        g.dispose();
        return copy;
    }

    /** Elige el dueno (la persona mas frecuente cerca del objeto), guarda su recorte y lo describe. */
    private OwnerInfo resolveOwner(TrackedObject t, Instant now) {
        Long ownerId = t.mostFrequentNearPersonId();
        if (ownerId == null) {
            return null;
        }
        BufferedImage crop = t.cropOf(ownerId);
        BufferedImage evidence = t.evidenceCropOf(ownerId);
        // la foto guardada muestra a la persona junto al objeto; el color y la pose se leen solo del recorte de la persona
        String cropPath = crop == null ? null
                : writePng(evidence != null ? evidence : crop,
                        "obj" + t.getId() + "_OWNER_person" + ownerId + "_" + now.toEpochMilli() + ".png");
        Keypoint[] pose = (crop == null || poseFinder == null) ? null : poseFinder.apply(crop);
        String description = PersonDescriber.describe(crop, t.objectInCropOf(ownerId), pose);
        t.setOwnerCrop(crop);
        t.setOwnerPose(pose);
        t.clearSightings();
        return new OwnerInfo(ownerId, description, cropPath);
    }

    /**
     * Guarda una copia PNG del frame en {@link #frameStorageDir}, nombrada con el id
     * del objeto, el tipo de evento y el instante exacto (para no pisar archivos
     * anteriores). Devuelve la ruta guardada, o {@code null} si no habia frame
     * disponible (pruebas sin camara) o si la escritura fallo.
     */
    private String saveFrameSnapshot(BufferedImage frame, long trackedId, String eventType, Instant now) {
        if (frame == null) {
            return null;
        }
        return writePng(frame, "obj" + trackedId + "_" + eventType + "_" + now.toEpochMilli() + ".png");
    }

    private String writePng(BufferedImage image, String filename) {
        try {
            Files.createDirectories(frameStorageDir);
            Path path = frameStorageDir.resolve(filename);
            ImageIO.write(image, "png", path.toFile());
            return path.toString();
        } catch (IOException e) {
            System.err.println("No se pudo guardar la imagen del evento: " + e.getMessage());
            return null;
        }
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
