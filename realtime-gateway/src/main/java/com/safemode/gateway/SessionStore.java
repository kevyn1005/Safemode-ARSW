package com.safemode.gateway;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Donde viven las sesiones de los operadores. Por defecto en memoria de cada proceso (como siempre). Con varias replicas del
 * gateway detras de un balanceador se guardan en la base compartida ({@link JdbcSessionStore}), para que cualquiera
 * reconozca la cookie que emitio otra y para que cerrar la sesion valga en todas (docs/disponibilidad).
 */
interface SessionStore extends AutoCloseable {

    void put(String id, AuthService.User user, Instant expires);

    /** El usuario de la sesion, o vacio si no existe o ya vencio (en ese caso la borra). Nunca lanza: ante un fallo, "no hay sesion". */
    Optional<AuthService.User> find(String id, Instant now);

    void remove(String id);

    /** Borra las sesiones vencidas. */
    void purgeExpired(Instant now);

    @Override
    default void close() {
    }

    /** La de siempre: un mapa en memoria, que se pierde al reiniciar el proceso. */
    final class InMemory implements SessionStore {
        private record Entry(AuthService.User user, Instant expires) {
        }

        private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

        @Override
        public void put(String id, AuthService.User user, Instant expires) {
            sessions.put(id, new Entry(user, expires));
        }

        @Override
        public Optional<AuthService.User> find(String id, Instant now) {
            Entry entry = sessions.get(id);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.expires().isBefore(now)) {
                sessions.remove(id);
                return Optional.empty();
            }
            return Optional.of(entry.user());
        }

        @Override
        public void remove(String id) {
            sessions.remove(id);
        }

        @Override
        public void purgeExpired(Instant now) {
            sessions.values().removeIf(entry -> entry.expires().isBefore(now));
        }
    }
}
