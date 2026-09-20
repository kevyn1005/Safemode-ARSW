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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private volatile int stallRequests = 0; // las primeras N peticiones se cuelgan; Integer.MAX_VALUE = todas
    private volatile int status = 200;
    private volatile String responseBody = "";
    private volatile String secondResponseBody = null; // si no es null, se responde esto desde la segunda peticion

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int requestNumber = requests.incrementAndGet();
            if (stallRequests == Integer.MAX_VALUE || requestNumber <= stallRequests) {
                try {
                    Thread.sleep(2500); // mas que el limite de espera del cliente en las pruebas de cuelgue
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int code = status;
            String out = responseBody;
            if (requestNumber >= 2 && secondResponseBody != null) {
                out = secondResponseBody;
            }
            if (rejectImageUrlFormat && lastBody.get().contains("\"image_url\"")) {
                code = 422;
                out = "{\"detail\": \"formato de imagen no soportado\"}";
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException e) {
                // el cliente ya se rindio (prueba de cuelgue): no importa
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(3)); // que un cuelgue no bloquee al reintento
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

    private NvidiaOwnerVisionDescriber impatientClient() {
        return new NvidiaOwnerVisionDescriber("clave-de-prueba", "http://127.0.0.1:" + server.getAddress().getPort(),
                "modelo-de-prueba", Duration.ofSeconds(1));
    }

    @Test
    void siLaPrimeraLlamadaSeCuelgaSeReintentaYLaSegundaRespondeBien() {
        stallRequests = 1;
        responseBody = serviceResponse("{\"ropa_superior\": \"camiseta negra\"}");

        String description = impatientClient().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB));

        assertEquals("ropa superior: camiseta negra", description);
        assertEquals(2, requests.get(), "una llamada colgada y un reintento");
    }

    @Test
    void siTodasLasLlamadasSeCuelganSeRindeTrasElReintentoSinExcepcion() {
        stallRequests = Integer.MAX_VALUE;
        responseBody = serviceResponse("{\"ropa_superior\": \"camiseta negra\"}");

        assertNull(impatientClient().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB)));
        assertEquals(2, requests.get(), "exactamente un intento y un reintento, sin bucle infinito");
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

    private static final String TEXTO_LIBRE = "**Analisis de la imagen** La imagen muestra a una mujer sentada en un sofa "
            + "con una mochila negra. **Objetos identificados** * **Ropa superior**: camiseta blanca.";

    @Test
    void elCalentamientoEsUnaPeticionDeSoloTextoSinImagenes() {
        responseBody = serviceResponse("ok");

        client().warmUp();

        assertEquals(1, requests.get());
        assertEquals("/chat/completions", lastPath.get());
        assertEquals("Bearer clave-de-prueba", lastAuth.get());
        assertTrue(!lastBody.get().contains("image_url") && !lastBody.get().contains("base64"),
                "el calentamiento no debe enviar ninguna imagen");
        assertEquals(3, JsonParser.parseString(lastBody.get()).getAsJsonObject().get("max_tokens").getAsInt(),
                "pide muy pocos tokens: solo despertar el modelo");
    }

    @Test
    void siElCalentamientoFallaNoHayExcepcion() {
        status = 500;
        responseBody = "{\"error\": \"boom\"}";
        client().warmUp();

        new NvidiaOwnerVisionDescriber("k", "http://127.0.0.1:1", "m", Duration.ofSeconds(2)).warmUp();
        assertEquals(1, requests.get());
    }

    @Test
    void siElModeloRespondeEnTextoLibreSePideDeNuevoYSeUsaLaSegundaRespuesta() {
        responseBody = serviceResponse(TEXTO_LIBRE);
        secondResponseBody = serviceResponse("{\"ropa_superior\": \"camiseta blanca\"}");

        String description = client().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB));

        assertEquals("ropa superior: camiseta blanca", description);
        assertEquals(2, requests.get(), "una respuesta sin JSON y una segunda pidiendola de nuevo");
    }

    @Test
    void siElModeloNuncaRespondeConJsonSeRindeTrasUnSoloReintento() {
        responseBody = serviceResponse(TEXTO_LIBRE);

        assertNull(client().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB)));
        assertEquals(2, requests.get(), "exactamente una peticion y un reintento");
    }

    @Test
    void unJsonValidoSinDatosUtilesNoSeReintenta() {
        responseBody = serviceResponse("{\"ropa_superior\": \"no se ve\", \"cabello\": \"no se ve\"}");

        assertNull(client().describe(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB)));
        assertEquals(1, requests.get(), "el modelo si contesto en JSON: 'no se ve' es una respuesta, no un error de formato");
    }

    @Test
    void describeArmsTambienSePideDeNuevoSiNoVieneEnJson() {
        responseBody = serviceResponse(TEXTO_LIBRE);
        secondResponseBody = serviceResponse("{\"tatuajes\": \"antebrazo, gris, mediano\"}");

        assertEquals("antebrazo, gris, mediano", client().describeArms(new BufferedImage(200, 512, BufferedImage.TYPE_INT_RGB)));
        assertEquals(2, requests.get());
    }

    @Test
    void losPromptsNoLlevanBarrasComoAlternativasPorqueElModeloLasCopia() {
        // respuesta real: el modelo copio "parte | ninguno | no se ve" y devolvio "brazo, negro | ninguno"
        assertFalse(NvidiaOwnerVisionDescriber.PROMPT.contains("|"), "el prompt de la persona no debe llevar barras");
        assertFalse(NvidiaOwnerVisionDescriber.ARMS_PROMPT.contains("|"), "el prompt de antebrazos no debe llevar barras");
    }

    @Test
    void siElModeloCopiaLasAlternativasSeQuedaConLoConcreto() {
        assertEquals("brazo, negro, grande y antebrazo, negro, pequeño",
                NvidiaOwnerVisionDescriber.formatTattoos(
                        "{\"tatuajes\": \"brazo, negro, grande | antebrazo, negro, pequeño | ninguno\"}"));
        assertEquals("ninguno", NvidiaOwnerVisionDescriber.formatTattoos("{\"tatuajes\": \"ninguno | no se ve\"}"));
        assertNull(NvidiaOwnerVisionDescriber.formatTattoos("{\"tatuajes\": \"no se ve | no se ve\"}"));
        assertEquals("un texto sin barras", NvidiaOwnerVisionDescriber.resolveAlternatives("un texto sin barras"));
        assertEquals("ropa superior: camisa negra; lentes: ninguno",
                NvidiaOwnerVisionDescriber.formatDescription(
                        "{\"ropa_superior\": \"camisa negra | no se ve\", \"lentes\": \"ninguno | no se ve\"}"));
    }

    @Test
    void izquierdaYDerechaSeQuitanDeLoQueSeGuarda() {
        assertEquals("En el brazo, un tatuaje de color negro",
                NvidiaOwnerVisionDescriber.formatTattoos("{\"tatuajes\": \"En el brazo izquierdo, un tatuaje de color negro\"}"));
        assertEquals("antebrazo gris", NvidiaOwnerVisionDescriber.withoutSides("antebrazo derecho gris"));
        assertEquals("antebrazo", NvidiaOwnerVisionDescriber.withoutSides("antebrazo izquierdo"));
        assertEquals("ancla en el brazo", NvidiaOwnerVisionDescriber.withoutSides("ancla en el brazo"));
        assertEquals("ropa superior: camisa", NvidiaOwnerVisionDescriber.formatDescription("{\"ropa_superior\": \"camisa\"}"));
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
