package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Guarda las alertas en la tabla {@code alert} de H2. Puede vivir en la misma base que los eventos de presencia (mismo
 * archivo, AUTO_SERVER permite que el tracker y el alert-engine la abran a la vez desde procesos distintos).
 */
public class AlertStore implements AutoCloseable {

    private static final String COLUMNS = "id, event_id, event_occurred_at, alert_type, severity, class_name, message, "
            + "frame_path, created_at, acknowledged";

    private final Connection connection;

    private AlertStore(Connection connection) {
        this.connection = connection;
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS alert (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    event_id BIGINT NOT NULL,
                    event_occurred_at TIMESTAMP NOT NULL,
                    alert_type VARCHAR(40) NOT NULL,
                    severity VARCHAR(10) NOT NULL,
                    class_name VARCHAR(50) NOT NULL,
                    message VARCHAR(500) NOT NULL,
                    frame_path VARCHAR(500),
                    created_at TIMESTAMP NOT NULL,
                    acknowledged BOOLEAN DEFAULT FALSE NOT NULL,
                    UNIQUE (event_id, event_occurred_at)
                )
                """);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo crear la tabla alert", e);
        }
    }

    /** Abre (o crea) la base H2 en ese archivo; usar la misma ruta que PresenceEventStore para compartir la base. */
    public static AlertStore forFile(String dbFilePath) {
        return open("jdbc:h2:file:" + dbFilePath + ";AUTO_SERVER=TRUE");
    }

    /** Abre la base indicada por SAFEMODE_DB_URL (p. ej. el servicio H2 de docker-compose), o el archivo local si no esta definida. */
    public static AlertStore fromEnvironment(String defaultFilePath) {
        return open(PresenceEventStore.jdbcUrlFor(System.getenv(PresenceEventStore.DB_URL_ENV), defaultFilePath));
    }

    /** Base en memoria con ese nombre; PresenceEventStore.inMemory(mismoNombre) apunta a la misma base. */
    public static AlertStore inMemory(String name) {
        return open("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }

    private static AlertStore open(String url) {
        try {
            return new AlertStore(DriverManager.getConnection(url));
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo abrir la base de datos de alertas", e);
        }
    }

    /** Guarda la alerta salvo que ese evento ya tenga una; devuelve la alerta guardada, o vacio si ya existia. */
    public Optional<Alert> insertIfAbsent(long eventId, Instant eventOccurredAt, AlertType type, Severity severity,
                                          String className, String message, String framePath, Instant createdAt) {
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM alert WHERE event_id = ? AND event_occurred_at = ?")) {
                ps.setLong(1, eventId);
                ps.setTimestamp(2, Timestamp.from(eventOccurredAt));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return Optional.empty();
                    }
                }
            }
            String sql = "INSERT INTO alert (event_id, event_occurred_at, alert_type, severity, class_name, message, "
                    + "frame_path, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, eventId);
                ps.setTimestamp(2, Timestamp.from(eventOccurredAt));
                ps.setString(3, type.name());
                ps.setString(4, severity.name());
                ps.setString(5, className);
                ps.setString(6, message);
                ps.setString(7, framePath);
                ps.setTimestamp(8, Timestamp.from(createdAt));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return findById(keys.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar la alerta", e);
        }
    }

    public Optional<Alert> findById(long id) {
        return query("WHERE id = ?", id).stream().findFirst();
    }

    /** Todas las alertas, de la mas nueva a la mas vieja. */
    public List<Alert> listAll() {
        return query("", null);
    }

    /** Las alertas que nadie ha revisado todavia, de la mas nueva a la mas vieja. */
    public List<Alert> listPending() {
        return query("WHERE acknowledged = FALSE", null);
    }

    /** Marca la alerta como revisada; devuelve false si no existe. */
    public boolean acknowledge(long id) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE alert SET acknowledged = TRUE WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo marcar la alerta como revisada", e);
        }
    }

    private List<Alert> query(String where, Long param) {
        String sql = "SELECT " + COLUMNS + " FROM alert " + where + " ORDER BY id DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (param != null) {
                ps.setLong(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Alert> alerts = new ArrayList<>();
                while (rs.next()) {
                    alerts.add(new Alert(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(),
                            AlertType.valueOf(rs.getString(4)), Severity.valueOf(rs.getString(5)), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getBoolean(10)));
                }
                return alerts;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la tabla alert", e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("Error cerrando la base de datos de alertas: " + e.getMessage());
        }
    }
}
