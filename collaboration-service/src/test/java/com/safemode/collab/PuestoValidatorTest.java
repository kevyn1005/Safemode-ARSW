package com.safemode.collab;

import com.google.gson.JsonObject;
import com.safemode.common.HttpError;
import com.safemode.common.Json;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuestoValidatorTest {

    private static JsonObject json(String text) {
        return Json.parseObject(text);
    }

    private static void rejects(String body) {
        HttpError error = assertThrows(HttpError.class, () -> PuestoValidator.changes(json(body)), body);
        assertEquals(400, error.status());
    }

    private static String render(Bson update) {
        return update.toBsonDocument(BsonDocument.class, CodecRegistries.fromProviders(new org.bson.codecs.DocumentCodecProvider(),
                new org.bson.codecs.BsonValueCodecProvider(), new org.bson.codecs.ValueCodecProvider())).toJson();
    }

    @Test
    void expectedVersionIsRequiredAndMustBeAPositiveInteger() {
        assertEquals(3L, PuestoValidator.expectedVersion(json("{\"expectedVersion\":3}")));
        for (String body : new String[] {"{}", "{\"expectedVersion\":\"3\"}", "{\"expectedVersion\":0}",
                "{\"expectedVersion\":1.5}", "{\"expectedVersion\":-2}", "{\"expectedVersion\":null}"}) {
            HttpError error = assertThrows(HttpError.class, () -> PuestoValidator.expectedVersion(json(body)), body);
            assertEquals(400, error.status());
        }
    }

    @Test
    void acceptsATitleAndTrimsIt() {
        String update = render(PuestoValidator.changes(json("{\"expectedVersion\":1,\"title\":\"  Entrada  \"}")));
        assertTrue(update.contains("\"title\": \"Entrada\""), update);
    }

    @Test
    void rejectsEmptyOrTooLongTitles() {
        rejects("{\"expectedVersion\":1,\"title\":\"\"}");
        rejects("{\"expectedVersion\":1,\"title\":\"   \"}");
        rejects("{\"expectedVersion\":1,\"title\":\"" + "x".repeat(81) + "\"}");
        rejects("{\"expectedVersion\":1,\"title\":42}");
    }

    @Test
    void rejectsUnknownFieldsAndEmptyChanges() {
        rejects("{\"expectedVersion\":1}");
        rejects("{\"expectedVersion\":1,\"role\":\"admin\"}");
        rejects("{\"expectedVersion\":1,\"title\":\"a\",\"version\":99}");
    }

    @Test
    void preferencesAreChangedFieldByFieldWithFixedTypesAndRanges() {
        String update = render(PuestoValidator.changes(json(
                "{\"expectedVersion\":1,\"preferences\":{\"minSeverity\":\"HIGH\",\"notifyTelegram\":false,\"photoRetentionDays\":7}}")));
        assertTrue(update.contains("preferences.minSeverity"), update);
        assertTrue(update.contains("preferences.notifyTelegram"), update);
        assertTrue(update.contains("preferences.photoRetentionDays"), update);

        rejects("{\"expectedVersion\":1,\"preferences\":{}}");
        rejects("{\"expectedVersion\":1,\"preferences\":{\"minSeverity\":\"CRITICAL\"}}");
        rejects("{\"expectedVersion\":1,\"preferences\":{\"notifyTelegram\":\"si\"}}");
        rejects("{\"expectedVersion\":1,\"preferences\":{\"photoRetentionDays\":0}}");
        rejects("{\"expectedVersion\":1,\"preferences\":{\"photoRetentionDays\":366}}");
        rejects("{\"expectedVersion\":1,\"preferences\":{\"fondo\":\"rojo\"}}");
    }

    @Test
    void camerasAcceptNullOrInsideFrameZonesOnly() {
        PuestoValidator.changes(json("{\"expectedVersion\":1,\"cameras\":[{\"id\":1,\"zone\":{\"x\":0.1,\"y\":0.1,\"w\":0.5,\"h\":0.5}},{\"id\":2,\"zone\":null}]}"));
        rejects("{\"expectedVersion\":1,\"cameras\":[{\"id\":9}]}");
        rejects("{\"expectedVersion\":1,\"cameras\":[{\"id\":1},{\"id\":1}]}");
        rejects("{\"expectedVersion\":1,\"cameras\":[{\"id\":1,\"zone\":{\"x\":0.8,\"y\":0.1,\"w\":0.5,\"h\":0.5}}]}");
        rejects("{\"expectedVersion\":1,\"cameras\":[{\"id\":1,\"zone\":{\"x\":0,\"y\":0,\"w\":0,\"h\":0.5}}]}");
        rejects("{\"expectedVersion\":1,\"cameras\":[{\"id\":1,\"zone\":{\"x\":-0.1,\"y\":0,\"w\":0.2,\"h\":0.2}}]}");
        rejects("{\"expectedVersion\":1,\"cameras\":\"todas\"}");
    }

    @Test
    void malformedBodiesAreBadRequests() {
        for (String body : new String[] {"", "no es json", "[1,2]", "42"}) {
            HttpError error = assertThrows(HttpError.class, () -> Json.parseObject(body), body);
            assertEquals(400, error.status());
        }
    }
}
