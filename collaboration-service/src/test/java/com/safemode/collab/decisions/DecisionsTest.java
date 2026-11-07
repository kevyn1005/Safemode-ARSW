package com.safemode.collab.decisions;

import com.safemode.collab.Authority;
import com.safemode.collab.AuthorityUnavailableException;
import com.safemode.collab.PresenceStore;
import com.safemode.collab.StoreUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Las decisiones 2 y 3 son logica pura (la 1 exige MongoDB y se comprueba en el acceptance de Compose). */
class DecisionsTest {

    /** Almacen falso que registra lo que se le pide y puede simular que Redis no responde. */
    static class FakeStore implements PresenceStore {
        final List<String> calls = new ArrayList<>();
        boolean down;

        private void check() {
            if (down) {
                throw new StoreUnavailableException("Redis no disponible", null);
            }
        }

        @Override
        public void setWithTtl(String key, String value, int ttlSeconds) {
            check();
            calls.add("SET " + key + " " + value + " EX " + ttlSeconds);
        }

        @Override
        public void set(String key, String value) {
            check();
            calls.add("SET " + key + " " + value);
        }

        @Override
        public Optional<String> get(String key) {
            check();
            return Optional.empty();
        }

        @Override
        public List<String> keys(String pattern) {
            check();
            return List.of();
        }

        @Override
        public void deleteByPattern(String pattern) {
            check();
        }

        @Override
        public boolean ping() {
            return !down;
        }
    }

    private static Authority answering(boolean value) {
        return (operator, puesto) -> value;
    }

    private static final Authority UNAVAILABLE = (operator, puesto) -> {
        throw new AuthorityUnavailableException("Gestion no disponible", null);
    };

    // ---- TODO 2: autoridad frente a cache ----

    @Test
    void safeIgnoresAStaleCacheAndAsksTheAuthority() {
        Decisions safe = new SafeDecisions();
        // la cache dice true (vieja) pero la autoridad ya revoco: se rechaza -> el servicio responde 403
        assertFalse(safe.permissionForWrite("luis", "puesto-1", true, answering(false)));
        // y al reves: una cache que dice false no puede negar lo que la autoridad concede
        assertTrue(safe.permissionForWrite("luis", "puesto-1", false, answering(true)));
        assertFalse(safe.usesPermissionCacheForWrites(), "las escrituras no deben ni leer la cache");
    }

    @Test
    void safeFailsClosedWhenTheAuthorityIsUnavailable() {
        Decisions safe = new SafeDecisions();
        // aunque la cache diga true, sin poder verificar NO se concede: la excepcion sube y el servicio responde 503
        assertThrows(AuthorityUnavailableException.class, () -> safe.permissionForWrite("luis", "puesto-1", true, UNAVAILABLE));
    }

    @Test
    void baselineTrustsTheStaleCacheWhichIsTheDefect() {
        Decisions base = new BaselineDecisions();
        assertTrue(base.permissionForWrite("luis", "puesto-1", true, answering(false)), "defecto: la cache vieja deja escribir");
        assertTrue(base.permissionForWrite("luis", "puesto-1", true, UNAVAILABLE), "defecto: la cache oculta la falla de Gestion");
        assertFalse(base.permissionForWrite("luis", "puesto-1", null, answering(false)), "sin cache pregunta a la autoridad");
        assertTrue(base.usesPermissionCacheForWrites());
    }

    // ---- TODO 3: presencia con vencimiento ----

    @Test
    void safeRegistersValueAndExpiryInOneWrite() {
        FakeStore store = new FakeStore();
        assertTrue(new SafeDecisions().touchPresence(store, "presence:puesto-1:ana", 20));
        // UNA sola escritura con vencimiento (SET ... EX 20), nunca SET + EXPIRE por separado
        assertEquals(List.of("SET presence:puesto-1:ana online EX 20"), store.calls);
    }

    @Test
    void safeDegradesInsteadOfFailingWhenRedisIsDown() {
        FakeStore store = new FakeStore();
        store.down = true;
        assertFalse(new SafeDecisions().touchPresence(store, "presence:puesto-1:ana", 20),
                "no debe decir que hay presencia si no se pudo registrar");
    }

    @Test
    void safeDoesNotSwallowProgrammingErrors() {
        PresenceStore broken = new FakeStore() {
            @Override
            public void setWithTtl(String key, String value, int ttlSeconds) {
                throw new IllegalStateException("bug");
            }
        };
        // solo se atrapa la falla del almacen; un error de codigo debe verse, no esconderse como "degradado"
        assertThrows(IllegalStateException.class, () -> new SafeDecisions().touchPresence(broken, "k", 20));
    }

    @Test
    void baselineHasNoExpiryAndDoesNotCatchTheFailure() {
        FakeStore store = new FakeStore();
        assertTrue(new BaselineDecisions().touchPresence(store, "presence:puesto-1:ana", 20));
        assertEquals(List.of("SET presence:puesto-1:ana online"), store.calls, "defecto: sin EX, la clave no vence");

        store.down = true;
        assertThrows(StoreUnavailableException.class, () -> new BaselineDecisions().touchPresence(store, "k", 20));
    }

    // ---- seleccion de la implementacion ----

    @Test
    void modeSelectsTheImplementation() {
        assertInstanceOf(SafeDecisions.class, Decisions.forMode(null));
        assertInstanceOf(SafeDecisions.class, Decisions.forMode(""));
        assertInstanceOf(SafeDecisions.class, Decisions.forMode("solucion"));
        assertInstanceOf(BaselineDecisions.class, Decisions.forMode("BASE"));
        assertThrows(IllegalArgumentException.class, () -> Decisions.forMode("otra"));
    }
}
