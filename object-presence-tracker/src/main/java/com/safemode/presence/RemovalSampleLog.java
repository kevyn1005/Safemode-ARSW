package com.safemode.presence;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Registro OPCIONAL de las muestras con las que se entrena el modelo de verificacion de retiro ("sigue ahi" o "fue retirado").
 * Para cada frame en que el detector no ve un objeto en reposo anota las senales de la zona ({@link RegionFingerprint.RemovalSignals})
 * y lo que decidio la regla actual. No cambia ninguna decision del {@link ObjectTracker}.
 *
 * <p>Garantias: nunca lanza una excepcion hacia el seguimiento (si algo falla, se desactiva y avisa una sola vez) y escribe en
 * archivos CSV UTF-8 en modo <i>append</i>, vaciados en cada fila, dentro de {@code object-presence-tracker/data/removal-dataset/}
 * (carpeta ignorada por git; nadie la borra).
 *
 * <p>Archivos: {@code samples.csv} (una fila por frame sin ver), {@code events.csv} (una fila por evento al cerrarse),
 * {@code discarded.csv} (retiros por desplazamiento, que no entran al dataset) y {@code crops/} (recortes chicos de la zona, para
 * revisar los errores a mano).
 *
 * <p>Un <b>evento</b> es una racha de frames sin ver el mismo objeto en reposo. Termina de una de tres formas
 * ({@code outcome_rule}): {@code SEEN_AGAIN} (el detector volvio a verlo), {@code REMOVED} (la regla lo dio por retirado) o
 * {@code DROPPED_CAP} (la regla dejo de seguirlo tras el tope de frames, sin alerta). Cuando la regla decide, el seguimiento
 * deja de ver ese objeto, pero este registro sigue anotando la <b>cola</b> (filas {@code TAIL}) hasta el tope de
 * {@link ObjectTracker#UNSEEN_CAP_FRAMES} frames o hasta que algo vuelva a verse en esa zona: asi se puede saber que habria hecho
 * otro criterio mas adelante sin haber cambiado lo que el sistema hizo de verdad.
 */
public final class RemovalSampleLog implements AutoCloseable {

    static final Path DEFAULT_DIR = Path.of("object-presence-tracker", "data", "removal-dataset");
    static final int CROP_MAX_SIDE = 320;

    static final String SAMPLES_HEADER = "scene,session,run_label,scenario,camera_id,event_id,object_class,frames_unseen,"
            + "x1_changed,x2_visible,x3_changed_soft,x4_mean_abs_diff,x5_frame_changed,box_w,box_h,persons_in_frame,rule_action,"
            + "model_p,model_action,ts";
    static final String EVENTS_HEADER = "scene,session,run_label,label,label_source,scenario,camera_id,event_id,object_id,"
            + "object_class,outcome_rule,frames_unseen_at_end,max_frames_unseen,tail_end,ts_start,ts_end";
    static final String DISCARDED_HEADER = "scene,session,run_label,scenario,camera_id,object_id,reason,ts";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,40}");
    private static final Set<String> LABELS = Set.of("removed", "still");
    // un objeto que vuelve a verse "en la misma zona": mismo umbral de solape que el emparejamiento del seguimiento
    private static final double RESEEN_MIN_IOU = 0.3;
    // si cambia tanto el RESTO del cuadro (sin la zona del objeto ni las personas), lo mas probable es que se movio o tapo una
    // ventana (captura de pantalla) o cambio la luz de toda la escena, no que se llevaran el objeto: se avisa por consola
    static final double FRAME_CHANGE_WARNING = 0.6;

    /**
     * Lo que el seguimiento le cuenta al registro de un frame en que no ve un objeto en reposo. {@code restFrame} es la huella del
     * cuadro completo en el ultimo frame en que se vio el objeto quieto (puede ser null).
     */
    record UnseenFrame(int cameraId, long objectId, String className, int x, int y, int width, int height,
                       int framesUnseen, RegionFingerprint rest, RegionFingerprint restFrame, BufferedImage frame,
                       List<Rectangle> personBoxes, String action, String outcome, Instant now) {}

    private static final class OpenEvent {
        final String id;
        final int cameraId;
        final long objectId;
        final String objectClass;
        final Instant startedAt;
        int x;
        int y;
        int width;
        int height;
        RegionFingerprint rest;
        RegionFingerprint restFrame;
        // el mayor cambio del resto del cuadro visto en esta racha (-1 = no se pudo medir)
        double maxFrameChanged = -1;
        // con un clasificador: estado de la racha para las senales de secuencia y si el modelo ya "dio el retiro" en algun frame
        final RemovalFeatures.Run modelRun = new RemovalFeatures.Run();
        boolean modelFired;
        int lastK;
        long lastSeq = -1;
        Instant lastTs;
        boolean decided;
        String outcome;
        int ruleK;
        BufferedImage lastCrop;

        OpenEvent(String id, int cameraId, long objectId, String objectClass, Instant startedAt) {
            this.id = id;
            this.cameraId = cameraId;
            this.objectId = objectId;
            this.objectClass = objectClass;
            this.startedAt = startedAt;
        }
    }

    private final Path dir;
    private final String scene;
    private final String session;
    private final String runLabel;
    private final String scenario;
    private final Writer samples;
    private final Writer events;
    private final Writer discarded;

    private final Map<String, OpenEvent> open = new LinkedHashMap<>();
    private final Map<String, Integer> runsStarted = new HashMap<>();
    private BufferedImage previousFrame;
    private BufferedImage currentFrame;
    private long seq;
    private boolean disabled;
    private boolean closed;
    private boolean warnedNoSignals;
    // opcional: con un modelo, cada fila (tambien las de la cola) lleva su probabilidad y lo que habria decidido (model_p, model_action)
    private RemovalClassifier classifier;

    /** Constructor para pruebas: con escritores propios (por ejemplo uno que falla a proposito). No escribe encabezados. */
    RemovalSampleLog(Path dir, String scene, String session, String runLabel, String scenario,
                     Writer samples, Writer events, Writer discarded) {
        this.dir = dir;
        this.scene = scene;
        this.session = session;
        this.runLabel = runLabel;
        this.scenario = clean(scenario);
        this.samples = samples;
        this.events = events;
        this.discarded = discarded;
    }

    /**
     * Abre el registro con la configuracion de las variables de entorno, o devuelve {@code null} si no esta activado
     * ({@code SAFEMODE_REMOVAL_LOG} distinto de 1: el comportamiento de siempre). Con el registro activado exige
     * {@code SAFEMODE_SCENE} y {@code SAFEMODE_LABEL} ({@code removed} o {@code still}) y lanza
     * {@link IllegalArgumentException} con un mensaje claro si falta algo o la sesion ya existe: mejor fallar antes de empezar
     * una captura que perderla. {@code SAFEMODE_SESSION} (por defecto la fecha y hora) y {@code SAFEMODE_SCENARIO} son opcionales.
     */
    public static RemovalSampleLog fromEnvironment() {
        RemovalSampleLog log = fromEnvironment(System.getenv(), DEFAULT_DIR);
        if (log != null) {
            // si se corta con Ctrl+C, los eventos que quedaban abiertos igual se anotan
            Runtime.getRuntime().addShutdownHook(new Thread(log::close, "removal-sample-log-close"));
        }
        return log;
    }

    static RemovalSampleLog fromEnvironment(Map<String, String> env, Path dir) {
        if (!"1".equals(trimmed(env.get("SAFEMODE_REMOVAL_LOG")))) {
            return null;
        }
        String scene = validId(env.get("SAFEMODE_SCENE"), "SAFEMODE_SCENE", "A, B o C");
        String label = trimmed(env.get("SAFEMODE_LABEL")).toLowerCase(Locale.ROOT);
        if (!LABELS.contains(label)) {
            throw new IllegalArgumentException("SAFEMODE_LABEL debe ser removed o still (la verdad de todos los eventos de la corrida), no \""
                    + trimmed(env.get("SAFEMODE_LABEL")) + "\"");
        }
        String session = trimmed(env.get("SAFEMODE_SESSION"));
        if (session.isEmpty()) {
            session = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        }
        return open(dir, scene, session, label, env.get("SAFEMODE_SCENARIO"));
    }

    static RemovalSampleLog open(Path dir, String scene, String session, String label, String scenario) {
        String validScene = validId(scene, "SAFEMODE_SCENE", "A, B o C");
        String validSession = validId(session, "SAFEMODE_SESSION", "A-still-persona-01");
        if (!LABELS.contains(label)) {
            throw new IllegalArgumentException("La etiqueta debe ser removed o still, no \"" + label + "\"");
        }
        Writer samples = null;
        Writer events = null;
        Writer discarded = null;
        try {
            Files.createDirectories(dir.resolve("crops"));
            if (sessionAlreadyLogged(dir, validSession)) {
                throw new IllegalArgumentException("La sesion \"" + validSession + "\" ya tiene datos en " + dir
                        + ": usa otro SAFEMODE_SESSION (si no, los eventos se mezclarian)");
            }
            samples = openAppend(dir.resolve("samples.csv"), SAMPLES_HEADER);
            events = openAppend(dir.resolve("events.csv"), EVENTS_HEADER);
            discarded = openAppend(dir.resolve("discarded.csv"), DISCARDED_HEADER);
            return new RemovalSampleLog(dir, validScene, validSession, label, scenario, samples, events, discarded);
        } catch (IOException e) {
            closeQuietly(samples);
            closeQuietly(events);
            closeQuietly(discarded);
            throw new IllegalStateException("No se pudo abrir el registro de muestras en " + dir + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            closeQuietly(samples);
            closeQuietly(events);
            closeQuietly(discarded);
            throw e;
        }
    }

    /**
     * Anota ademas, en cada fila con senales, la probabilidad del modelo ({@code model_p}) y lo que habria decidido
     * ({@code model_action}: WAIT mientras no llega al umbral, REMOVE en el primer frame que lo supera, AFTER despues). Es solo
     * lectura: no cambia ninguna decision. Devuelve este mismo registro.
     */
    public RemovalSampleLog withClassifier(RemovalClassifier classifier) {
        this.classifier = classifier;
        return this;
    }

    /** Descripcion corta para el arranque ("escena=A, sesion=..., etiqueta=still, carpeta=..."). */
    String describe() {
        return "escena=" + scene + ", sesion=" + session + ", etiqueta=" + runLabel
                + (scenario.isEmpty() ? "" : ", escenario=" + scenario) + ", carpeta=" + dir;
    }

    boolean isDisabled() {
        return disabled;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ganchos que llama el ObjectTracker. Ninguno lanza excepciones: si algo falla se desactiva el registro.
    // ---------------------------------------------------------------------------------------------------------------

    /** Primer paso de cada onFrame: guarda el frame (y el anterior, que sirve para el recorte "en reposo"). */
    synchronized void frameStarted(BufferedImage frame) {
        if (inactive()) {
            return;
        }
        previousFrame = currentFrame;
        currentFrame = frame;
        seq++;
    }

    /** Un frame sin ver un objeto en reposo; {@code outcome} es null mientras la regla sigue esperando. */
    synchronized void unseenFrame(UnseenFrame u) {
        if (inactive()) {
            return;
        }
        try {
            recordUnseenFrame(u);
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    /** El detector volvio a ver un objeto: si tenia una racha abierta (sin decision de la regla), termina en SEEN_AGAIN. */
    synchronized void seenAgain(int cameraId, long objectId, Instant now) {
        if (inactive()) {
            return;
        }
        try {
            OpenEvent e = open.get(key(cameraId, objectId));
            if (e == null || e.decided) {
                return;
            }
            e.outcome = "SEEN_AGAIN";
            System.out.println("[MUESTRAS] " + e.id + ": el detector volvio a verlo tras " + e.lastK + " frame(s) (etiqueta "
                    + labelFor("SEEN_AGAIN") + ")" + wholeFrameWarning(e));
            closeEvent(e, "", now);
        } catch (IOException | RuntimeException ex) {
            fail(ex);
        }
    }

    /** Un objeto en reposo se dio por retirado por desplazamiento (no entra al dataset): solo se cuenta aparte. */
    synchronized void displacedRemoval(int cameraId, long objectId, Instant now) {
        if (inactive()) {
            return;
        }
        try {
            append(discarded, String.join(",", scene, session, runLabel, csv(scenario), Integer.toString(cameraId),
                    Long.toString(objectId), "DISPLACED_REMOVAL", now.toString()));
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    /**
     * Ultimo paso de cada onFrame: anota la cola (frames siguientes a la decision de la regla) de los eventos ya decididos,
     * o la cierra si algo vuelve a verse en esa zona o se llega al tope.
     */
    synchronized void frameFinished(Instant now, BufferedImage frame, List<Rectangle> personBoxes, List<Rectangle> objectBoxes) {
        if (inactive()) {
            return;
        }
        try {
            for (OpenEvent e : new ArrayList<>(open.values())) {
                if (!e.decided || e.lastSeq >= seq) {
                    continue;
                }
                Rectangle zone = new Rectangle(e.x, e.y, e.width, e.height);
                if (objectBoxes.stream().anyMatch(b -> iou(b, zone) > RESEEN_MIN_IOU)) {
                    closeEvent(e, "RESEEN", now);
                    continue;
                }
                int k = e.lastK + 1;
                writeRow(e, k, frame, personBoxes, "TAIL", now);
                if (k >= ObjectTracker.UNSEEN_CAP_FRAMES) {
                    closeEvent(e, "CAP", now);
                }
            }
        } catch (IOException | RuntimeException ex) {
            fail(ex);
        }
    }

    /** Cierra el registro: los eventos que seguian abiertos se anotan (sin decision: OPEN_AT_END; con cola: RUN_END). */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (!disabled) {
            try {
                for (OpenEvent e : new ArrayList<>(open.values())) {
                    closeEvent(e, e.decided ? "RUN_END" : "", e.lastTs == null ? Instant.now() : e.lastTs);
                }
            } catch (IOException | RuntimeException ex) {
                fail(ex);
            }
        }
        closed = true;
        closeQuietly(samples);
        closeQuietly(events);
        closeQuietly(discarded);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private boolean inactive() {
        return disabled || closed;
    }

    private void recordUnseenFrame(UnseenFrame u) throws IOException {
        String key = key(u.cameraId(), u.objectId());
        OpenEvent e = open.get(key);
        if (e == null) {
            int run = runsStarted.merge(key, 1, Integer::sum);
            e = new OpenEvent(session + "_c" + u.cameraId() + "_o" + u.objectId() + "_r" + run, u.cameraId(), u.objectId(),
                    u.className(), u.now());
            open.put(key, e);
        } else if (e.decided) {
            return;
        }
        e.x = u.x();
        e.y = u.y();
        e.width = u.width();
        e.height = u.height();
        e.rest = u.rest();
        e.restFrame = u.restFrame();
        int k = u.framesUnseen();
        if (k == 1 && previousFrame != null) {
            // el frame anterior es el ultimo en que se vio el objeto quieto: asi se veia "en reposo"
            writeCrop(e.id + "_rest", cropScaled(previousFrame, e.x, e.y, e.width, e.height));
        }
        writeRow(e, k, u.frame(), u.personBoxes(), u.action(), u.now());
        if (u.outcome() != null) {
            e.decided = true;
            e.outcome = u.outcome();
            e.ruleK = k;
            System.out.println("[MUESTRAS] " + e.id + ": la regla dio " + u.outcome() + " tras " + k + " frame(s) sin verse (etiqueta "
                    + labelFor(u.outcome()) + ")" + wholeFrameWarning(e));
            if (k >= ObjectTracker.UNSEEN_CAP_FRAMES) {
                closeEvent(e, "CAP", u.now());
            }
        }
    }

    private void writeRow(OpenEvent e, int k, BufferedImage frame, List<Rectangle> personBoxes, String action, Instant ts)
            throws IOException {
        RegionFingerprint.RemovalSignals signals = null;
        RegionFingerprint now = RegionFingerprint.of(frame, e.x, e.y, e.width, e.height);
        if (e.rest != null && now != null) {
            signals = e.rest.signals(now, personBoxes);
        } else if (frame != null && e.rest == null && !warnedNoSignals) {
            warnedNoSignals = true;
            System.out.println("[MUESTRAS] Aviso: el objeto no tiene huella de reposo (falta tracker.withRemovalVerification()); "
                    + "las filas salen sin senales.");
        }
        double frameChanged = frameChanged(e.restFrame, frame, new Rectangle(e.x, e.y, e.width, e.height), personBoxes);
        e.maxFrameChanged = Math.max(e.maxFrameChanged, frameChanged);
        String modelP = "";
        String modelAction = "";
        if (classifier != null && signals != null) {
            double p = classifier.probabilityFromCatalog(e.modelRun.next(signals, frameChanged, k));
            modelP = number(p);
            if (k >= RemovalClassifier.DECIDES_FROM_FRAME && !e.modelFired && p >= classifier.threshold()) {
                e.modelFired = true;
                modelAction = "REMOVE";
            } else {
                modelAction = e.modelFired ? "AFTER" : "WAIT";
            }
        }
        String[] cells = {
                scene, session, runLabel, csv(scenario), Integer.toString(e.cameraId), e.id, e.objectClass, Integer.toString(k),
                signals == null ? "" : number(signals.changedFraction()), signals == null ? "" : number(signals.visibleFraction()),
                signals == null ? "" : number(signals.changedSoft()), signals == null ? "" : number(signals.meanAbsDiff()),
                frameChanged < 0 ? "" : number(frameChanged),
                Integer.toString(e.width), Integer.toString(e.height), Integer.toString(personBoxes.size()), action,
                modelP, modelAction, ts.toString()};
        append(samples, String.join(",", cells));
        e.lastK = k;
        e.lastSeq = seq;
        e.lastTs = ts;
        BufferedImage crop = cropScaled(frame, e.x, e.y, e.width, e.height);
        e.lastCrop = crop;
        if (k == 2 || k == 3) {
            writeCrop(e.id + "_" + k, crop);
        }
    }

    /**
     * Cuanto cambio el RESTO del cuadro (cuadricula gruesa de todo el frame, sin los puntos de la zona del objeto ni de las personas)
     * respecto al ultimo frame en que se vio el objeto quieto, de 0 a 1; -1 si no se puede medir (sin imagen, sin huella o todo el
     * cuadro tapado). Un retiro deja esto en ~0; una ventana que se mueve o un cambio de luz lo suben.
     */
    static double frameChanged(RegionFingerprint restFrame, BufferedImage frame, Rectangle zone, List<Rectangle> personBoxes) {
        if (restFrame == null || frame == null) {
            return -1;
        }
        RegionFingerprint now = RegionFingerprint.of(frame, 0, 0, frame.getWidth(), frame.getHeight());
        if (now == null) {
            return -1;
        }
        List<Rectangle> covered = new ArrayList<>(personBoxes);
        covered.add(zone);
        RegionFingerprint.Comparison comparison = restFrame.compare(now, covered);
        return comparison.visibleFraction() == 0 ? -1 : comparison.changedFraction();
    }

    /** Aviso para la consola cuando cambio casi todo el cuadro durante el evento (probable ventana movida, no un retiro). */
    private static String wholeFrameWarning(OpenEvent e) {
        return e.maxFrameChanged >= FRAME_CHANGE_WARNING
                ? " | AVISO: cambio TODO el cuadro (x5=" + number(e.maxFrameChanged) + "): se movio o tapo alguna ventana? revisa el recorte, "
                + "puede ser dato malo"
                : "";
    }

    private void closeEvent(OpenEvent e, String tailEnd, Instant endTs) throws IOException {
        open.remove(key(e.cameraId, e.objectId));
        String outcome = e.outcome == null ? "OPEN_AT_END" : e.outcome;
        int framesAtEnd = e.decided ? e.ruleK : e.lastK;
        String[] cells = {
                scene, session, runLabel, labelFor(outcome), labelSourceFor(outcome), csv(scenario), Integer.toString(e.cameraId),
                e.id, Long.toString(e.objectId), e.objectClass, outcome, Integer.toString(framesAtEnd), Integer.toString(e.lastK),
                tailEnd, e.startedAt.toString(), endTs.toString()};
        append(events, String.join(",", cells));
        writeCrop(e.id + "_last", e.lastCrop);
    }

    /**
     * Etiqueta final del evento. Es la de la corrida, salvo en una corrida "removed": un evento que termino con el detector
     * viendo el objeto otra vez (SEEN_AGAIN) demuestra que el objeto seguia ahi, asi que cuenta como "still".
     */
    private String labelFor(String outcome) {
        return "removed".equals(runLabel) && "SEEN_AGAIN".equals(outcome) ? "still" : runLabel;
    }

    private String labelSourceFor(String outcome) {
        return "removed".equals(runLabel) && "SEEN_AGAIN".equals(outcome) ? "auto_seen_again" : "run";
    }

    private void writeCrop(String name, BufferedImage crop) throws IOException {
        if (crop == null) {
            return;
        }
        Path cropsDir = dir.resolve("crops");
        Files.createDirectories(cropsDir);
        ImageIO.write(crop, "png", cropsDir.resolve(name + ".png").toFile());
    }

    /** Recorte de la zona con el lado mayor a lo sumo {@link #CROP_MAX_SIDE}; siempre una imagen nueva (no retiene el frame). */
    static BufferedImage cropScaled(BufferedImage frame, int x, int y, int width, int height) {
        if (frame == null) {
            return null;
        }
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(frame.getWidth(), x + width);
        int y1 = Math.min(frame.getHeight(), y + height);
        if (x1 - x0 < 2 || y1 - y0 < 2) {
            return null;
        }
        double scale = Math.min(1.0, (double) CROP_MAX_SIDE / Math.max(x1 - x0, y1 - y0));
        int targetWidth = Math.max(1, (int) Math.round((x1 - x0) * scale));
        int targetHeight = Math.max(1, (int) Math.round((y1 - y0) * scale));
        BufferedImage out = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(frame, 0, 0, targetWidth, targetHeight, x0, y0, x1, y1, null);
        g.dispose();
        return out;
    }

    private static double iou(Rectangle a, Rectangle b) {
        Rectangle overlap = a.intersection(b);
        if (overlap.isEmpty()) {
            return 0;
        }
        double inter = (double) overlap.width * overlap.height;
        double union = (double) a.width * a.height + (double) b.width * b.height - inter;
        return union <= 0 ? 0 : inter / union;
    }

    private void append(Writer writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private void fail(Exception cause) {
        if (!disabled) {
            disabled = true;
            System.err.println("[MUESTRAS] El registro de muestras se desactivo por un error (el seguimiento sigue normal): " + cause);
        }
        closeQuietly(samples);
        closeQuietly(events);
        closeQuietly(discarded);
    }

    private static String key(int cameraId, long objectId) {
        return cameraId + ":" + objectId;
    }

    // seis decimales alcanzan: las fracciones son enteros/576 como mucho y la regla compara con 0.15, 0.35 y 0.6
    private static String number(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    /** Un valor de texto libre para un CSV: entre comillas (con las comillas internas duplicadas) si hace falta. */
    static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) : oneLine;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String validId(String value, String name, String example) {
        String trimmed = trimmed(value);
        if (!ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(name + " es obligatoria con el registro activado: letras, numeros, punto, guion o guion bajo "
                    + "(hasta 40), por ejemplo " + example + (trimmed.isEmpty() ? "" : "; recibi \"" + trimmed + "\""));
        }
        return trimmed;
    }

    private static Writer openAppend(Path file, String header) throws IOException {
        boolean needsHeader = !Files.exists(file) || Files.size(file) == 0;
        Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (needsHeader) {
            writer.write(header);
            writer.write('\n');
            writer.flush();
        }
        return writer;
    }

    /** La sesion ya tiene filas en alguno de los archivos (la segunda columna es la sesion, sin comillas por la validacion). */
    private static boolean sessionAlreadyLogged(Path dir, String session) throws IOException {
        for (String name : List.of("samples.csv", "events.csv", "discarded.csv")) {
            Path file = dir.resolve(name);
            if (!Files.exists(file)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line = reader.readLine(); // encabezado
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split(",", 3);
                    if (fields.length >= 2 && fields[1].equals(session)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void closeQuietly(Writer writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // ya se aviso del error original; cerrar es lo ultimo que se intenta
        }
    }
}
