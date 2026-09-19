package com.safemode.presence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

/**
 * Persistencia de los eventos "objeto registrado en reposo" y "objeto
 * retirado de la escena".
 *
 * Usa H2 embebido en un archivo local: es persistencia real (sobrevive un
 * reinicio, se puede consultar con SQL) sin necesidad de levantar un
 * servidor de base de datos aparte. El "Alert DB" en la nube que aparece
 * en el diagrama de arquitectura es un componente distinto, del Alert
 * Engine (llega mas adelante en el release plan); este store no lo
 * reemplaza ni depende de el.
 */
public class PresenceEventStore implements AutoCloseable {

    private final Connection connection;

    public PresenceEventStore(String dbFilePath) {
        try {
            connection = DriverManager.getConnection("jdbc:h2:file:" + dbFilePath + ";AUTO_SERVER=TRUE");
            createSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo abrir la base de datos H2 en " + dbFilePath, e);
        }
    }

    /** Constructor para pruebas: base de datos H2 en memoria, aislada por nombre. */
    static PresenceEventStore inMemory(String name) {
        try {
            Connection conn = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
            return new PresenceEventStore(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo abrir la base de datos H2 en memoria", e);
        }
    }

    private PresenceEventStore(Connection connection) {
        this.connection = connection;
        try {
            createSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo crear el esquema", e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS object_presence_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tracked_object_id BIGINT NOT NULL,
                    class_name VARCHAR(50) NOT NULL,
                    event_type VARCHAR(20) NOT NULL,
                    occurred_at TIMESTAMP NOT NULL,
                    pos_x INT NOT NULL,
                    pos_y INT NOT NULL,
                    width INT NOT NULL,
                    height INT NOT NULL,
                    person_nearby BOOLEAN
                )
                """);
        }
    }

    public void recordRegistered(long trackedId, String className, int x, int y, int w, int h, Instant when) {
        insert(trackedId, className, "REGISTERED_AT_REST", x, y, w, h, when, null);
    }

    public void recordRemoved(long trackedId, String className, int x, int y, int w, int h, Instant when, boolean personNearby) {
        insert(trackedId, className, "REMOVED", x, y, w, h, when, personNearby);
    }

    /** Da el valor de person_nearby del ultimo evento de ese tipo (usado en las pruebas). */
    Boolean lastPersonNearby(String eventType) {
        String sql = "SELECT person_nearby FROM object_presence_event WHERE event_type = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventType);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                boolean val = rs.getBoolean(1);
                return rs.wasNull() ? null : val;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar object_presence_event", e);
        }
    }

    /** Cuenta los eventos guardados de un tipo dado (usado en las pruebas). */
    int countEvents(String eventType) {
        String sql = "SELECT COUNT(*) FROM object_presence_event WHERE event_type = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventType);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar object_presence_event", e);
        }
    }

    private void insert(long trackedId, String className, String eventType,
                         int x, int y, int w, int h, Instant when, Boolean personNearby) {
        String sql = """
            INSERT INTO object_presence_event
                (tracked_object_id, class_name, event_type, occurred_at, pos_x, pos_y, width, height, person_nearby)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, trackedId);
            ps.setString(2, className);
            ps.setString(3, eventType);
            ps.setTimestamp(4, Timestamp.from(when));
            ps.setInt(5, x);
            ps.setInt(6, y);
            ps.setInt(7, w);
            ps.setInt(8, h);
            if (personNearby == null) {
                ps.setNull(9, Types.BOOLEAN);
            } else {
                ps.setBoolean(9, personNearby);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar el evento de presencia", e);
        }

        String label = "REGISTERED_AT_REST".equals(eventType) ? "registrado en reposo" : "retirado";
        String personInfo = personNearby != null ? " - persona cerca: " + personNearby : "";
        System.out.println("[" + when + "] Objeto #" + trackedId + " (" + className + ") " + label + personInfo);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("Error cerrando la base de datos: " + e.getMessage());
        }
    }
}
