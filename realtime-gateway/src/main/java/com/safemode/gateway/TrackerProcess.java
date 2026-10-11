package com.safemode.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Lanza y detiene el tracker como proceso hijo del gateway. El comando es FIJO: lo decide el codigo del servidor y nada
 * de lo que mande el navegador entra en el. Salida y errores del tracker van a un archivo de registro (si no, el pipe
 * se llenaria y el hijo se bloquearia).
 * <p>
 * Parada ordenada: el hijo recibe "stop" por su entrada estandar (ver {@code ObjectPresenceTrackerTest}); esa misma
 * entrada se cierra sola si el gateway muere, asi el tracker nunca queda huerfano. Si tarda mas de {@code stopTimeout}
 * se mata al proceso y a todos sus descendientes (con "mvn" el proceso real es un nieto).
 */
public class TrackerProcess implements TrackerControl {

    private final List<String> command;
    private final Path workDir;
    private final Map<String, String> extraEnv;
    private final Path logFile;
    private final Duration stopTimeout;

    private Process process;          // protegido por "this"
    private boolean stopRequested;    // idem: distingue "lo detuve yo" de "se cayo"

    public TrackerProcess(List<String> command, Path workDir, Map<String, String> extraEnv, Path logFile,
                          Duration stopTimeout) {
        this.command = List.copyOf(command);
        this.workDir = workDir;
        this.extraEnv = Map.copyOf(extraEnv);
        this.logFile = logFile;
        this.stopTimeout = stopTimeout;
    }

    /** El comando de siempre: {@code mvn -pl object-presence-tracker exec:java ...} (en Windows, mvn.cmd), sin limite de tiempo. */
    public static List<String> mavenCommand() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return List.of(windows ? "mvn.cmd" : "mvn", "-pl", "object-presence-tracker", "exec:java",
                "-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest", "-Dexec.args=0");
    }

    @Override
    public synchronized Status status() {
        if (process == null) {
            return new Status(State.STOPPED, -1);
        }
        if (process.isAlive()) {
            return new Status(stopRequested ? State.STOPPING : State.RUNNING, -1);
        }
        int code = process.exitValue();
        return stopRequested || code == 0 ? new Status(State.STOPPED, code) : new Status(State.CRASHED, code);
    }

    @Override
    public void start() throws IOException {
        start(1);
    }

    /** Lanza el tracker con la variable SAFEMODE_CAMERAS: un numero entero, nunca texto del navegador. */
    @Override
    public synchronized void start(int cameras) throws IOException {
        if (cameras < 1) {
            throw new IllegalArgumentException("Debe haber al menos una camara");
        }
        State state = status().state();
        if (state == State.RUNNING || state == State.STOPPING) {
            throw new IllegalStateException("El tracker ya esta " + (state == State.RUNNING ? "corriendo" : "cerrandose"));
        }
        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        builder.environment().putAll(extraEnv);
        builder.environment().put("SAFEMODE_CAMERAS", Integer.toString(cameras));
        process = builder.start();
        stopRequested = false;
    }

    @Override
    public synchronized void requestStop() {
        if (process == null || !process.isAlive() || stopRequested) {
            return;
        }
        stopRequested = true;
        Process target = process;
        try {
            OutputStream stdin = target.getOutputStream();
            stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            stdin.close();
        } catch (IOException e) {
            // el hijo ya cerro su entrada: la espera de abajo lo mata si hace falta
        }
        Thread killer = new Thread(() -> {
            try {
                if (!target.waitFor(stopTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    killTree(target);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "tracker-stop");
        killer.setDaemon(true);
        killer.start();
    }

    /** Detiene el tracker y espera a que termine (lo usa el cierre del gateway). */
    public void stopAndWait() {
        Process target;
        synchronized (this) {
            target = process;
        }
        requestStop();
        if (target == null) {
            return;
        }
        try {
            if (!target.waitFor(stopTimeout.toMillis() + 2000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                killTree(target);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void killTree(Process target) {
        target.descendants().forEach(ProcessHandle::destroyForcibly);
        target.destroyForcibly();
    }
}
