package com.safemode.gateway;

import com.google.gson.JsonObject;
import com.safemode.alerts.Alert;
import com.safemode.alerts.AlertType;
import com.safemode.alerts.Severity;
import com.safemode.presence.PresenceEventStore.StoredEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertJsonTest {

    private static final Instant WHEN = Instant.parse("2026-10-11T17:48:50Z");

    private static Alert alert(AlertType type, Severity severity) {
        return new Alert(5, 9, WHEN, type, severity, "backpack", "mensaje", "frames/obj1_REMOVED_1.png", WHEN.plusSeconds(1), false);
    }

    private static StoredEvent event(boolean looksLikeOwner) {
        return new StoredEvent(9, 1, "backpack", "REMOVED", WHEN, 1, 2, 3, 4, true, "frames/obj1_REMOVED_1.png", 3L, "persona", null, null,
                "BY_OTHER", 7L, "persona", "frames/obj1_REMOVER_person7_1.png", null, 1, looksLikeOwner);
    }

    @Test
    void laAlertaDiceSiQuienRetiraSePareceAlDueno() {
        JsonObject json = AlertJson.toJson(alert(AlertType.POSSIBLE_OWNER_RETURN, Severity.MEDIUM), Optional.of(event(true)));

        assertTrue(json.get("removerLooksLikeOwner").getAsBoolean());
        assertEquals("POSSIBLE_OWNER_RETURN", json.get("type").getAsString());
        assertEquals("MEDIUM", json.get("severity").getAsString());
        assertEquals("/photo/obj1_REMOVER_person7_1.png", json.get("removerPhoto").getAsString());
    }

    @Test
    void unPosibleRoboNoLlevaLaPista() {
        JsonObject json = AlertJson.toJson(alert(AlertType.POSSIBLE_THEFT, Severity.HIGH), Optional.of(event(false)));

        assertFalse(json.get("removerLooksLikeOwner").getAsBoolean());
    }

    @Test
    void sinEvidenciaNoHayPista() {
        JsonObject json = AlertJson.toJson(alert(AlertType.POSSIBLE_THEFT, Severity.HIGH), Optional.empty());

        assertFalse(json.get("removerLooksLikeOwner").getAsBoolean());
    }
}
