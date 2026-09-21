package com.safemode.presence;

import ai.onnxruntime.OrtException;
import com.safemode.presence.PresenceEventStore.OwnerInfo;
import com.safemode.presence.PresenceEventStore.RemovalInfo;
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
    // Quien esta "junto" al objeto en reposo (para atribuir un retiro): distancia del centro del objeto a la caja de la persona.
    private static final double REMOVAL_PROXIMITY_PX = 100;
    // Con la camara pegada al objeto todo se ve enorme (una mochila de 500 px): "cerca" crece con el tamano del objeto.
    // Los radios de arriba son el minimo; para objetos pequenos no cambia nada.
    private static final double PROXIMITY_PER_OBJECT_SIZE = 0.4;
    // Si el detector deja de ver un objeto en reposo pero su zona se ve casi igual que antes (menos de esta fraccion de
    // puntos cambio), se asume que sigue ahi tapado o mal detectado. Calibrado con fotos reales: retiro falso 0.04,
    // retiro real (alguien lo tapa y lo levanta) 0.28. Tope de frames de espera para no dejar un retiro real sin declarar.
    private static final double STILL_THERE_MAX_CHANGE = 0.15;
    private static final int MAX_VERIFIED_UNSEEN_FRAMES = 12;
    // Un objeto en reposo que aparece desplazado mas de MOVEMENT_TOLERANCE_PX solo se da por retirado si sigue desplazado
    // en este numero de frames seguidos: una persona que pasa por delante lo tapa y la caja del detector salta de sitio.
    private static final int REMOVAL_CONFIRM_FRAMES = 2;
    private static final double MAX_PERSON_FALLBACK_DISTANCE_PX = 150;
    // fraccion del tamano de la caja (ancho o alto, el mayor) que una persona puede desplazarse entre frames
    private static final double PERSON_MOVE_PER_SIZE = 0.6;
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
    // opcional: comparar la imagen de la zona antes de dar por retirado un objeto que el detector dejo de ver
    private boolean verifyRemovalByImage;
    // persona -> dato de tatuajes ya obtenido de sus antebrazos en esta corrida (ver tattoosFromArms)
    private final Map<Long, String> tattooCheckByPerson = new java.util.concurrent.ConcurrentHashMap<>();
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

        List<Long> personIds = matchPersons(personDetections, frame);
        Set<Long> matchedIds = new HashSet<>();

        // Paso 1: cada deteccion con un objeto de su misma clase.
        TrackedObject[] assigned = new TrackedObject[objectDetections.size()];
        for (int i = 0; i < objectDetections.size(); i++) {
            assigned[i] = findBestMatch(objectDetections.get(i), matchedIds);
            if (assigned[i] != null) {
                matchedIds.add(assigned[i].getId());
            }
        }
        // Paso 2: las que quedaron sin objeto pueden ser un objeto que el detector cambio de clase entre frames
        // (la misma maleta sale a veces como backpack y a veces como handbag). Va despues del paso 1 para no
        // robarle el objeto a otra deteccion de su misma clase.
        for (int i = 0; i < objectDetections.size(); i++) {
            if (assigned[i] == null) {
                assigned[i] = findClassChangeMatch(objectDetections.get(i), matchedIds);
                if (assigned[i] != null) {
                    matchedIds.add(assigned[i].getId());
                }
            }
        }

        for (int i = 0; i < objectDetections.size(); i++) {
            Detection det = objectDetections.get(i);
            TrackedObject best = assigned[i];

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
                    + " nueva pos=(" + det.x() + "," + det.y() + ")"
                    + (best.getClassName().equals(det.className()) ? ""
                            : " [el detector cambio de clase: " + best.getClassName() + " -> " + det.className() + "]"));
            if (best.getState() == TrackedObject.State.NEW) {
                updateOwnerCandidate(best, det, personDetections, personIds, frame);
            }
            updatePosition(best, det, personDetections, personIds, now, frame);
        }

        handleUnseenObjects(matchedIds, personDetections, personIds, now, frame);
    }

    private void handleUnseenObjects(Set<Long> matchedIds, List<Detection> personDetections, List<Long> personIds,
                                     Instant now, BufferedImage frame) {
        for (TrackedObject t : new ArrayList<>(tracked.values())) {
            if (matchedIds.contains(t.getId())) {
                continue;
            }
            t.incrementFramesUnseen();
            System.out.println("[DEBUG]   -> Objeto #" + t.getId() + " no visto este frame ("
                    + t.getFramesUnseen() + "/" + MAX_FRAMES_UNSEEN + ")");
            if (t.getState() == TrackedObject.State.AT_REST) {
                // quien se acerca al sitio del objeto mientras desaparece: cuando se declare el retiro quiza ya se alejo con el
                t.addDisappearanceNear(collectNearPersons(t.getX(), t.getY(), t.getWidth(), t.getHeight(),
                        personDetections, personIds, frame));
            }
            if (t.getFramesUnseen() < MAX_FRAMES_UNSEEN) {
                continue;
            }
            if (t.getState() == TrackedObject.State.AT_REST && stillLooksInPlace(t, frame)) {
                continue;
            }
            if (t.getState() == TrackedObject.State.AT_REST) {
                markRemoved(t, personDetections, personIds, now, frame);
            } else {
                System.out.println("[DEBUG]   -> Objeto #" + t.getId()
                        + " descartado SIN GUARDAR (nunca llegó a AT_REST, estado=" + t.getState() + ")");
            }
            tracked.remove(t.getId());
        }
    }

    /**
     * El detector no ve el objeto, pero la zona donde estaba en reposo se ve casi igual que la ultima vez que se lo vio:
     * alguien lo tapa un momento o el detector fallo. Solo se espera un numero limitado de frames; sin imagen no se puede
     * comprobar y se decide como siempre.
     */
    private boolean stillLooksInPlace(TrackedObject t, BufferedImage frame) {
        if (t.getFramesUnseen() >= MAX_FRAMES_UNSEEN + MAX_VERIFIED_UNSEEN_FRAMES) {
            return false;
        }
        if (!verifyRemovalByImage) {
            return false;
        }
        RegionFingerprint atRest = t.getRestFingerprint();
        RegionFingerprint now = RegionFingerprint.of(frame, t.getX(), t.getY(), t.getWidth(), t.getHeight());
        if (atRest == null || now == null) {
            return false;
        }
        double change = atRest.changedFraction(now);
        boolean stillThere = change < STILL_THERE_MAX_CHANGE;
        System.out.println("[DEBUG]   -> Objeto #" + t.getId() + " sin verse " + t.getFramesUnseen() + " frames; su zona cambio "
                + Math.round(change * 100) + "%: " + (stillThere ? "sigue ahi (tapado o mal detectado), no se da por retirado"
                        : "ya no esta, se da por retirado"));
        return stillThere;
    }

    /** Objeto sin emparejar de OTRA clase que se solapa con la deteccion (el detector le cambio la clase), o null. */
    private TrackedObject findClassChangeMatch(Detection det, Set<Long> alreadyMatched) {
        TrackedObject best = null;
        double bestIou = MATCH_IOU_THRESHOLD;
        for (TrackedObject t : tracked.values()) {
            if (alreadyMatched.contains(t.getId()) || t.getClassName().equals(det.className())) {
                continue;
            }
            double iou = iou(t.getX(), t.getY(), t.getWidth(), t.getHeight(),
                    det.x(), det.y(), det.width(), det.height());
            if (iou > bestIou) {
                bestIou = iou;
                best = t;
            }
        }
        return best;
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

    private void updatePosition(TrackedObject t, Detection det, List<Detection> personDetections, List<Long> personIds,
                                Instant now, BufferedImage frame) {
        boolean stillInPlace = Math.abs(det.x() - t.getX()) <= MOVEMENT_TOLERANCE_PX
                && Math.abs(det.y() - t.getY()) <= MOVEMENT_TOLERANCE_PX;

        t.resetFramesUnseen();

        if (t.getState() == TrackedObject.State.AT_REST) {
            if (stillInPlace) {
                t.resetDisplacedFrames();
            } else {
                int displaced = t.incrementDisplacedFrames();
                if (displaced < REMOVAL_CONFIRM_FRAMES) {
                    // no se actualiza la posicion: sigue valiendo la de reposo, a la que puede volver en el siguiente frame
                    System.out.println("[DEBUG]   -> Objeto #" + t.getId() + " aparece desplazado mas de " + MOVEMENT_TOLERANCE_PX
                            + "px estando en reposo (" + displaced + "/" + REMOVAL_CONFIRM_FRAMES
                            + "): se espera confirmacion, puede ser que alguien lo tape");
                    return;
                }
            }
        }

        t.setPosition(det.x(), det.y(), det.width(), det.height());

        if (!stillInPlace) {
            System.out.println("[DEBUG]   -> Objeto #" + t.getId() + " se movió más de " + MOVEMENT_TOLERANCE_PX
                    + "px, se reinicia el contador de quietud (estado=" + t.getState() + ")");
            if (t.getState() == TrackedObject.State.AT_REST) {
                markRemoved(t, personDetections, personIds, now, frame);
                tracked.remove(t.getId());
            } else {
                t.setRestSinceAt(now);
            }
            return;
        }

        if (t.getState() == TrackedObject.State.AT_REST) {
            // quien esta junto al objeto ahora: si desaparece o se lo llevan, es a quien se le atribuye el retiro
            t.setLastNearPersons(collectNearPersons(t.getX(), t.getY(), t.getWidth(), t.getHeight(), personDetections, personIds, frame));
            if (verifyRemovalByImage) {
                t.setRestFingerprint(RegionFingerprint.of(frame, t.getX(), t.getY(), t.getWidth(), t.getHeight()));
            }
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
        if (verifyRemovalByImage) {
            t.setRestFingerprint(RegionFingerprint.of(frame, t.getX(), t.getY(), t.getWidth(), t.getHeight()));
        }
        System.out.println("[DEBUG]   -> ¡Objeto #" + t.getId() + " marcado EN REPOSO! Guardando en BD...");
        String framePath = saveFrameSnapshot(frame, t.getId(), "REGISTERED_AT_REST", now);
        OwnerInfo owner = resolveOwner(t, now);
        t.setOwner(owner);
        long eventId = store.recordRegistered(t.getId(), t.getClassName(), t.getX(), t.getY(), t.getWidth(), t.getHeight(), now, framePath, owner);
        t.setRegisteredEventId(eventId);
        requestAiDescription(t, t.takeOwnerCrop(), t.takeOwnerPose(), owner == null ? null : owner.personId());
    }

    private void markRemoved(TrackedObject t, List<Detection> personDetections, List<Long> personIds, Instant now, BufferedImage frame) {
        t.setState(TrackedObject.State.REMOVED);
        // distancia del centro del objeto a la CAJA de la persona: una persona sentada o cargando el
        // objeto tiene una caja enorme y su centro queda lejos aunque este tocandolo
        double ox = t.getX() + t.getWidth() / 2.0;
        double oy = t.getY() + t.getHeight() / 2.0;
        double personRadius = scaledProximity(PERSON_PROXIMITY_PX, t.getWidth(), t.getHeight());
        boolean personNearby = personDetections.stream()
                .anyMatch(p -> distanceToBox(ox, oy, p) <= personRadius);
        String framePath = saveFrameSnapshot(frame, t.getId(), "REMOVED", now);
        RemovalAnalysis analysis = analyzeRemoval(t, personDetections, personIds, frame, now);
        long eventId = store.recordRemoved(t.getId(), t.getClassName(), t.getX(), t.getY(), t.getWidth(), t.getHeight(), now,
                personNearby, framePath, t.getOwner(), analysis.info());
        String aiDescription = t.registerRemovedEvent(eventId);
        if (aiDescription != null) {
            store.updateOwnerAiDescription(eventId, aiDescription);
        }
        // Solo en retiros sospechosos con una persona a quien atribuirselo: pocas llamadas y donde mas ayuda al vigilante
        if (analysis.kind().worthDescribingRemover() && analysis.remover() != null) {
            requestRemoverAiDescription(t, eventId, analysis.remover().crop, analysis.pose(), analysis.remover().personId);
        }
    }

    /** Resultado del analisis de un retiro: lo que se guarda, mas lo que hace falta para describir a quien lo retiro. */
    private record RemovalAnalysis(RemovalInfo info, RemovalKind kind, TrackedObject.NearPerson remover, Keypoint[] pose) {}

    /**
     * Personas junto al objeto (dentro de {@link #REMOVAL_PROXIMITY_PX} o del radio escalado), con su recorte y la foto que las muestra
     * junto al objeto. La clave es el id de persona; el mas cercano se puede elegir por {@code distance}.
     */
    private Map<Long, TrackedObject.NearPerson> collectNearPersons(int ox, int oy, int ow, int oh, List<Detection> persons,
                                                                   List<Long> personIds, BufferedImage frame) {
        double cx = ox + ow / 2.0;
        double cy = oy + oh / 2.0;
        Map<Long, TrackedObject.NearPerson> near = new LinkedHashMap<>();
        double radius = scaledProximity(REMOVAL_PROXIMITY_PX, ow, oh);
        for (int i = 0; i < persons.size(); i++) {
            Detection person = persons.get(i);
            double distance = distanceToBox(cx, cy, person);
            if (distance > radius) {
                continue;
            }
            Rectangle objectInCrop = new Rectangle(ox - Math.max(0, person.x()), oy - Math.max(0, person.y()), ow, oh);
            int ux = Math.min(person.x(), ox);
            int uy = Math.min(person.y(), oy);
            int ux2 = Math.max(person.x() + person.width(), ox + ow);
            int uy2 = Math.max(person.y() + person.height(), oy + oh);
            near.put(personIds.get(i), new TrackedObject.NearPerson(personIds.get(i), distance,
                    cropRegion(frame, person.x(), person.y(), person.width(), person.height()),
                    cropRegion(frame, ux, uy, ux2 - ux, uy2 - uy), objectInCrop));
        }
        return near;
    }

    /**
     * Como se retiro el objeto y a quien atribuirselo. Se consideran las personas junto al objeto en el ultimo frame en
     * que se vio quieto y tambien las que estan junto a el ahora (quien se lo lleva suele aparecer justo al levantarlo,
     * y para cuando el objeto desaparece quiza ya se alejo).
     */
    private RemovalAnalysis analyzeRemoval(TrackedObject t, List<Detection> personDetections, List<Long> personIds,
                                           BufferedImage frame, Instant now) {
        Map<Long, TrackedObject.NearPerson> near = new LinkedHashMap<>(t.getLastNearPersons());
        near.putAll(t.getDisappearanceNear());
        near.putAll(collectNearPersons(t.getX(), t.getY(), t.getWidth(), t.getHeight(), personDetections, personIds, frame));

        Long ownerId = t.getOwner() == null ? null : t.getOwner().personId();
        RemovalKind kind = classifyRemoval(ownerId, near.keySet());
        TrackedObject.NearPerson remover = pickRemover(kind, ownerId, near);
        if (remover == null) {
            return new RemovalAnalysis(new RemovalInfo(kind.name(), null, null, null), kind, null, null);
        }

        String cropPath = remover.crop == null ? null
                : writePng(remover.evidenceCrop != null ? remover.evidenceCrop : remover.crop,
                        "obj" + t.getId() + "_REMOVER_person" + remover.personId + "_" + now.toEpochMilli() + ".png");
        Keypoint[] pose = (remover.crop == null || poseFinder == null) ? null : poseFinder.apply(remover.crop);
        String description = PersonDescriber.describe(remover.crop, remover.objectInCrop, pose);
        return new RemovalAnalysis(new RemovalInfo(kind.name(), remover.personId, description, cropPath), kind, remover, pose);
    }

    /** Tipo de retiro segun el dueno del objeto (puede ser null) y las personas que estaban junto a el. */
    static RemovalKind classifyRemoval(Long ownerId, java.util.Collection<Long> nearIds) {
        if (nearIds.isEmpty()) {
            return RemovalKind.NO_ONE_NEAR;
        }
        if (ownerId == null) {
            return RemovalKind.OWNER_UNKNOWN;
        }
        if (nearIds.contains(ownerId)) {
            return nearIds.size() == 1 ? RemovalKind.BY_OWNER : RemovalKind.OWNER_AND_OTHER_NEAR;
        }
        return RemovalKind.BY_OTHER;
    }

    /** A quien se le atribuye el retiro: el dueno si actuo solo; si no, la persona (distinta del dueno) mas cercana. */
    private static TrackedObject.NearPerson pickRemover(RemovalKind kind, Long ownerId, Map<Long, TrackedObject.NearPerson> near) {
        if (kind == RemovalKind.NO_ONE_NEAR) {
            return null;
        }
        if (kind == RemovalKind.BY_OWNER) {
            return near.get(ownerId);
        }
        TrackedObject.NearPerson best = null;
        for (TrackedObject.NearPerson candidate : near.values()) {
            if (ownerId != null && candidate.personId == ownerId) {
                continue;
            }
            if (best == null || candidate.distance < best.distance) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Activa (opcionalmente) la verificacion por imagen del retiro: si el detector deja de ver un objeto en reposo pero
     * su zona se ve casi igual, se espera en vez de declararlo retirado (una persona que se cruza lo tapa). Necesita que
     * onFrame reciba el frame real; con frames null no hace nada. Devuelve este mismo tracker.
     */
    public ObjectTracker withRemovalVerification() {
        this.verifyRemovalByImage = true;
        return this;
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

    /**
     * Si la descripcion afirma que HAY tatuajes. Un "ninguno" no cuenta como confirmacion (con la persona
     * entera el brazo es diminuto y un "ninguno" ya fallo con tatuajes visibles), ni tampoco que falte el dato
     * ("no se ve" se omite al armar la frase): en ambos casos conviene revisar los antebrazos.
     */
    static boolean reportsTattoos(String description) {
        if (description == null) {
            return false;
        }
        for (String part : description.split("; ")) {
            if (part.startsWith("tatuajes:")) {
                String value = part.substring("tatuajes:".length()).trim().toLowerCase(java.util.Locale.ROOT);
                return !(value.isEmpty() || value.equals("ninguno") || value.equals("ninguna")
                        || value.equals("no hay") || value.equals("sin tatuajes"));
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

    /**
     * Dato de tatuajes sacado del recorte de antebrazos, o null si no se pudo saber. El resultado se recuerda por
     * persona durante la corrida: los otros objetos de la misma persona reutilizan la respuesta en vez de gastar
     * otra llamada. Solo se recuerdan respuestas con dato; un fallo o un "no se ve" se puede reintentar despues.
     * Limite: el id de persona es del seguimiento de esta corrida; si dos personas muy juntas se intercambian el id,
     * se reutilizaria el dato de la otra.
     */
    private String tattoosFromArms(OwnerVisionDescriber describer, TrackedObject t, BufferedImage ownerCrop,
                                   Keypoint[] pose, Long ownerId) {
        String remembered = ownerId == null ? null : tattooCheckByPerson.get(ownerId);
        if (remembered != null) {
            System.out.println("[IA] Objeto #" + t.getId() + ": los antebrazos de la persona #" + ownerId
                    + " ya se revisaron; se reutiliza el resultado");
            return remembered;
        }
        BufferedImage armsCrop = ArmsCropper.armsCrop(ownerCrop, pose);
        if (armsCrop == null) {
            return null;
        }
        System.out.println("[IA] Objeto #" + t.getId() + ": los tatuajes no quedaron confirmados; se revisan los antebrazos");
        String result = describer.describeArms(armsCrop);
        if (result != null && ownerId != null) {
            tattooCheckByPerson.put(ownerId, result);
        }
        return result;
    }

    /**
     * Descripcion por IA de una persona a partir de su recorte. Segunda llamada (solo antebrazos) si la primera
     * respondio pero no confirmo tatuajes: falto el dato o dijo "ninguno". Si la primera fallo del todo, el servicio
     * probablemente no responde: no se insiste. Devuelve null si no se pudo describir.
     */
    private String describePerson(OwnerVisionDescriber describer, TrackedObject t, BufferedImage crop, Keypoint[] pose,
                                  Long personId) {
        String text = describer.describe(crop);
        if (text != null && pose != null && !reportsTattoos(text)) {
            text = mergeTattoos(text, tattoosFromArms(describer, t, crop, pose, personId));
        }
        return text;
    }

    /** Descripcion por IA de quien se llevo el objeto, en segundo plano; se guarda en la fila REMOVED. */
    private void requestRemoverAiDescription(TrackedObject t, long eventId, BufferedImage crop, Keypoint[] pose, Long personId) {
        OwnerVisionDescriber describer = visionDescriber;
        if (describer == null || crop == null) {
            return;
        }
        aiExecutor().execute(() -> {
            try {
                String text = describePerson(describer, t, crop, pose, personId);
                if (text == null || text.isBlank()) {
                    return;
                }
                store.updateRemoverAiDescription(eventId, text);
                System.out.println("[IA] Objeto #" + t.getId() + " - quien lo retiro (persona #" + personId + "): " + text);
            } catch (RuntimeException e) {
                System.err.println("[IA] No se pudo guardar la descripcion de quien retiro el objeto: " + e.getMessage());
            }
        });
    }

    /** Pide la descripcion del dueno en un hilo aparte: puede tardar segundos y no debe frenar los frames. */
    private void requestAiDescription(TrackedObject t, BufferedImage ownerCrop, Keypoint[] pose, Long ownerId) {
        OwnerVisionDescriber describer = visionDescriber;
        if (describer == null || ownerCrop == null) {
            return;
        }
        aiExecutor().execute(() -> {
            try {
                String text = describePerson(describer, t, ownerCrop, pose, ownerId);
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
     * descartan tras {@link #MAX_PERSON_FRAMES_UNSEEN} frames. Si hay imagen, una persona nueva solo hereda el id de
     * alguien que salio de cuadro cuando la ropa de las dos se parece (ver {@link PersonAppearance}): si no, quien entra
     * justo despues de quien se fue quedaba con su id, y un robo se leia como retiro del dueno.
     */
    private List<Long> matchPersons(List<Detection> personDetections, BufferedImage frame) {
        List<Long> ids = new ArrayList<>();
        Set<Long> matched = new HashSet<>();

        for (Detection det : personDetections) {
            PersonAppearance appearance = PersonAppearance.of(frame, det.x(), det.y(), det.width(), det.height());
            TrackedPerson person = findBestPersonMatch(det, matched, appearance);
            if (person == null) {
                person = new TrackedPerson(nextPersonId.getAndIncrement(), det.x(), det.y(), det.width(), det.height());
                trackedPersons.put(person.getId(), person);
            } else {
                person.setPosition(det.x(), det.y(), det.width(), det.height());
            }
            if (appearance != null) {
                person.setAppearance(appearance);
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

    /**
     * Puede la persona detectada ser esta persona rastreada? Con solape entre frames consecutivos basta la posicion; si la
     * persona rastreada no se veia en el frame anterior (salio de cuadro), o el emparejamiento es solo por cercania (evidencia
     * debil), tambien tiene que parecerse su ropa. Sin imagen no se puede comparar y se decide como siempre.
     */
    private static boolean sameLooking(TrackedPerson tracked, PersonAppearance seen, boolean weakEvidence) {
        if (seen == null || tracked.getAppearance() == null) {
            return true;
        }
        if (!weakEvidence && tracked.getFramesUnseen() == 0) {
            return true;
        }
        return seen.looksLike(tracked.getAppearance());
    }

    private TrackedPerson findBestPersonMatch(Detection det, Set<Long> alreadyMatched, PersonAppearance appearance) {
        TrackedPerson bestByIou = null;
        double bestIou = MATCH_IOU_THRESHOLD;
        for (TrackedPerson p : trackedPersons.values()) {
            if (alreadyMatched.contains(p.getId()) || !sameLooking(p, appearance, false)) {
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

        // Cuanto puede moverse una persona entre dos frames depende de su tamano en la imagen: una que pasa pegada a la
        // camara (caja enorme) recorre cientos de pixeles, y con un limite fijo el seguimiento le cambiaba el id.
        TrackedPerson bestByDistance = null;
        double bestRatio = 1.0; // distancia / limite de esa persona; solo cuentan las de ratio < 1
        for (TrackedPerson p : trackedPersons.values()) {
            if (alreadyMatched.contains(p.getId()) || !sameLooking(p, appearance, true)) {
                continue;
            }
            double distance = Math.hypot(
                    (det.x() + det.width() / 2.0) - (p.getX() + p.getWidth() / 2.0),
                    (det.y() + det.height() / 2.0) - (p.getY() + p.getHeight() / 2.0));
            double limit = Math.max(MAX_PERSON_FALLBACK_DISTANCE_PX, PERSON_MOVE_PER_SIZE * Math.max(p.getWidth(), p.getHeight()));
            double ratio = distance / limit;
            if (ratio < bestRatio) {
                bestRatio = ratio;
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
        double nearest = scaledProximity(OWNER_PROXIMITY_PX, objDet.width(), objDet.height());
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

    /** Radio de "cerca" para un objeto de ese tamano: el minimo dado, o una fraccion de su lado mayor si eso es mas. */
    static double scaledProximity(double minimumPx, int objectWidth, int objectHeight) {
        return Math.max(minimumPx, PROXIMITY_PER_OBJECT_SIZE * Math.max(objectWidth, objectHeight));
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
