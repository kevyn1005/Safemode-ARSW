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
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean rejectImageUrlFormat = false;
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.incrementAndGet();
            int code = status;
            String out = responseBody;
            if (rejectImageUrlFormat && lastBody.get().contains("\"image_url\"")) {
                code = 422;
                out = "{\"detail\": \"formato de imagen no soportado\"}";
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
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

        assertEquals("ropa superior: camisa, chaleco", NvidiaOwnerVisionDescriber.formatDescription(content));
    }

    @Test
    void unaListaDeObjetosDeTatuajesSeLeeComoTextoNoComoJsonCrudo() {
        // respuesta real del modelo sobre un recorte de antebrazos
        String content = "{ \"tatuajes\": [ { \"descripcion\": \"un corazón\", \"parte\": \"brazo\" }, "
                + "{ \"descripcion\": \"un arco\", \"parte\": \"antebrazo\" } ] }";

        assertEquals("un corazón (brazo), un arco (antebrazo)", NvidiaOwnerVisionDescriber.formatTattoos(content));
    }

    @Test
    void unObjetoSinLasClavesEsperadasSeUneComoTexto() {
        assertEquals("gris antebrazo", NvidiaOwnerVisionDescriber.formatTattoos("{\"tatuajes\": {\"color\": \"gris\", \"zona\": \"antebrazo\"}}"));
    }

    @Test
    void unaListaVaciaDeTatuajesNoEsUnDato() {
        assertNull(NvidiaOwnerVisionDescriber.formatTattoos("{\"tatuajes\": []}"));
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
    void formatTattoosDevuelveElDatoOninguno() {
        assertEquals("dibujo en el antebrazo", NvidiaOwnerVisionDescriber.formatTattoos("{\"tatuajes\": \"dibujo en el antebrazo\"}"));
        assertEquals("ninguno", NvidiaOwnerVisionDescriber.formatTattoos("```json\n{\"tatuajes\": \"ninguno\"}\n```"));
    }

    @Test
    void formatTattoosSinDatoDevuelveNull() {
        assertNull(NvidiaOwnerVisionDescriber.formatTattoos("{\"tatuajes\": \"no se ve\"}"));
        assertNull(NvidiaOwnerVisionDescriber.formatTattoos("{\"tatuajes\": \"\"}"));
        assertNull(NvidiaOwnerVisionDescriber.formatTattoos("{\"ropa_superior\": \"camisa\"}"));
        assertNull(NvidiaOwnerVisionDescriber.formatTattoos("Se ven dos antebrazos."));
        assertNull(NvidiaOwnerVisionDescriber.formatTattoos(null));
    }

    @Test
    void describeArmsHaceLaPreguntaDeTatuajesYDevuelveSoloEseDato() {
        responseBody = serviceResponse("{\"tatuajes\": \"dibujo gris en el antebrazo\"}");

        String tattoos = client().describeArms(new BufferedImage(200, 512, BufferedImage.TYPE_INT_RGB));

        assertEquals("dibujo gris en el antebrazo", tattoos);
        String prompt = JsonParser.parseString(lastBody.get()).getAsJsonObject().getAsJsonArray("messages")
                .get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(prompt.contains("antebrazos"), "debe usar la pregunta especifica de antebrazos, no la de la persona entera");
    }

    @Test
    void describeArmsSiElModeloNoLoSabeDevuelveNull() {
        responseBody = serviceResponse("{\"tatuajes\": \"no se ve\"}");

        assertNull(client().describeArms(new BufferedImage(200, 512, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void siElModeloRechazaElFormatoEstandarReintentaConLaImagenDentroDelTexto() {
        rejectImageUrlFormat = true;
        responseBody = serviceResponse("{\"ropa_superior\": \"camiseta negra\"}");

        String description = client().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB));

        assertEquals("ropa superior: camiseta negra", description);
        assertEquals(2, requests.get(), "primero el formato estandar, y tras el 422 el formato con <img>");
        String content = JsonParser.parseString(lastBody.get()).getAsJsonObject().getAsJsonArray("messages")
                .get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(content.contains("<img src=\"data:image/jpeg;base64,"), "la imagen viaja dentro del texto");
    }

    @Test
    void conElFormatoEstandarAceptadoNoSeReintenta() {
        responseBody = serviceResponse("{\"ropa_superior\": \"camiseta negra\"}");

        client().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB));

        assertEquals(1, requests.get());
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
