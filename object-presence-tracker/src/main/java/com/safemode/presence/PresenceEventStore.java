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
 *
 * Cada evento puede llevar tambien la ruta (frame_path) de una foto PNG
 * guardada en disco en el momento exacto del evento (ver
 * ObjectTracker#saveFrameSnapshot): sirve como evidencia visual para el
 * centro de alertas ("en la camara 1 se retiro la maleta" + foto).
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
                    person_nearby BOOLEAN,
                    frame_path VARCHAR(500)
                )
                """);
            // Bases creadas antes de existir frame_path: CREATE TABLE IF NOT EXISTS no las toca.
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS frame_path VARCHAR(500)");
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS owner_person_id BIGINT");
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS owner_description VARCHAR(200)");
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS owner_crop_path VARCHAR(500)");
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS owner_ai_description VARCHAR(1000)");
        }
    }

    /**
     * Dueno probable de un objeto: la persona que estuvo mas tiempo cerca de el
     * mientras aparecia y se asentaba. Todos los campos pueden ser null (ej. sin
     * imagen no hay recorte ni descripcion).
     */
    public record OwnerInfo(Long personId, String description, String cropPath) {}

    /** Guarda el evento y devuelve el id de la fila. */
    public long recordRegistered(long trackedId, String className, int x, int y, int w, int h, Instant when, String framePath, OwnerInfo owner) {
        return insert(trackedId, className, "REGISTERED_AT_REST", x, y, w, h, when, null, framePath, owner);
    }

    /** Guarda el evento y devuelve el id de la fila. */
    public long recordRemoved(long trackedId, String className, int x, int y, int w, int h, Instant when, boolean personNearby, String framePath, OwnerInfo owner) {
        return insert(trackedId, className, "REMOVED", x, y, w, h, when, personNearby, framePath, owner);
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

    /** Da la ruta de la imagen (frame_path) del ultimo evento de ese tipo (usado en las pruebas). */
    String lastFramePath(String eventType) {
        String sql = "SELECT frame_path FROM object_presence_event WHERE event_type = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventType);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar object_presence_event", e);
        }
    }

    /** Da el id del objeto (tracked_object_id) del ultimo evento de ese tipo, o -1 si no hay (usado en las pruebas). */
    long lastTrackedObjectId(String eventType) {
        String sql = "SELECT tracked_object_id FROM object_presence_event WHERE event_type = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventType);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar object_presence_event", e);
        }
    }

    /** Da el dueno guardado en el ultimo evento de ese tipo, o null si no tenia dueno (usado en las pruebas). */
    OwnerInfo lastOwner(String eventType) {
        String sql = "SELECT owner_person_id, owner_description, owner_crop_path FROM object_presence_event "
                + "WHERE event_type = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventType);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long personId = rs.getLong(1);
                Long id = rs.wasNull() ? null : personId;
                String description = rs.getString(2);
                String cropPath = rs.getString(3);
                if (id == null && description == null && cropPath == null) {
                    return null;
                }
                return new OwnerInfo(id, description, cropPath);
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

    private long insert(long trackedId, String className, String eventType,
                         int x, int y, int w, int h, Instant when, Boolean personNearby, String framePath, OwnerInfo owner) {
        String sql = """
            INSERT INTO object_presence_event
                (tracked_object_id, class_name, event_type, occurred_at, pos_x, pos_y, width, height, person_nearby, frame_path,
                 owner_person_id, owner_description, owner_crop_path)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        long eventId;
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
            ps.setString(10, framePath);
            if (owner == null || owner.personId() == null) {
                ps.setNull(11, Types.BIGINT);
            } else {
                ps.setLong(11, owner.personId());
            }
            ps.setString(12, owner == null ? null : owner.description());
            ps.setString(13, owner == null ? null : owner.cropPath());
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                keys.next();
                eventId = keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar el evento de presencia", e);
        }

        String label = "REGISTERED_AT_REST".equals(eventType) ? "registrado en reposo" : "retirado";
        String personInfo = personNearby != null ? " - persona cerca: " + personNearby : "";
        String frameInfo = framePath != null ? " - foto: " + framePath : "";
        String ownerInfo = owner != null && owner.personId() != null
                ? " - dueño: persona #" + owner.personId()
                        + (owner.description() != null ? " (" + owner.description() + ")" : "")
                : "";
        System.out.println("[" + when + "] Objeto #" + trackedId + " (" + className + ") " + label + personInfo + frameInfo + ownerInfo);
        return eventId;
    }

    /**
     * Guarda la descripcion del dueno generada por un modelo de vision (llega de forma
     * asincrona, unos segundos despues de insertar el evento). Se identifica la fila por su
     * id porque tracked_object_id se reinicia en cada ejecucion del programa.
     */
    public void updateOwnerAiDescription(long eventId, String aiDescription) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE object_presence_event SET owner_ai_description = ? WHERE id = ?")) {
            ps.setString(1, aiDescription);
            ps.setLong(2, eventId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar la descripcion del dueno", e);
        }
    }

    /**
     * Borra todos los eventos y reinicia los ids desde 1. Solo para el script de prueba manual,
     * que empieza cada corrida desde cero; el seguimiento real nunca borra historial.
     * Devuelve cuantas filas habia.
     */
    public int clearAllEvents() {
        try (Statement st = connection.createStatement()) {
            int rows;
            try (var rs = st.executeQuery("SELECT COUNT(*) FROM object_presence_event")) {
                rs.next();
                rows = rs.getInt(1);
            }
            st.execute("TRUNCATE TABLE object_presence_event RESTART IDENTITY");
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo vaciar object_presence_event", e);
        }
    }

    /** Da la descripcion por IA del ultimo evento de ese tipo, o null (usado en las pruebas). */
    String lastOwnerAiDescription(String eventType) {
        String sql = "SELECT owner_ai_description FROM object_presence_event WHERE event_type = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventType);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar object_presence_event", e);
        }
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
