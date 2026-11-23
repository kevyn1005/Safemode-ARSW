package com.safemode.collab.decisions;

import com.mongodb.client.MongoCollection;
import com.safemode.collab.Authority;
import com.safemode.collab.PresenceStore;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * Las TRES decisiones del laboratorio, aisladas en un solo lugar (el equivalente de {@code decisions.py} del
 * enunciado). Todo lo demas del servicio (rutas, validacion, metricas) es infraestructura; aqui esta lo que se defiende:
 * <ol>
 *   <li>como se evita una edicion perdida ({@link #savePuesto});</li>
 *   <li>de donde sale la autoridad para ESCRIBIR frente a una cache ({@link #permissionForWrite});</li>
 *   <li>como se registra una presencia que expira y se degrada sin Redis ({@link #touchPresence}).</li>
 * </ol>
 * Hay dos implementaciones: {@link SafeDecisions} (la solucion, por defecto) y {@link BaselineDecisions} (los defectos
 * del punto de partida, conservados a proposito para reproducir el "antes"). Se elige con {@code DECISIONS_MODE}.
 */
public interface Decisions {

    /** {@code solucion} o {@code base}. */
    String mode();

    /**
     * TODO 1 · Guarda los cambios y devuelve el documento nuevo, o {@code null} si la version no coincide (el servicio lo
     * traduce a 409).
     *
     * @param changes los {@code $set} del cambio; la implementacion agrega la condicion de version y el incremento
     */
    Document savePuesto(MongoCollection<Document> collection, String puestoId, long expectedVersion, Bson changes);

    /** Si {@link #permissionForWrite} consulta la cache: cuando es {@code false}, el servicio ni siquiera la lee. */
    boolean usesPermissionCacheForWrites();

    /**
     * TODO 2 · Decide si una escritura que empieza AHORA tiene permiso.
     *
     * @param cachedPermission la copia en cache ({@code null} si no hay o Redis no responde); solo sirve para la pantalla
     * @param authority        la fuente valida (Gestion de acceso); lanza {@code AuthorityUnavailableException} si no responde
     */
    boolean permissionForWrite(String operator, String puestoId, Boolean cachedPermission, Authority authority);

    /**
     * TODO 3 · Registra la presencia con vencimiento.
     *
     * @return {@code true} si quedo registrada; {@code false} si no se pudo (Redis caido) y la funcion se degrada
     */
    boolean touchPresence(PresenceStore store, String key, int ttlSeconds);

    /** La implementacion pedida por {@code DECISIONS_MODE} (por defecto {@code solucion}). */
    static Decisions forMode(String mode) {
        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("solucion")) {
            return new SafeDecisions();
        }
        if (mode.equalsIgnoreCase("base")) {
            return new BaselineDecisions();
        }
        throw new IllegalArgumentException("DECISIONS_MODE debe ser 'solucion' o 'base' y es: " + mode);
    }
}
