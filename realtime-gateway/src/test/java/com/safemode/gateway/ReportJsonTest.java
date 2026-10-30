package com.safemode.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.safemode.presence.PresenceEventStore.StoredEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportJsonTest {

    private static final Instant WHEN = Instant.parse("2026-10-11T17:48:50Z");

    private static StoredEvent registered(long id, int cameraId) {
        return new StoredEvent(id, 1, "backpack", "REGISTERED_AT_REST", WHEN, 404, 408, 300, 280, null,
                "object-presence-tracker\\data\\frames\\obj1_REGISTERED_AT_REST_1.png", 3L, "persona con camisa blanca",
                "object-presence-tracker\\data\\frames\\obj1_OWNER_person3_1.png", "jumper blanco; pantalones azules",
                null, null, null, null, null, cameraId);
    }

    private static StoredEvent removed(long id, int cameraId, String kind) {
        return new StoredEvent(id, 1, "backpack", "REMOVED", WHEN.plusSeconds(20), 404, 408, 300, 280, true,
                "frames/obj1_REMOVED_2.png", 3L, "persona con camisa blanca", null, null,
                kind, 7L, "persona con camisa negra", "frames/obj1_REMOVER_person7_2.png", "chaqueta negra", cameraId);
    }

    @Test
    void unRegistroEnReposoNoGeneraAlertaYTraeLasFotosSoloPorNombre() {
        JsonObject row = ReportJson.row(registered(5, 1), id -> "CAM " + id);

        assertEquals(5, row.get("id").getAsLong());
        assertEquals("2026-10-11T17:48:50Z", row.get("occurredAt").getAsString());
        assertEquals("REGISTERED_AT_REST", row.get("eventType").getAsString());
        assertEquals("backpack", row.get("className").getAsString());
        assertEquals(3, row.get("ownerPersonId").getAsLong());
        assertEquals("/photo/obj1_REGISTERED_AT_REST_1.png", row.get("framePhoto").getAsString());
        assertEquals("/photo/obj1_OWNER_person3_1.png", row.get("ownerPhoto").getAsString());
        assertTrue(row.get("removerPhoto").isJsonNull());
        assertTrue(row.get("alertType").isJsonNull(), "registrar un objeto no es una alerta");
        assertTrue(row.get("severity").isJsonNull());
    }

    @Test
    void unRetiroPorOtraPersonaDiceQueAlertaGenera() {
        JsonObject row = ReportJson.row(removed(6, 2, "BY_OTHER"), id -> id == 2 ? "Cancha sur" : "CAM " + id);

        assertEquals(2, row.get("cameraId").getAsInt());
        assertEquals("Cancha sur", row.get("cameraName").getAsString());
        assertEquals("BY_OTHER", row.get("removalKind").getAsString());
        assertEquals(7, row.get("removerPersonId").getAsLong());
        assertEquals("chaqueta negra", row.get("removerAiDescription").getAsString());
        assertEquals("/photo/obj1_REMOVER_person7_2.png", row.get("removerPhoto").getAsString());
        assertEquals("POSSIBLE_THEFT", row.get("alertType").getAsString());
        assertEquals("HIGH", row.get("severity").getAsString());
    }

    @Test
    void siQuienRetiraSePareceAlDuenoElReporteLoDiceYLaAlertaEsDeDuenoQueVolvio() {
        StoredEvent lookAlike = new StoredEvent(10, 1, "backpack", "REMOVED", WHEN, 404, 408, 300, 280, true, null, 3L, null, null, null,
                "BY_OTHER", 7L, null, null, null, 1, true);
        JsonObject row = ReportJson.row(lookAlike, id -> "CAM " + id);

        assertTrue(row.get("removerLooksLikeOwner").getAsBoolean());
        assertEquals("POSSIBLE_OWNER_RETURN", row.get("alertType").getAsString());
        assertEquals("MEDIUM", row.get("severity").getAsString());
        assertFalse(ReportJson.row(removed(11, 1, "BY_OTHER"), id -> "CAM " + id).get("removerLooksLikeOwner").getAsBoolean());
    }

    @Test
    void elRetiroPorSuDuenoApareceEnElReportePeroSinAlerta() {
        JsonObject row = ReportJson.row(removed(7, 1, "BY_OWNER"), id -> "CAM " + id);

        assertEquals("BY_OWNER", row.get("removalKind").getAsString());
        assertTrue(row.get("alertType").isJsonNull(), "el dueno que recoge su mochila no es una alerta");
    }

    @Test
    void unTipoDeRetiroDesconocidoNoRompeElReporte() {
        JsonObject row = ReportJson.row(removed(8, 1, "TIPO_NUEVO"), id -> "CAM " + id);

        assertEquals("TIPO_NUEVO", row.get("removalKind").getAsString());
        assertTrue(row.get("alertType").isJsonNull());
    }

    @Test
    void elReporteConservaElOrdenYDiceElLimite() {
        JsonObject report = ReportJson.toJson(List.of(removed(9, 1, "NO_ONE_NEAR"), registered(8, 1)), id -> "CAM " + id);
        JsonArray rows = report.getAsJsonArray("events");

        assertEquals(2, rows.size());
        assertEquals(9, rows.get(0).getAsJsonObject().get("id").getAsLong());
        assertEquals("OBJECT_VANISHED", rows.get(0).getAsJsonObject().get("alertType").getAsString());
        assertEquals(8, rows.get(1).getAsJsonObject().get("id").getAsLong());
        assertEquals(ReportJson.MAX_ROWS, report.get("limit").getAsInt());
    }

    @Test
    void sinEventosElReporteEstaVacioPeroValido() {
        JsonObject report = ReportJson.toJson(List.of(), id -> "CAM " + id);

        assertTrue(report.getAsJsonArray("events").isEmpty());
        assertFalse(report.toString().contains("null"));
    }
}
