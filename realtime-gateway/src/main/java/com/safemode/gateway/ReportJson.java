package com.safemode.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.safemode.alerts.AlertRules;
import com.safemode.presence.PresenceEventStore.StoredEvent;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Convierte los eventos de presencia al JSON del reporte del panel. A diferencia del centro de alertas (que solo muestra
 * retiros sospechosos), el reporte lista TODO lo que el sistema registro: cada objeto que quedo en reposo y cada retiro,
 * incluidos los que no generan alerta (por ejemplo, el dueno que recoge su mochila). Cada fila dice ademas que alerta
 * generaria ese evento (segun {@link AlertRules}), para que se vea por que hubo o no hubo alerta.
 * Las fotos viajan como URL relativa ({@code /photo/<archivo>}), igual que en {@link AlertJson}.
 */
final class ReportJson {

    /** Filas que entrega el reporte: las mas recientes. */
    static final int MAX_ROWS = 200;

    private ReportJson() {}

    static JsonObject toJson(List<StoredEvent> events, IntFunction<String> cameraName) {
        JsonArray rows = new JsonArray();
        for (StoredEvent event : events) {
            rows.add(row(event, cameraName));
        }
        JsonObject report = new JsonObject();
        report.add("events", rows);
        report.addProperty("limit", MAX_ROWS);
        return report;
    }

    static JsonObject row(StoredEvent event, IntFunction<String> cameraName) {
        JsonObject json = new JsonObject();
        json.addProperty("id", event.id());
        json.addProperty("occurredAt", event.occurredAt().toString());
        json.addProperty("cameraId", event.cameraId());
        json.addProperty("cameraName", cameraName.apply(event.cameraId()));
        json.addProperty("eventType", event.eventType());
        json.addProperty("className", event.className());
        json.addProperty("trackedObjectId", event.trackedObjectId());
        json.addProperty("ownerPersonId", event.ownerPersonId());
        json.addProperty("ownerDescription", event.ownerDescription());
        json.addProperty("ownerAiDescription", event.ownerAiDescription());
        json.addProperty("removalKind", event.removalKind());
        json.addProperty("removerPersonId", event.removerPersonId());
        json.addProperty("removerDescription", event.removerDescription());
        json.addProperty("removerAiDescription", event.removerAiDescription());
        json.addProperty("framePhoto", AlertJson.photoUrl(event.framePath()));
        json.addProperty("ownerPhoto", AlertJson.photoUrl(event.ownerCropPath()));
        json.addProperty("removerPhoto", AlertJson.photoUrl(event.removerCropPath()));
        var decision = AlertRules.evaluate(event);
        json.addProperty("alertType", decision.map(d -> d.type().name()).orElse(null));
        json.addProperty("severity", decision.map(d -> d.severity().name()).orElse(null));
        return json;
    }
}
