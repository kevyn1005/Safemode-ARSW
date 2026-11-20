package com.safemode.gateway;

import com.safemode.presence.PresenceEventStore;
import com.safemode.presence.ReconnectingConnection;
import com.safemode.presence.SqlDialect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Sesiones de los operadores en la base compartida (H2 o PostgreSQL), para correr varias replicas del gateway.
 *
 * <p>Seguridad: la base guarda la huella SHA-256 del identificador de sesion, no el identificador (que es el valor de la
 * cookie): quien lea la tabla no puede usarla para entrar. Si la base no responde, "no hay sesion" (falla cerrado); la
 * conexion se reabre sola cuando la base vuelve. Nunca se escribe el identificador en un mensaje de error.
 */
final class JdbcSessionStore implements SessionStore {

    private static final int MAX_NAME = 255;

    private final ReconnectingConnection connection;

    private JdbcSessionStore(ReconnectingConnection connection) {
        this.connection = connection;
        try (Statement st = connection.get().createStatement()) {
            SqlDialect dialect = SqlDialect.of(connection.get());
            st.execute("""
                    CREATE TABLE IF NOT EXISTS operator_session (
                        id_hash VARCHAR(64) PRIMARY KEY,
                        email VARCHAR(320) NOT NULL,
                        display_name VARCHAR(255),
                        expires_at %s NOT NULL
                    )
                    """.formatted(dialect.timestampType()));
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo crear la tabla operator_session", e);
        }
    }

    static JdbcSessionStore forUrl(String jdbcUrl) {
        try {
            return new JdbcSessionStore(new ReconnectingConnection(jdbcUrl));
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo abrir la base de datos de sesiones", e);
        }
    }

    /** La base de SAFEMODE_DB_URL (la misma que usan los demas almacenes), o el archivo local si no esta definida. */
    static JdbcSessionStore fromEnvironment(String defaultFilePath) {
        return forUrl(PresenceEventStore.jdbcUrlFor(System.getenv(PresenceEventStore.DB_URL_ENV), defaultFilePath));
    }

    static String hash(String id) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    @Override
    public void put(String id, AuthService.User user, Instant expires) {
        String name = user.name() == null ? null : user.name().substring(0, Math.min(user.name().length(), MAX_NAME));
        try (PreparedStatement ps = connection.get().prepareStatement(
                "INSERT INTO operator_session (id_hash, email, display_name, expires_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, hash(id));
            ps.setString(2, user.email());
            ps.setString(3, name);
            ps.setTimestamp(4, Timestamp.from(expires));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar la sesion (SQLState " + e.getSQLState() + ")");
        }
    }

    @Override
    public Optional<AuthService.User> find(String id, Instant now) {
        try (PreparedStatement ps = connection.get().prepareStatement(
                "SELECT email, display_name, expires_at FROM operator_session WHERE id_hash = ?")) {
            ps.setString(1, hash(id));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                if (rs.getTimestamp(3).toInstant().isBefore(now)) {
                    remove(id);
                    return Optional.empty();
                }
                return Optional.of(new AuthService.User(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            System.err.println("[AUTH] No se pudo consultar la sesion; se trata como no iniciada (SQLState " + e.getSQLState() + ")");
            return Optional.empty();
        }
    }

    @Override
    public void remove(String id) {
        try (PreparedStatement ps = connection.get().prepareStatement("DELETE FROM operator_session WHERE id_hash = ?")) {
            ps.setString(1, hash(id));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[AUTH] No se pudo borrar la sesion (SQLState " + e.getSQLState() + ")");
        }
    }

    @Override
    public void purgeExpired(Instant now) {
        try (PreparedStatement ps = connection.get().prepareStatement("DELETE FROM operator_session WHERE expires_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[AUTH] No se pudieron limpiar las sesiones vencidas (SQLState " + e.getSQLState() + ")");
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("Error cerrando la base de datos de sesiones: " + e.getMessage());
        }
    }
}
