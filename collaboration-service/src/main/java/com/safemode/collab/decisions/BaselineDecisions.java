package com.safemode.collab.decisions;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.safemode.collab.Authority;
import com.safemode.collab.PresenceStore;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * EL PUNTO DE PARTIDA, CON SUS DEFECTOS A PROPOSITO. Reproduce lo que el laboratorio entrega antes de corregir, para
 * poder repetir la medicion del "antes" cuando se quiera (evidencias antes/despues). NO usar fuera del laboratorio: se
 * activa solo con {@code DECISIONS_MODE=base} y el servicio lo anuncia en su registro al arrancar.
 * <ul>
 *   <li>Edicion perdida: ignora la version, asi dos escritores con la misma version guardan los dos (200 y 200).</li>
 *   <li>Permiso revocado: si hay una copia en cache la cree, asi quien ya no tiene permiso sigue escribiendo hasta
 *       que vence la cache (60 s).</li>
 *   <li>Presencia eterna: escribe sin vencimiento y no atrapa la falla de Redis (con Redis caido responde 503).</li>
 * </ul>
 */
public class BaselineDecisions implements Decisions {

    @Override
    public String mode() {
        return "base";
    }

    @Override
    public Document savePuesto(MongoCollection<Document> collection, String puestoId, long expectedVersion, Bson changes) {
        // DEFECTO: lee y despues escribe SIN condicion de version; entre las dos operaciones otro escritor puede guardar
        Document current = collection.find(Filters.eq("_id", puestoId)).first();
        if (current == null) {
            return null;
        }
        return collection.findOneAndUpdate(Filters.eq("_id", puestoId),
                Updates.combine(changes, Updates.inc("version", 1L)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    @Override
    public boolean usesPermissionCacheForWrites() {
        return true;
    }

    @Override
    public boolean permissionForWrite(String operator, String puestoId, Boolean cachedPermission, Authority authority) {
        // DEFECTO: una copia vieja decide una escritura
        return cachedPermission != null ? cachedPermission : authority.canConfigure(operator, puestoId);
    }

    @Override
    public boolean touchPresence(PresenceStore store, String key, int ttlSeconds) {
        // DEFECTO: sin vencimiento (la clave nunca expira) y sin atrapar la falla del almacen
        store.set(key, "online");
        return true;
    }
}
