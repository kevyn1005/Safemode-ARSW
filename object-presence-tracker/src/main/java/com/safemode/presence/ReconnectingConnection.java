package com.safemode.presence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * La conexion JDBC de {@link PresenceEventStore} y del AlertStore, que se vuelve a abrir sola si se pierde.
 *
 * <p>Antes cada almacen guardaba UNA conexion y, si la base se reiniciaba (el contenedor de PostgreSQL o el servidor H2 del
 * compose), esa conexion quedaba muerta y el gateway fallaba hasta que alguien lo reiniciaba (limite documentado en el
 * ADR 0005). Ahora cada operacion pide la conexion con {@link #get()}: si la actual sigue valida se usa tal cual; si no, se
 * cierra y se abre otra con la misma URL.
 *
 * <p>Decisiones:
 * <ul>
 *   <li><b>Se valida antes de usar</b> ({@code Connection.isValid}) en vez de reintentar tras un error: asi ninguna
 *       operacion se ejecuta a medias sobre una conexion muerta y no hay que envolver cada consulta en un reintento. El costo
 *       es una ida y vuelta corta por operacion (en H2 embebido es instantaneo).</li>
 *   <li><b>Despues de un intento fallido se espera {@value #RETRY_DELAY_MILLIS} ms</b> antes del siguiente: el motor de
 *       alertas consulta cada 250 ms y, con la base caida, cada consulta pagaria el intento de conexion. Mientras dura la
 *       espera, {@link #get()} falla al instante con el mismo error.</li>
 *   <li>Con PostgreSQL se limita el tiempo de conexion a {@value #PG_CONNECT_TIMEOUT_SECONDS} s si la URL no lo trae (si el
 *       servidor no responde, el driver esperaria sin limite y el ciclo de fotogramas del tracker se quedaria colgado).</li>
 *   <li>Se registra una sola linea al perder la base y otra al recuperarla (no una por consulta fallida).</li>
 *   <li>No guarda estado de la base: el esquema y los datos estan en el servidor, asi que la conexion nueva ve lo mismo.
 *       Las transacciones abiertas en la conexion muerta se pierden (los almacenes usan autocommit: no hay ninguna).</li>
 * </ul>
 */
public final class ReconnectingConnection implements AutoCloseable {

    /** Tiempo minimo entre dos intentos de reconectar (despues de uno fallido). */
    static final long RETRY_DELAY_MILLIS = 1000;
    static final int PG_CONNECT_TIMEOUT_SECONDS = 3;
    private static final int VALID_TIMEOUT_SECONDS = 2;

    private final String jdbcUrl;
    private final Properties properties = new Properties();
    private final long retryDelayNanos;
    private Connection current;
    private boolean closed;
    private boolean down;
    private SQLException lastFailure;
    private long nextAttemptAtNanos;

    public ReconnectingConnection(String jdbcUrl) throws SQLException {
        this(jdbcUrl, RETRY_DELAY_MILLIS);
    }

    /** Con la espera entre intentos indicada (las pruebas la acortan). */
    ReconnectingConnection(String jdbcUrl, long retryDelayMillis) throws SQLException {
        this.jdbcUrl = jdbcUrl;
        this.retryDelayNanos = retryDelayMillis * 1_000_000L;
        // Solo PostgreSQL: H2 rechaza las propiedades que no conoce, y la suya (la URL) ya trae todo lo que necesita.
        if (jdbcUrl.startsWith("jdbc:postgresql:") && !jdbcUrl.contains("connectTimeout")) {
            properties.setProperty("connectTimeout", Integer.toString(PG_CONNECT_TIMEOUT_SECONDS));
        }
        this.current = open();
    }

    /** La conexion lista para usar: la misma de siempre si sigue valida; si no, una nueva (o {@link SQLException}). */
    public synchronized Connection get() throws SQLException {
        if (closed) {
            throw new SQLException("La conexion a la base de datos ya se cerro");
        }
        if (isUsable(current)) {
            return current;
        }
        if (!down) {
            down = true;
            System.err.println("[BD] Se perdio la conexion con la base de datos; se intentara reconectar sola");
        } else if (lastFailure != null && System.nanoTime() - nextAttemptAtNanos < 0) {
            throw lastFailure; // espera entre intentos: no se vuelve a pagar el intento de conexion
        }
        closeQuietly(current);
        try {
            current = open();
        } catch (SQLException e) {
            lastFailure = e;
            nextAttemptAtNanos = System.nanoTime() + retryDelayNanos;
            throw e;
        }
        down = false;
        lastFailure = null;
        System.err.println("[BD] Reconectado a la base de datos");
        return current;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    private static boolean isUsable(Connection connection) {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(VALID_TIMEOUT_SECONDS);
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            // la conexion ya estaba muerta: no hay nada mas que hacer con ella
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        closed = true;
        if (current != null) {
            current.close();
        }
    }
}
