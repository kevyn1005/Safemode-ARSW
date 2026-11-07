package com.safemode.collab;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * La configuracion compartida de un puesto de vigilancia: UN documento en MongoDB con el titulo, las camaras con su zona
 * y las preferencias de avisos EMBEBIDAS, mas la version. Se lee y se guarda como una unidad, y una operacion sobre un
 * solo documento es atomica: esa es la frontera de atomicidad que usa la version optimista.
 */
public class PuestoStore implements AutoCloseable {

    static final String SEED_ID = "puesto-1";

    private final MongoClient client;
    private final MongoCollection<Document> collection;

    public PuestoStore(String uri, String database, int timeoutMs) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                // con MongoDB caido, fallar en ~timeoutMs y responder 503; el valor por defecto del driver es 30 s
                .applyToClusterSettings(b -> b.serverSelectionTimeout(timeoutMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS))
                .build();
        this.client = MongoClients.create(settings);
        this.collection = client.getDatabase(database).getCollection("puestos");
    }

    /** La coleccion, para que {@link com.safemode.collab.decisions.Decisions} haga la operacion atomica. */
    public MongoCollection<Document> collection() {
        return collection;
    }

    public Optional<Document> find(String id) {
        return Optional.ofNullable(collection.find(Filters.eq("_id", id)).first());
    }

    /**
     * Crea el documento ficticio SOLO si no existe ({@code $setOnInsert} en un upsert): un reinicio del servicio no debe
     * devolver la version a 1 ni borrar lo que ya se edito. Si dos arranques compiten, el que pierde recibe un error de
     * clave duplicada, que aqui significa "ya lo creo el otro".
     */
    public void seedIfAbsent() {
        List<Bson> onInsert = new ArrayList<>();
        seedDocument().forEach((key, value) -> {
            if (!key.equals("_id")) {
                onInsert.add(Updates.setOnInsert(key, value));
            }
        });
        try {
            collection.updateOne(Filters.eq("_id", SEED_ID), Updates.combine(onInsert), new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
        }
    }

    /** Vuelve al documento ficticio, version 1 (solo para repetir pruebas). */
    public void reset() {
        collection.replaceOne(Filters.eq("_id", SEED_ID), seedDocument(), new ReplaceOptions().upsert(true));
    }

    /** Sondeo de la metrica de dependencia. */
    public boolean ping() {
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (MongoException e) {
            return false;
        }
    }

    static Document seedDocument() {
        return new Document("_id", SEED_ID)
                .append("title", "Puesto principal")
                .append("cameras", List.of(
                        new Document("id", 1).append("zone", new Document("x", 0.05).append("y", 0.10).append("w", 0.60).append("h", 0.70)),
                        new Document("id", 2).append("zone", null)))
                .append("preferences", new Document("minSeverity", "MEDIUM").append("notifyBrowser", true)
                        .append("notifyTelegram", true).append("photoRetentionDays", 30))
                .append("version", 1L)
                .append("updatedBy", "seed")
                .append("updatedAt", Instant.now().toString());
    }

    @Override
    public void close() {
        client.close();
    }
}
