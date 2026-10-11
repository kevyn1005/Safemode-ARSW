package com.safemode.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Prueba con un proceso hijo REAL (una JVM pequena), asi se comprueba de verdad lanzar, parar y matar. */
class TrackerProcessTest {

    /** Hijo falso: "normal" espera "stop" o el fin de la entrada; "crash" sale con error; "ignore" no hace caso de nada. */
    public static class FakeChild {
        public static void main(String[] args) throws Exception {
            String mode = args.length > 0 ? args[0] : "normal";
            System.out.println("listo env=" + System.getenv("SAFEMODE_PRUEBA"));
            switch (mode) {
                case "crash" -> System.exit(3);
                case "ignore" -> Thread.sleep(600_000);
                default -> {
                    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    do {
                        line = in.readLine();
                    } while (line != null && !line.trim().equals("stop"));
                    System.out.println("parando");
                }
            }
        }
    }

    @TempDir
    Path tmp;

    private TrackerProcess tracker;

    @AfterEach
    void cleanUp() {
        if (tracker != null) {
            tracker.stopAndWait();
        }
    }

    private TrackerProcess tracker(String mode, Duration stopTimeout) {
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), TrackerProcessTest.class.getName() + "$FakeChild", mode);
        tracker = new TrackerProcess(command, tmp, Map.of("SAFEMODE_PRUEBA", "hola"), tmp.resolve("logs").resolve("t.log"),
                stopTimeout);
        return tracker;
    }

    private void waitFor(TrackerControl.State expected) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (tracker.status().state() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(expected, tracker.status().state());
    }

    @Test
    void empiezaDetenido() {
        assertEquals(TrackerControl.State.STOPPED, tracker("normal", Duration.ofSeconds(5)).status().state());
    }

    @Test
    void iniciaYSeDetieneConLaSenalDeParada() throws Exception {
        tracker("normal", Duration.ofSeconds(10)).start();
        assertEquals(TrackerControl.State.RUNNING, tracker.status().state());
        tracker.requestStop();
        waitFor(TrackerControl.State.STOPPED);
        String log = Files.readString(tmp.resolve("logs").resolve("t.log"));
        assertTrue(log.contains("parando"), "el hijo debe recibir 'stop' por su entrada: " + log);
    }

    @Test
    void elHijoRecibeLasVariablesDeEntornoFijas() throws Exception {
        tracker("normal", Duration.ofSeconds(10)).start();
        tracker.requestStop();
        waitFor(TrackerControl.State.STOPPED);
        assertTrue(Files.readString(tmp.resolve("logs").resolve("t.log")).contains("env=hola"));
    }

    @Test
    void noSeLanzaUnSegundoTrackerMientrasUnoCorre() throws Exception {
        tracker("normal", Duration.ofSeconds(10)).start();
        assertThrows(IllegalStateException.class, tracker::start);
    }

    @Test
    void siSeCaeConErrorQuedaComoCaidoConSuCodigo() throws Exception {
        tracker("crash", Duration.ofSeconds(5)).start();
        waitFor(TrackerControl.State.CRASHED);
        assertEquals(3, tracker.status().exitCode());
    }

    @Test
    void despuesDeCaerseSePuedeIniciarOtraVez() throws Exception {
        tracker("crash", Duration.ofSeconds(5)).start();
        waitFor(TrackerControl.State.CRASHED);
        assertDoesNotThrow(tracker::start);
    }

    @Test
    void siNoHaceCasoALaParadaSeLeMataPasadoElTiempo() throws Exception {
        tracker("ignore", Duration.ofMillis(500)).start();
        tracker.requestStop();
        assertEquals(TrackerControl.State.STOPPING, tracker.status().state());
        waitFor(TrackerControl.State.STOPPED);
    }

    @Test
    void detenerSinTrackerNoHaceNada() {
        assertDoesNotThrow(() -> tracker("normal", Duration.ofSeconds(1)).requestStop());
    }

    @Test
    void elComandoPorDefectoEsFijoYApuntaAlTracker() {
        List<String> command = TrackerProcess.mavenCommand();
        assertTrue(command.contains("object-presence-tracker"));
        assertTrue(command.contains("-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest"));
        assertTrue(command.contains("-Dexec.args=0"));
    }
}
