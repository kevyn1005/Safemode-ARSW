package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore.StoredEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertRulesTest {

    private static StoredEvent removed(String kind, String ownerDescription, String removerDescription) {
        return new StoredEvent(7, 1, "suitcase", "REMOVED", Instant.parse("2026-01-01T10:00:00Z"), 100, 100, 50, 80,
                true, "frame.png", 1L, ownerDescription, null, null, kind, 2L, removerDescription, null, null);
    }

    @Test
    void retiroPorOtraPersonaEsPosibleRobo() {
        AlertRules.Decision d = AlertRules.evaluate(removed("BY_OTHER", "persona con camisa roja", "persona con camisa azul"))
                .orElseThrow();
        assertEquals(AlertType.POSSIBLE_THEFT, d.type());
        assertEquals(Severity.HIGH, d.severity());
        assertTrue(d.message().contains("maleta"));
        assertFalse(d.message().contains("camisa"), "el titular no repite colores aproximados: van en las tarjetas del panel");
    }

    @Test
    void retiroPorElDuenoNoGeneraAlerta() {
        assertTrue(AlertRules.evaluate(removed("BY_OWNER", "persona con camisa roja", null)).isEmpty());
    }

    @Test
    void retiroAmbiguoEsSeveridadMedia() {
        AlertRules.Decision d = AlertRules.evaluate(removed("OWNER_AND_OTHER_NEAR", null, null)).orElseThrow();
        assertEquals(AlertType.AMBIGUOUS_REMOVAL, d.type());
        assertEquals(Severity.MEDIUM, d.severity());
    }

    @Test
    void objetoQueDesapareceSinNadieCercaEsSeveridadMedia() {
        AlertRules.Decision d = AlertRules.evaluate(removed("NO_ONE_NEAR", null, null)).orElseThrow();
        assertEquals(AlertType.OBJECT_VANISHED, d.type());
        assertEquals(Severity.MEDIUM, d.severity());
    }

    @Test
    void retiroSinDuenoRegistradoEsSeveridadBaja() {
        AlertRules.Decision d = AlertRules.evaluate(removed("OWNER_UNKNOWN", null, "persona con camisa negra")).orElseThrow();
        assertEquals(AlertType.UNKNOWN_OWNER_REMOVAL, d.type());
        assertEquals(Severity.LOW, d.severity());
    }

    @Test
    void unEventoDeRegistroNoGeneraAlerta() {
        StoredEvent registered = new StoredEvent(1, 1, "backpack", "REGISTERED_AT_REST", Instant.now(), 0, 0, 10, 10,
                null, null, 1L, null, null, null, null, null, null, null, null);
        assertEquals(Optional.empty(), AlertRules.evaluate(registered));
    }

    @Test
    void unTipoDeRetiroDesconocidoNoRompeNada() {
        assertTrue(AlertRules.evaluate(removed("ALGO_NUEVO", null, null)).isEmpty());
    }

    private static StoredEvent removedObject(String className, String kind) {
        return new StoredEvent(1, 1, className, "REMOVED", Instant.now(), 0, 0, 10, 10, false, null,
                null, null, null, null, kind, null, null, null, null);
    }

    @Test
    void elArticuloYElParticipioConcuerdanConElGeneroDelObjeto() {
        assertEquals("Posible robo: el bolso fue retirado por alguien distinto del dueño",
                AlertRules.evaluate(removedObject("handbag", "BY_OTHER")).orElseThrow().message());
        assertEquals("Posible robo: la maleta fue retirada por alguien distinto del dueño",
                AlertRules.evaluate(removedObject("suitcase", "BY_OTHER")).orElseThrow().message());
        assertEquals("Retiraron un bolso que no tenía dueño registrado",
                AlertRules.evaluate(removedObject("handbag", "OWNER_UNKNOWN")).orElseThrow().message());
        assertEquals("Retiraron una mochila que no tenía dueño registrado",
                AlertRules.evaluate(removedObject("backpack", "OWNER_UNKNOWN")).orElseThrow().message());
        assertEquals("La mochila desapareció sin nadie cerca",
                AlertRules.evaluate(removedObject("backpack", "NO_ONE_NEAR")).orElseThrow().message());
        assertEquals("El bolso desapareció sin nadie cerca",
                AlertRules.evaluate(removedObject("handbag", "NO_ONE_NEAR")).orElseThrow().message());
    }

    @Test
    void nombraLaClaseEnEspanolYConservaLasDesconocidas() {
        StoredEvent backpack = new StoredEvent(1, 1, "backpack", "REMOVED", Instant.now(), 0, 0, 10, 10, false, null,
                null, null, null, null, "NO_ONE_NEAR", null, null, null, null);
        assertTrue(AlertRules.evaluate(backpack).orElseThrow().message().contains("mochila"));
        StoredEvent other = new StoredEvent(1, 1, "umbrella", "REMOVED", Instant.now(), 0, 0, 10, 10, false, null,
                null, null, null, null, "NO_ONE_NEAR", null, null, null, null);
        assertTrue(AlertRules.evaluate(other).orElseThrow().message().contains("umbrella"));
    }
}
