package com.safemode.collab.decisions;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.safemode.collab.Authority;
import com.safemode.collab.PresenceStore;
import com.safemode.collab.StoreUnavailableException;
import org.bson.Document;
import org.bson.conversions.Bson;

/** La solucion: cada decision protege la garantia que le toca. Es la implementacion por defecto. */
public class SafeDecisions implements Decisions {

    @Override
    public String mode() {
        return "solucion";
    }

    /**
     * La version esperada va en el FILTRO de la misma operacion. MongoDB evalua filtro + {@code $set} + {@code $inc}
     * de forma atomica sobre un documento: de dos escritores con la misma version solo uno coincide; el otro recibe
     * {@code null} y el servicio responde 409. Un {@code find} seguido de un {@code update} sin condicion dejaria una
     * ventana entre la lectura y la escritura, y esa ventana es exactamente la edicion perdida.
     */
    @Override
    public Document savePuesto(MongoCollection<Document> collection, String puestoId, long expectedVersion, Bson changes) {
        return collection.findOneAndUpdate(
                Filters.and(Filters.eq("_id", puestoId), Filters.eq("version", expectedVersion)),
                Updates.combine(changes, Updates.inc("version", 1L)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    @Override
    public boolean usesPermissionCacheForWrites() {
        return false;
    }

    /**
     * Una escritura es una operacion critica: la cache solo sirve para pintar la pantalla. Se pregunta SIEMPRE a la
     * autoridad. Revocacion confirmada antes de empezar la escritura: la autoridad dice {@code false} y el servicio
     * responde 403. Si la autoridad no responde, {@code AuthorityUnavailableException} sube sin atraparse: el servicio
     * responde 503 y no guarda, es decir, una falla nunca se convierte en una concesion. {@code cachedPermission} se
     * recibe por contrato y no se usa a proposito.
     *
     * <p>Costo: una llamada HTTP a Gestion (y una lectura por clave primaria en PostgreSQL) por cada escritura.
     * <p>Limite: no cubre una revocacion que ocurre DESPUES de esta validacion y ANTES de guardar, porque Gestion y
     * Contenido no comparten una transaccion (ver ADR 0005).
     */
    @Override
    public boolean permissionForWrite(String operator, String puestoId, Boolean cachedPermission, Authority authority) {
        return authority.canConfigure(operator, puestoId);
    }

    /**
     * Valor y vencimiento en UNA sola escritura: con {@code SET} y {@code EXPIRE} separados, una falla entre las dos dejaria
     * una clave eterna. Cada heartbeat reescribe la clave y renueva el vencimiento. Solo se atrapa la falla del
     * almacen ({@link StoreUnavailableException}), no los errores de programacion: si Redis no responde se devuelve
     * {@code false} y el servicio responde 200 con {@code available:false} y cuenta la degradacion.
     */
    @Override
    public boolean touchPresence(PresenceStore store, String key, int ttlSeconds) {
        try {
            store.setWithTtl(key, "online", ttlSeconds);
            return true;
        } catch (StoreUnavailableException e) {
            return false;
        }
    }
}
