package com.safemode.presence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** Variable de entorno con la URL JDBC de la base (por ejemplo la del servicio H2 de docker-compose). */
    public static final String DB_URL_ENV = "SAFEMODE_DB_URL";

    private final Connection connection;

    public PresenceEventStore(String dbFilePath) {
        try {
            connection = DriverManager.getConnection("jdbc:h2:file:" + dbFilePath + ";AUTO_SERVER=TRUE");
            createSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo abrir la base de datos H2 en " + dbFilePath, e);
        }
    }

    /**
     * URL JDBC a usar: la de la variable de entorno {@link #DB_URL_ENV} si tiene valor (base en otro contenedor, por
     * ejemplo {@code jdbc:h2:tcp://localhost:9092/presence}); si no, el archivo local de siempre.
     */
    public static String jdbcUrlFor(String envValue, String defaultFilePath) {
        return envValue == null || envValue.isBlank() ? "jdbc:h2:file:" + defaultFilePath + ";AUTO_SERVER=TRUE" : envValue.trim();
    }

    /** Abre la base indicada por {@link #DB_URL_ENV}, o el archivo local {@code defaultFilePath} si no esta definida. */
    public static PresenceEventStore fromEnvironment(String defaultFilePath) {
        return forUrl(jdbcUrlFor(System.getenv(DB_URL_ENV), defaultFilePath));
    }

    /** Abre la base H2 de esa URL JDBC (archivo, memoria o servidor TCP). */
    public static PresenceEventStore forUrl(String jdbcUrl) {
        try {
            return new PresenceEventStore(DriverManager.getConnection(jdbcUrl));
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo abrir la base de datos H2 en " + jdbcUrl, e);
        }
    }

    /**
     * Base de datos H2 en memoria, aislada por nombre (para pruebas). Otro componente puede abrir la MISMA base con la
     * URL {@code jdbc:h2:mem:<name>;DB_CLOSE_DELAY=-1} (asi lo hace el alert-engine en sus pruebas).
     */
    public static PresenceEventStore inMemory(String name) {
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
            // Solo se llenan en los eventos REMOVED: como se retiro el objeto y quien estaba junto a el
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS removal_kind VARCHAR(30)");
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS remover_person_id BIGINT");
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS remover_description VARCHAR(200)");
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS remover_crop_path VARCHAR(500)");
            st.execute("ALTER TABLE object_presence_event ADD COLUMN IF NOT EXISTS remover_ai_description VARCHAR(1000)");
        }
    }

    /**
     * Dueno probable de un objeto: la persona que estuvo mas tiempo cerca de el
     * mientras aparecia y se asentaba. Todos los campos pueden ser null (ej. sin
     * imagen no hay recorte ni descripcion).
     */
    public record OwnerInfo(Long personId, String description, String cropPath) {}

    /**
     * Como se retiro un objeto: el tipo (nombre de {@link RemovalKind}) y, si hubo alguien a quien atribuirselo, esa
     * persona (id, descripcion por color y foto junto al objeto). personId es null en NO_ONE_NEAR; los demas campos
     * pueden ser null (ej. sin imagen no hay foto ni descripcion).
     */
    public record RemovalInfo(String kind, Long personId, String description, String cropPath) {}

    /** Guarda el evento y devuelve el id de la fila. */
    public long recordRegistered(long trackedId, String className, int x, int y, int w, int h, Instant when, String framePath, OwnerInfo owner) {
        return insert(trackedId, className, "REGISTERED_AT_REST", x, y, w, h, when, null, framePath, owner, null);
    }

    /** Guarda el evento y devuelve el id de la fila. */
    public long recordRemoved(long trackedId, String className, int x, int y, int w, int h, Instant when, boolean personNearby,
                              String framePath, OwnerInfo owner, RemovalInfo removal) {
        return insert(trackedId, className, "REMOVED", x, y, w, h, when, personNearby, framePath, owner, removal);
    }

    /** Da como se retiro el objeto en el ultimo evento REMOVED, o null si no hay (usado en las pruebas). */
    RemovalInfo lastRemoval() {
        String sql = "SELECT removal_kind, remover_person_id, remover_description, remover_crop_path FROM object_presence_event "
                + "WHERE event_type = 'REMOVED' ORDER BY id DESC LIMIT 1";
        try (var st = connection.createStatement(); var rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            long personId = rs.getLong(2);
            Long id = rs.wasNull() ? null : personId;
            return new RemovalInfo(rs.getString(1), id, rs.getString(3), rs.getString(4));
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar object_presence_event", e);
        }
    }

    /** Un evento tal como esta guardado, con todo lo que otros componentes (alert-engine, dashboard) necesitan leer. */
    public record StoredEvent(long id, long trackedObjectId, String className, String eventType, Instant occurredAt,
                              int x, int y, int width, int height, Boolean personNearby, String framePath,
                              Long ownerPersonId, String ownerDescription, String ownerCropPath, String ownerAiDescription,
                              String removalKind, Long removerPersonId, String removerDescription, String removerCropPath,
                              String removerAiDescription) {}

    private static final String EVENT_COLUMNS = "id, tracked_object_id, class_name, event_type, occurred_at, pos_x, pos_y, "
            + "width, height, person_nearby, frame_path, owner_person_id, owner_description, owner_crop_path, "
            + "owner_ai_description, removal_kind, remover_person_id, remover_description, remover_crop_path, "
            + "remover_ai_description";

    /** Eventos REMOVED con id mayor que el dado, del mas viejo al mas nuevo: asi se lee lo que falta por procesar. */
    public List<StoredEvent> findRemovedAfter(long lastEventId) {
        return query("event_type = 'REMOVED' AND id > ?", lastEventId);
    }

    /** El id mas alto guardado (0 si no hay eventos): sirve para detectar que la tabla se vacio y los ids se reiniciaron. */
    public long maxEventId() {
        try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM object_presence_event")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer object_presence_event", e);
        }
    }

    /** El evento con ese id, con los datos actuales (la descripcion por IA se completa unos segundos despues). */
    public Optional<StoredEvent> findById(long id) {
        return query("id = ?", id).stream().findFirst();
    }

    private List<StoredEvent> query(String where, long param) {
        String sql = "SELECT " + EVENT_COLUMNS + " FROM object_presence_event WHERE " + where + " ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, param);
            try (var rs = ps.executeQuery()) {
                List<StoredEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new StoredEvent(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getTimestamp(5).toInstant(), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                            nullableBoolean(rs, 10), rs.getString(11), nullableLong(rs, 12), rs.getString(13),
                            rs.getString(14), rs.getString(15), rs.getString(16), nullableLong(rs, 17), rs.getString(18),
                            rs.getString(19), rs.getString(20)));
                }
                return events;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer object_presence_event", e);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(java.sql.ResultSet rs, int column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
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
                         int x, int y, int w, int h, Instant when, Boolean personNearby, String framePath, OwnerInfo owner,
                         RemovalInfo removal) {
        String sql = """
            INSERT INTO object_presence_event
                (tracked_object_id, class_name, event_type, occurred_at, pos_x, pos_y, width, height, person_nearby, frame_path,
                 owner_person_id, owner_description, owner_crop_path,
                 removal_kind, remover_person_id, remover_description, remover_crop_path)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.setString(14, removal == null ? null : removal.kind());
            if (removal == null || removal.personId() == null) {
                ps.setNull(15, Types.BIGINT);
            } else {
                ps.setLong(15, removal.personId());
            }
            ps.setString(16, removal == null ? null : removal.description());
            ps.setString(17, removal == null ? null : removal.cropPath());
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
        String removalInfo = removal == null ? ""
                : " - RETIRO " + removal.kind()
                        + (removal.personId() != null ? " por persona #" + removal.personId()
                                + (removal.description() != null ? " (" + removal.description() + ")" : "") : "");
        System.out.println("[" + when + "] Objeto #" + trackedId + " (" + className + ") " + label + personInfo + frameInfo
                + ownerInfo + removalInfo);
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

    /** Guarda la descripcion por IA de quien retiro el objeto (llega unos segundos despues del evento REMOVED). */
    public void updateRemoverAiDescription(long eventId, String aiDescription) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE object_presence_event SET remover_ai_description = ? WHERE id = ?")) {
            ps.setString(1, aiDescription);
            ps.setLong(2, eventId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar la descripcion de quien retiro el objeto", e);
        }
    }

    /** Da la descripcion por IA de quien retiro el objeto en el ultimo evento REMOVED, o null (usado en las pruebas). */
    String lastRemoverAiDescription() {
        String sql = "SELECT remover_ai_description FROM object_presence_event WHERE event_type = 'REMOVED' ORDER BY id DESC LIMIT 1";
        try (var st = connection.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar object_presence_event", e);
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
