package com.safemode.presence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sin red: el cliente se prueba contra un servidor HTTP falso local. */
class NvidiaOwnerVisionDescriberTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private NvidiaOwnerVisionDescriber client() {
        return new NvidiaOwnerVisionDescriber("clave-de-prueba", "http://127.0.0.1:" + server.getAddress().getPort(),
                "modelo-de-prueba", Duration.ofSeconds(5));
    }

    /** Respuesta con el formato del servicio: el texto del modelo va dentro de choices[0].message.content. */
    private static String serviceResponse(String modelText) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", modelText);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonObject root = new JsonObject();
        root.add("choices", JsonParser.parseString("[" + choice + "]"));
        return root.toString();
    }

    @Test
    void armaLaFraseConLosCamposQueElModeloLlenoYOmiteLoQueNoSeVe() {
        String content = "```json\n{\"ropa_superior\": \"camiseta negra\", \"ropa_inferior\": \"pantalon azul oscuro\","
                + " \"calzado\": \"no se ve\", \"tatuajes\": \"antebrazo\", \"lentes\": \"ninguno\","
                + " \"cabello\": \"oscuro y corto\"}\n```";

        assertEquals("ropa superior: camiseta negra; ropa inferior: pantalon azul oscuro; tatuajes: antebrazo; "
                        + "lentes: ninguno; cabello: oscuro y corto",
                NvidiaOwnerVisionDescriber.formatDescription(content));
    }

    @Test
    void aceptaTextoAlrededorDelJsonYValoresQueNoSonTexto() {
        String content = "Claro, aqui esta: {\"ropa_superior\": [\"camisa\", \"chaleco\"], \"tatuajes\": null} Espero que sirva.";

        assertEquals("ropa superior: [\"camisa\",\"chaleco\"]", NvidiaOwnerVisionDescriber.formatDescription(content));
    }

    @Test
    void sinJsonUtilNoHayDescripcion() {
        assertNull(NvidiaOwnerVisionDescriber.formatDescription(null));
        assertNull(NvidiaOwnerVisionDescriber.formatDescription("Es un hombre alto con camisa negra."));
        assertNull(NvidiaOwnerVisionDescriber.formatDescription("{ esto no es json }"));
        assertNull(NvidiaOwnerVisionDescriber.formatDescription(
                "{\"ropa_superior\": \"no se ve\", \"ropa_inferior\": \"\", \"cabello\": \"No se ve\"}"));
    }

    @Test
    void ignoraLosCamposQueNoPidio() {
        // el modelo invento una estatura: no esta en los campos permitidos, no debe colarse
        String content = "{\"ropa_superior\": \"camiseta negra\", \"estatura\": \"1.70 metros\"}";

        assertEquals("ropa superior: camiseta negra", NvidiaOwnerVisionDescriber.formatDescription(content));
    }

    @Test
    void enviaLaImagenYLaClaveAlServicioYDevuelveLaDescripcion() {
        responseBody = serviceResponse("{\"ropa_superior\": \"camiseta negra\", \"tatuajes\": \"antebrazo\"}");

        String description = client().describe(new BufferedImage(217, 640, BufferedImage.TYPE_INT_RGB));

        assertEquals("ropa superior: camiseta negra; tatuajes: antebrazo", description);
        assertEquals("/chat/completions", lastPath.get());
        assertEquals("Bearer clave-de-prueba", lastAuth.get());
        JsonObject sent = JsonParser.parseString(lastBody.get()).getAsJsonObject();
        assertEquals("modelo-de-prueba", sent.get("model").getAsString());
        assertTrue(lastBody.get().contains("data:image/jpeg;base64,"), "la imagen viaja como JPEG en base64");
    }

    @Test
    void siElModeloNoDevuelveJsonNoHayDescripcionNiExcepcion() {
        responseBody = serviceResponse("La persona lleva una camiseta negra y pantalon azul.");

        assertNull(client().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void siLaRespuestaTieneOtraFormaNoHayDescripcionNiExcepcion() {
        responseBody = "{\"algo\": \"inesperado\"}";

        assertNull(client().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void siElServicioFallaNoHayDescripcionNiExcepcion() {
        status = 500;
        responseBody = "{\"error\": \"boom\"}";

        assertNull(client().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void siElServicioNoRespondeNoHayDescripcionNiExcepcion() {
        NvidiaOwnerVisionDescriber sinServidor = new NvidiaOwnerVisionDescriber("k", "http://127.0.0.1:1", "m", Duration.ofSeconds(2));

        assertNull(sinServidor.describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void laImagenSeReduceAntesDeEnviarse() throws IOException {
        String small = NvidiaOwnerVisionDescriber.encodeJpegBase64(new BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB));
        String huge = NvidiaOwnerVisionDescriber.encodeJpegBase64(new BufferedImage(2560, 1080, BufferedImage.TYPE_INT_RGB));

        assertTrue(small.length() > 0);
        assertTrue(huge.length() < 200_000, "una imagen de 2560x1080 debe reducirse a 640 px antes de enviarse");
    }
}
