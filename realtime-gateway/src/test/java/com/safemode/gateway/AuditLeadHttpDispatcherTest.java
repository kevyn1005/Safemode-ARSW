package com.safemode.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validacion de la pista 3 de la auditoria de seguridad (run-1): "DashboardHttpServer atiende todo en un unico hilo, asi
 * que una conexion detenida o una llamada lenta puede congelar el panel".
 *
 * <p>Cada prueba abre una situacion que podria retener ese hilo y despues mide si un SEGUNDO cliente, normal, recibe
 * respuesta. Todo en loopback, con un verificador de Google falso (no se llama a internet) y archivos temporales.
 *
 * <p>ARREGLADA: {@link DashboardHttpServer} ahora atiende cada peticion en un pool de 32 hilos y fija limites de tiempo del
 * JDK (peticion completa 30 s, respuesta completa 60 s). Antes del arreglo las cuatro pruebas {@code pista3_*} fallaban en
 * esta maquina (el segundo cliente no recibia respuesta mientras una conexion estaba retenida); ahora son pruebas de
 * regresion normales. Ojo: se corren en la JDK de tu maquina, que puede no ser la 17 que usa el Dockerfile.
 * Limite que NO cubre el arreglo: quien retenga mas de 32 conexiones a la vez agota el pool hasta que el JDK las corte
 * (30 s); el servidor escucha en localhost por defecto.
 */
class AuditLeadHttpDispatcherTest {

    private static final String MP4 = "Camera_pushing_into_AI_core_20260926081000.mp4";
    private static final int SECOND_CLIENT_WAIT_MS = 3000;

    @TempDir
    Path tmp;

    private DashboardHttpServer server;
    private int port;
    private String origin;
    private Path dashboard;
    private volatile long verifierDelayMillis = 0;
    private final List<Socket> rawSockets = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        dashboard = Files.createDirectories(tmp.resolve("dashboard"));
        Path photos = Files.createDirectories(tmp.resolve("photos"));
        Files.writeString(dashboard.resolve("index.html"), "<html>panel</html>");
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        origin = "http://localhost:" + port;
        AuthService.TokenVerifier verifier = token -> {
            try {
                Thread.sleep(verifierDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(new AuthService.User("ana@example.com", "Ana"));
        };
        AuthService auth = new AuthService("client-id-falso", Set.of("ana@example.com"), verifier, Clock.systemUTC());
        server = new DashboardHttpServer("localhost", port, dashboard, photos, null, Set.of(origin)).withAuth(auth);
        server.start();
    }

    @AfterEach
    void tearDown() {
        for (Socket socket : rawSockets) {
            try {
                socket.close(); // primero se sueltan las conexiones detenidas, para que stop() no se quede esperando
            } catch (IOException ignored) {
                // ya estaba cerrado
            }
        }
        server.stop();
    }

    private static void log(String line) {
        System.out.println("[AUDITORIA] " + line);
    }

    /** Abre una conexion TCP cruda (sin HttpClient) para poder mandar peticiones a medias. */
    private Socket openRaw(int receiveBufferBytes) throws IOException {
        Socket socket = new Socket();
        if (receiveBufferBytes > 0) {
            socket.setReceiveBufferSize(receiveBufferBytes); // debe fijarse antes de conectar
        }
        socket.connect(new InetSocketAddress("localhost", port), 2000);
        rawSockets.add(socket);
        return socket;
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    /** Milisegundos que tarda un cliente normal en recibir respuesta de GET path, o -1 si no responde a tiempo. */
    private long timeToServe(String path, int timeoutMillis) throws Exception {
        long start = System.nanoTime();
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + path))
                    .timeout(Duration.ofMillis(timeoutMillis)).build(), HttpResponse.BodyHandlers.ofString());
            return (System.nanoTime() - start) / 1_000_000;
        } catch (HttpTimeoutException e) {
            return -1;
        }
    }

    private static String describe(long ms) {
        return ms < 0 ? "SIN RESPUESTA (bloqueado)" : "atendido en " + ms + " ms";
    }

    @Test
    void pista3_losLimitesDeTiempoDelJdkQuedanFijados() {
        // Que el JDK los respete depende de que este sea el primer HttpServer de la JVM (lo es en GatewayRunner); aqui solo
        // se comprueba que el codigo los fija y que se pueden cambiar con -D (putIfAbsent no pisa un valor ya puesto).
        assertTrue(Long.parseLong(System.getProperty("sun.net.httpserver.maxReqTime")) > 0);
        assertTrue(Long.parseLong(System.getProperty("sun.net.httpserver.maxRspTime")) > 0);
    }

    @Test
    @Timeout(60)
    void pista3_unaPeticionIncompletaNoDebeBloquearAOtrosClientes() throws Exception {
        Socket stalled = openRaw(0);
        write(stalled, "GET /index.html HTTP/1.1\r\nHost: localhost:" + port + "\r\n"); // falta la linea en blanco final
        Thread.sleep(300);

        long whileStalled = timeToServe("/auth/config", SECOND_CLIENT_WAIT_MS);
        stalled.close();
        long afterClosing = timeToServe("/auth/config", 5000);
        log("pista 3 / encabezados a medias, segundo cliente: " + describe(whileStalled)
                + " | despues de cerrar la conexion detenida: " + describe(afterClosing));
        assertTrue(whileStalled >= 0, "una peticion a medias dejo sin servicio a los demas clientes");
    }

    @Test
    @Timeout(60)
    void pista3_unCuerpoPostIncompletoNoDebeBloquearAOtrosClientes() throws Exception {
        Socket stalled = openRaw(0);
        write(stalled, "POST /auth/google HTTP/1.1\r\nHost: localhost:" + port + "\r\nOrigin: " + origin
                + "\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"credential\":"); // promete 100 bytes, manda 14
        Thread.sleep(300);

        long whileStalled = timeToServe("/auth/config", SECOND_CLIENT_WAIT_MS);
        stalled.close();
        long afterClosing = timeToServe("/auth/config", 5000);
        log("pista 3 / cuerpo POST a medias, segundo cliente: " + describe(whileStalled)
                + " | despues de cerrar la conexion detenida: " + describe(afterClosing));
        assertTrue(whileStalled >= 0, "un cuerpo POST a medias dejo sin servicio a los demas clientes");
    }

    @Test
    @Timeout(60)
    void pista3_unaVerificacionLentaDeGoogleNoDebeRetrasarOtrasRutas() throws Exception {
        verifierDelayMillis = 2000; // simula a Google tardando 2 s por inicio de sesion (aqui no se llama a Google)
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + "/auth/google"))
                        .header("Origin", origin).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"credential\":\"a.b.c\"}")).build(),
                        HttpResponse.BodyHandlers.ofString()));
            }
            Thread.sleep(400);
            long ms = timeToServe("/auth/config", 8000);
            log("pista 3 / 2 inicios de sesion de 2 s en curso, GET /auth/config: " + describe(ms));
            assertTrue(ms >= 0 && ms < 1000, "una ruta barata esta esperando detras de verificaciones lentas (" + describe(ms) + ")");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(90)
    void pista3_unaDescargaSinLeerElMp4NoDebeBloquearAOtrosClientes() throws Exception {
        Files.write(dashboard.resolve(MP4), new byte[12 * 1024 * 1024]); // tamano parecido al mp4 real (~12 MB)
        Socket stalled = openRaw(1024); // el atacante controla sus propios buffers: uno minimo
        write(stalled, "GET /" + MP4 + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n"); // pide el archivo y NO lee la respuesta
        Thread.sleep(500);

        long whileStalled = timeToServe("/auth/config", SECOND_CLIENT_WAIT_MS);
        stalled.close();
        long afterClosing = timeToServe("/auth/config", 5000);
        log("pista 3 / mp4 de 12 MB pedido sin leer, segundo cliente: " + describe(whileStalled)
                + " | despues de cerrar esa conexion: " + describe(afterClosing));
        assertTrue(whileStalled >= 0, "una descarga que no se lee dejo sin servicio a los demas clientes");
    }
}
