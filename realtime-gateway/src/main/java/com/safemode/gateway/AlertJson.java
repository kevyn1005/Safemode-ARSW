package com.safemode.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.safemode.alerts.Alert;
import com.safemode.presence.PresenceEventStore.StoredEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Convierte una alerta (y la evidencia del evento que la origino) al JSON que recibe el dashboard. Las fotos no viajan
 * por el WebSocket: solo su URL relativa ({@code /photo/<archivo>}), que el navegador pide al servidor HTTP.
 */
final class AlertJson {

    static final String PHOTO_PREFIX = "/photo/";

    private AlertJson() {}

    static JsonObject toJson(Alert alert, Optional<StoredEvent> evidence) {
        return toJson(alert, evidence, id -> "CAM " + id);
    }

    /** Igual, con el nombre de la camara que le puso el usuario ({@code cameraName} lo da a partir del id). */
    static JsonObject toJson(Alert alert, Optional<StoredEvent> evidence, IntFunction<String> cameraName) {
        JsonObject json = new JsonObject();
        json.addProperty("id", alert.id());
        json.addProperty("eventId", alert.eventId());
        json.addProperty("type", alert.type().name());
        json.addProperty("severity", alert.severity().name());
        json.addProperty("className", alert.className());
        json.addProperty("message", alert.message());
        json.addProperty("occurredAt", alert.eventOccurredAt().toString());
        json.addProperty("createdAt", alert.createdAt().toString());
        json.addProperty("acknowledged", alert.acknowledged());
        json.addProperty("framePhoto", photoUrl(alert.framePath()));

        StoredEvent event = evidence.orElse(null);
        int cameraId = event == null ? 1 : event.cameraId();
        json.addProperty("cameraId", cameraId);
        json.addProperty("cameraName", cameraName.apply(cameraId));
        json.addProperty("removalKind", event == null ? null : event.removalKind());
        json.addProperty("ownerPhoto", event == null ? null : photoUrl(event.ownerCropPath()));
        json.addProperty("ownerDescription", event == null ? null : event.ownerDescription());
        json.addProperty("ownerAiDescription", event == null ? null : event.ownerAiDescription());
        json.addProperty("removerPhoto", event == null ? null : photoUrl(event.removerCropPath()));
        json.addProperty("removerDescription", event == null ? null : event.removerDescription());
        json.addProperty("removerAiDescription", event == null ? null : event.removerAiDescription());
        return json;
    }

    static JsonObject envelope(String type, JsonObject alert) {
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        message.add("alert", alert);
        return message;
    }

    static JsonObject snapshot(List<JsonObject> alerts) {
        JsonArray array = new JsonArray();
        alerts.forEach(array::add);
        JsonObject message = new JsonObject();
        message.addProperty("type", "snapshot");
        message.add("alerts", array);
        return message;
    }

    /** Igual, y ademas con los nombres de las camaras. */
    static JsonObject snapshot(List<JsonObject> alerts, Map<Integer, String> cameras) {
        JsonObject message = snapshot(alerts);
        message.add("cameras", camerasArray(cameras));
        return message;
    }

    /** Mensaje {@code cameras}: los nombres vigentes (se envia cuando el usuario renombra una camara). */
    static JsonObject camerasMessage(Map<Integer, String> cameras) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "cameras");
        message.add("cameras", camerasArray(cameras));
        return message;
    }

    static JsonArray camerasArray(Map<Integer, String> cameras) {
        JsonArray array = new JsonArray();
        cameras.forEach((id, name) -> {
            JsonObject camera = new JsonObject();
            camera.addProperty("id", id);
            camera.addProperty("name", name);
            array.add(camera);
        });
        return array;
    }

    /**
     * URL relativa de la foto, o null si no hay ruta. Solo se usa el nombre del archivo, nunca la ruta del disco. La ruta
     * la guardo el tracker en su sistema (barras de Windows) y aqui puede leerla un Linux (contenedor): se corta en ambas.
     */
    static String photoUrl(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        String fileName = storedPath.substring(Math.max(storedPath.lastIndexOf('/'), storedPath.lastIndexOf('\\')) + 1);
        return fileName.isBlank() ? null : PHOTO_PREFIX + fileName;
    }
}
