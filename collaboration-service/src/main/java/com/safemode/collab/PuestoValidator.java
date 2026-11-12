package com.safemode.collab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mongodb.client.model.Updates;
import com.safemode.common.HttpError;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Valida el cuerpo de {@code PUT /api/puestos/{id}} y lo convierte en los {@code $set} de MongoDB. Nada del cliente
 * llega a la base sin pasar por aqui: se aceptan solo los campos conocidos, con tipos y rangos fijos (un campo
 * desconocido es un 400, no se guarda "por si acaso").
 *
 * <ul>
 *   <li>{@code title}: texto de 1 a 80 caracteres, sin caracteres de control.</li>
 *   <li>{@code preferences}: {@code minSeverity} (LOW|MEDIUM|HIGH), {@code notifyBrowser}, {@code notifyTelegram}
 *       (booleanos) y {@code photoRetentionDays} (1 a 365). Se cambian por campo ({@code preferences.<campo>}).</li>
 *   <li>{@code cameras}: hasta 8, cada una {@code {id: 1..8, zone: null | {x,y,w,h en 0..1}}}. Se reemplaza la lista.</li>
 * </ul>
 */
public final class PuestoValidator {

    static final int MAX_TITLE_LENGTH = 80;
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> TOP_LEVEL = Set.of("expectedVersion", "title", "preferences", "cameras");
    private static final Set<String> PREFERENCES = Set.of("minSeverity", "notifyBrowser", "notifyTelegram", "photoRetentionDays");

    private PuestoValidator() {}

    /** La version que el cliente leyo y sobre la que edita. */
    public static long expectedVersion(JsonObject body) {
        JsonElement element = body.get("expectedVersion");
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new HttpError(400, "expectedVersion es obligatorio y debe ser un entero");
        }
        double value = element.getAsDouble();
        if (value != Math.rint(value) || value < 1 || value > 1e12) {
            throw new HttpError(400, "expectedVersion debe ser un entero mayor o igual a 1");
        }
        return (long) value;
    }

    /** Los {@code $set} del cambio; {@link HttpError} 400 si no hay nada que cambiar o algo no es valido. */
    public static Bson changes(JsonObject body) {
        for (String key : body.keySet()) {
            if (!TOP_LEVEL.contains(key)) {
                throw new HttpError(400, "Campo desconocido: " + key);
            }
        }
        List<Bson> sets = new ArrayList<>();
        if (body.has("title")) {
            sets.add(Updates.set("title", title(body.get("title"))));
        }
        if (body.has("preferences")) {
            sets.addAll(preferences(body.get("preferences")));
        }
        if (body.has("cameras")) {
            sets.add(Updates.set("cameras", cameras(body.get("cameras"))));
        }
        if (sets.isEmpty()) {
            throw new HttpError(400, "No hay nada que cambiar: envie title, preferences o cameras");
        }
        return Updates.combine(sets);
    }

    static String title(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new HttpError(400, "title debe ser texto");
        }
        String clean = element.getAsString().replaceAll("\\p{Cntrl}", " ").strip();
        if (clean.isEmpty() || clean.length() > MAX_TITLE_LENGTH) {
            throw new HttpError(400, "title debe tener de 1 a " + MAX_TITLE_LENGTH + " caracteres");
        }
        return clean;
    }

    private static List<Bson> preferences(JsonElement element) {
        if (!element.isJsonObject() || element.getAsJsonObject().size() == 0) {
            throw new HttpError(400, "preferences debe ser un objeto con al menos un campo");
        }
        List<Bson> sets = new ArrayList<>();
        for (var entry : element.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (!PREFERENCES.contains(key)) {
                throw new HttpError(400, "Preferencia desconocida: " + key);
            }
            switch (key) {
                case "minSeverity" -> {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                            || !SEVERITIES.contains(value.getAsString())) {
                        throw new HttpError(400, "minSeverity debe ser LOW, MEDIUM o HIGH");
                    }
                    sets.add(Updates.set("preferences.minSeverity", value.getAsString()));
                }
                case "photoRetentionDays" -> {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                        throw new HttpError(400, "photoRetentionDays debe ser un entero de 1 a 365");
                    }
                    double days = value.getAsDouble();
                    if (days != Math.rint(days) || days < 1 || days > 365) {
                        throw new HttpError(400, "photoRetentionDays debe ser un entero de 1 a 365");
                    }
                    sets.add(Updates.set("preferences.photoRetentionDays", (int) days));
                }
                default -> {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                        throw new HttpError(400, key + " debe ser true o false");
                    }
                    sets.add(Updates.set("preferences." + key, value.getAsBoolean()));
                }
            }
        }
        return sets;
    }

    private static List<Document> cameras(JsonElement element) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() > 8) {
            throw new HttpError(400, "cameras debe ser una lista de hasta 8 camaras");
        }
        JsonArray array = element.getAsJsonArray();
        List<Document> cameras = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) {
                throw new HttpError(400, "Cada camara debe ser un objeto {id, zone}");
            }
            JsonObject camera = item.getAsJsonObject();
            for (String key : camera.keySet()) {
                if (!key.equals("id") && !key.equals("zone")) {
                    throw new HttpError(400, "Campo desconocido en la camara: " + key);
                }
            }
            int id = integerIn(camera.get("id"), 1, 8, "id de camara");
            if (!ids.add(id)) {
                throw new HttpError(400, "Camara repetida: " + id);
            }
            Document doc = new Document("id", id);
            JsonElement zone = camera.get("zone");
            doc.append("zone", zone == null || zone.isJsonNull() ? null : zone(zone));
            cameras.add(doc);
        }
        return cameras;
    }

    private static Document zone(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new HttpError(400, "zone debe ser null o un objeto {x, y, w, h}");
        }
        JsonObject zone = element.getAsJsonObject();
        for (String key : zone.keySet()) {
            if (!Set.of("x", "y", "w", "h").contains(key)) {
                throw new HttpError(400, "Campo desconocido en la zona: " + key);
            }
        }
        double x = fraction(zone.get("x"), "x");
        double y = fraction(zone.get("y"), "y");
        double w = fraction(zone.get("w"), "w");
        double h = fraction(zone.get("h"), "h");
        if (w <= 0 || h <= 0 || x + w > 1.0000001 || y + h > 1.0000001) {
            throw new HttpError(400, "La zona debe tener area y quedar dentro del cuadro (0 a 1)");
        }
        return new Document("x", x).append("y", y).append("w", w).append("h", h);
    }

    private static double fraction(JsonElement element, String name) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new HttpError(400, "zone." + name + " debe ser un numero entre 0 y 1");
        }
        double value = element.getAsDouble();
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new HttpError(400, "zone." + name + " debe estar entre 0 y 1");
        }
        return value;
    }

    private static int integerIn(JsonElement element, int min, int max, String what) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new HttpError(400, what + " debe ser un entero de " + min + " a " + max);
        }
        double value = element.getAsDouble();
        if (value != Math.rint(value) || value < min || value > max) {
            throw new HttpError(400, what + " debe ser un entero de " + min + " a " + max);
        }
        return (int) value;
    }
}
