package com.safemode.collab;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link PresenceStore} sobre Redis con Jedis. El tiempo de espera es corto (por defecto 300 ms): con Redis detenido la
 * peticion debe degradarse en milisegundos, no esperar varios segundos. Solo se traducen las excepciones de Jedis;
 * cualquier otro error es un error de programacion y se deja subir.
 */
public class RedisPresenceStore implements PresenceStore, AutoCloseable {

    private final JedisPool pool;

    public RedisPresenceStore(String host, int port, int timeoutMs) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        config.setMaxWait(java.time.Duration.ofMillis(timeoutMs));
        // PING al tomar una conexion del pool: si Redis se reinicio, las conexiones que el pool guardaba estan muertas y la
        // primera operacion tras la recuperacion fallaria (un heartbeat "degradado" sin motivo, y una cache que no se llena).
        // Medido en el experimento de Redis detenido: sin esto, el primer uso despues de reiniciar fallaba.
        config.setTestOnBorrow(true);
        config.setTestWhileIdle(true);
        this.pool = new JedisPool(config, host, port, timeoutMs);
    }

    @Override
    public void setWithTtl(String key, String value, int ttlSeconds) {
        run(jedis -> jedis.set(key, value, SetParams.setParams().ex(ttlSeconds)));
    }

    @Override
    public void set(String key, String value) {
        run(jedis -> jedis.set(key, value));
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(run(jedis -> jedis.get(key)));
    }

    @Override
    public List<String> keys(String pattern) {
        return run(jedis -> {
            List<String> found = new ArrayList<>();
            ScanParams params = new ScanParams().match(pattern).count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> page = jedis.scan(cursor, params);
                found.addAll(page.getResult());
                cursor = page.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
            return found;
        });
    }

    @Override
    public void deleteByPattern(String pattern) {
        List<String> found = keys(pattern);
        if (!found.isEmpty()) {
            run(jedis -> jedis.del(found.toArray(new String[0])));
        }
    }

    @Override
    public boolean ping() {
        try {
            return "PONG".equalsIgnoreCase(run(Jedis::ping));
        } catch (StoreUnavailableException e) {
            return false;
        }
    }

    private <T> T run(java.util.function.Function<Jedis, T> command) {
        try (Jedis jedis = pool.getResource()) {
            return command.apply(jedis);
        } catch (JedisException e) {
            throw new StoreUnavailableException("Redis no disponible", e);
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
