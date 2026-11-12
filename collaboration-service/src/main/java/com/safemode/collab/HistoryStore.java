package com.safemode.collab;

import com.google.gson.JsonObject;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.safemode.common.HttpError;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * El historial de cambios de un puesto (ADR 0005, "Historial creciente"): una coleccion SEPARADA, {@code puesto_history}, con
 * UN documento por cada cambio, en vez de una lista dentro del documento del puesto (que crece sin limite, se lee entera con cada
 * lectura, se reescribe con cada edicion y choca con el limite de 16 MB de MongoDB).
 *
 * <pre>
 *   { puesto_id: "puesto-1", version: 7, title: "...", changed: ["cameras"], by: "ana", at: ISODate("...") }
 * </pre>
 * <ul>
 *   <li><b>Indice unico</b> {@code {puesto_id: 1, version: -1}}: una entrada por version y la consulta "los ultimos cambios" sale
 *       del propio indice, ya ordenada.</li>
 *   <li><b>Paginacion por cursor</b> (no por {@code skip}): la primera pagina trae las N mas recientes; la siguiente pide
 *       {@code version < nextBefore}. Cada pagina cuesta lo mismo aunque haya miles de versiones y no repite ni salta filas si
 *       llegan cambios nuevos mientras se lee.</li>
 *   <li><b>Retencion</b>: indice TTL sobre {@code at} ({@value #DEFAULT_RETENTION_DAYS} dias por defecto): MongoDB borra solo las
 *       entradas viejas (revisa cada ~60 s, asi que el borrado puede tardar un poco mas que el plazo).</li>
 *   <li><b>Se escribe DESPUES del cambio</b>, no en la misma operacion: un documento y otra coleccion no comparten una transaccion
 *       simple (las de varios documentos exigen un conjunto de replicas, fuera del alcance de una sola instancia). Si la entrada
 *       falla, se pierde una entrada de auditoria, nunca el cambio; queda un hueco en las versiones y la metrica
 *       {@code safemode_history_writes_total{result="error"}} lo cuenta.</li>
 * </ul>
 */
public class HistoryStore {

    public static final int DEFAULT_RETENTION_DAYS = 90;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    private static final Pattern DIGITS = Pattern.compile("\\d{1,12}");
    /** Los campos del puesto que un cambio puede tocar, en un orden fijo (el de la respuesta no depende del orden del cuerpo). */
    private static final List<String> TRACKED = List.of("title", "preferences", "cameras");
    private static final int INDEX_OPTIONS_CONFLICT = 85;
    private static final int INDEX_KEY_SPECS_CONFLICT = 86;

    private final MongoCollection<Document> collection;

    public HistoryStore(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    /** Una pagina del historial y el cursor para pedir la siguiente ({@code null} si no hay mas). */
    public record Page(List<Map<String, Object>> items, Long nextBefore) {}

    /** Crea los indices (idempotente). Si cambio el plazo de retencion, vuelve a crear el TTL con el plazo nuevo. */
    public void ensureIndexes(int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("La retencion del historial debe ser de al menos 1 dia");
        }
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("puesto_id"), Indexes.descending("version")),
                new IndexOptions().unique(true).name("puesto_version_unique"));
        IndexOptions ttl = new IndexOptions().name("at_ttl").expireAfter((long) retentionDays, TimeUnit.DAYS);
        try {
            collection.createIndex(Indexes.ascending("at"), ttl);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != INDEX_OPTIONS_CONFLICT && e.getErrorCode() != INDEX_KEY_SPECS_CONFLICT) {
                throw e;
            }
            collection.dropIndex("at_ttl");
            collection.createIndex(Indexes.ascending("at"), ttl);
        }
    }

    /**
     * Anota el cambio. Devuelve {@code false} si esa version ya tenia entrada (el indice unico lo impide: escribir dos veces la
     * misma version es inofensivo). Cualquier otra falla de MongoDB sube a quien llama, que decide no frenar el cambio.
     */
    public boolean record(String puesto, long version, String title, List<String> changed, String by, Instant at) {
        Document entry = new Document("puesto_id", puesto)
                .append("version", version)
                .append("title", title)
                .append("changed", changed)
                .append("by", by)
                .append("at", Date.from(at));
        try {
            collection.insertOne(entry);
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    /** Los cambios mas recientes primero: hasta {@code limit}, de las versiones MENORES que {@code before} (o desde la ultima si es null). */
    public Page page(String puesto, Long before, int limit) {
        Bson filter = before == null
                ? Filters.eq("puesto_id", puesto)
                : Filters.and(Filters.eq("puesto_id", puesto), Filters.lt("version", before));
        // una fila de mas para saber si hay otra pagina sin contar toda la coleccion
        List<Document> found = collection.find(filter).sort(Sorts.descending("version")).limit(limit + 1).into(new ArrayList<>());
        boolean more = found.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document doc : found.subList(0, Math.min(limit, found.size()))) {
            items.add(view(doc));
        }
        Long next = more ? ((Number) found.get(limit - 1).get("version")).longValue() : null;
        return new Page(items, next);
    }

    /** Borra todo el historial (solo para el reinicio del escenario: la version vuelve a 1 y las entradas viejas chocarian con el indice unico). */
    public void clear() {
        collection.deleteMany(new Document());
    }

    // ------------------------------------------------------------------------------------------------ puras (se prueban sin MongoDB)

    /** Los campos que toco un cambio, tomados del cuerpo del PUT: {@code expectedVersion} no cuenta y el orden es siempre el mismo. */
    static List<String> changedFields(JsonObject body) {
        List<String> changed = new ArrayList<>();
        for (String field : TRACKED) {
            if (body.has(field)) {
                changed.add(field);
            }
        }
        return changed;
    }

    /** El cursor {@code ?before=}: vacio = desde el principio; si no, un entero de 1 a 12 digitos (nunca texto libre). */
    static Long parseBefore(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!DIGITS.matcher(raw).matches() || Long.parseLong(raw) < 1) {
            throw new HttpError(400, "before debe ser un entero mayor o igual a 1");
        }
        return Long.parseLong(raw);
    }

    /** El tamano de pagina {@code ?limit=}: vacio = {@value #DEFAULT_LIMIT}; si no, un entero de 1 a {@value #MAX_LIMIT}. */
    static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        if (!DIGITS.matcher(raw).matches() || Long.parseLong(raw) < 1 || Long.parseLong(raw) > MAX_LIMIT) {
            throw new HttpError(400, "limit debe ser un entero de 1 a " + MAX_LIMIT);
        }
        return Integer.parseInt(raw);
    }

    /** La entrada como la ve el cliente (sin el {@code _id} interno de MongoDB). */
    static Map<String, Object> view(Document doc) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", doc.get("version"));
        view.put("title", doc.get("title"));
        view.put("changed", doc.get("changed"));
        view.put("by", doc.get("by"));
        Object at = doc.get("at");
        view.put("at", at instanceof Date date ? date.toInstant().toString() : at);
        return view;
    }
}
