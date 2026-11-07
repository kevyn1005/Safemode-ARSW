package com.safemode.collab;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.safemode.common.HttpError;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Las reglas del historial que no dependen de MongoDB: que cuenta como cambio, el cursor, el tamano de pagina y la forma de la respuesta. */
class HistoryStoreTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void loQueTocoUnCambioSaleDelCuerpoSinContarLaVersionNiElOrden() {
        assertEquals(List.of("title", "cameras"), HistoryStore.changedFields(json("{\"cameras\":[],\"expectedVersion\":3,\"title\":\"X\"}")));
        assertEquals(List.of("preferences"), HistoryStore.changedFields(json("{\"expectedVersion\":1,\"preferences\":{\"minSeverity\":\"LOW\"}}")));
        assertEquals(List.of("title", "preferences", "cameras"),
                HistoryStore.changedFields(json("{\"cameras\":[],\"preferences\":{},\"title\":\"X\",\"expectedVersion\":1}")),
                "el orden es siempre el mismo");
        assertEquals(List.of(), HistoryStore.changedFields(json("{\"expectedVersion\":1}")));
    }

    @Test
    void elCursorBeforeEsUnEnteroOVacio() {
        assertNull(HistoryStore.parseBefore(null));
        assertNull(HistoryStore.parseBefore(""));
        assertNull(HistoryStore.parseBefore("  "));
        assertEquals(7L, HistoryStore.parseBefore("7"));
        assertEquals(999999999999L, HistoryStore.parseBefore("999999999999"));
        for (String bad : new String[] {"0", "-1", "1.5", "abc", "1e3", "7; DROP", "1234567890123", "$gt", "{\"$ne\":1}"}) {
            HttpError e = assertThrows(HttpError.class, () -> HistoryStore.parseBefore(bad), bad);
            assertEquals(400, e.status());
        }
    }

    @Test
    void elTamanoDePaginaVaDe1a50ConUnValorPorDefecto() {
        assertEquals(20, HistoryStore.parseLimit(null));
        assertEquals(20, HistoryStore.parseLimit(""));
        assertEquals(1, HistoryStore.parseLimit("1"));
        assertEquals(50, HistoryStore.parseLimit("50"));
        for (String bad : new String[] {"0", "51", "-3", "x", "10.5", "1000000", "5 "}) {
            assertEquals(400, assertThrows(HttpError.class, () -> HistoryStore.parseLimit(bad), bad).status());
        }
    }

    @Test
    void laEntradaSeEntregaSinElIdInternoYConLaHoraEnFormatoIso() {
        Instant when = Instant.parse("2026-11-07T14:42:51.719Z");
        Document stored = new Document("_id", "interno").append("puesto_id", "puesto-1").append("version", 7L)
                .append("title", "Puesto principal").append("changed", List.of("cameras")).append("by", "ana").append("at", Date.from(when));

        Map<String, Object> view = HistoryStore.view(stored);

        assertEquals(List.of("version", "title", "changed", "by", "at"), List.copyOf(view.keySet()));
        assertEquals(7L, view.get("version"));
        assertEquals("2026-11-07T14:42:51.719Z", view.get("at"));
        assertFalse(view.containsKey("_id") || view.containsKey("puesto_id"), "no se filtran campos internos");
    }
}
