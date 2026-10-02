package com.safemode.presence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Descripcion del dueno con un modelo de vision del catalogo de NVIDIA (API compatible con OpenAI).
 *
 * El modelo NO escribe la frase: llena campos fijos en JSON (ropa, tatuajes, lentes...) y la frase
 * se arma aqui. Asi no se cuelan estaturas inventadas ni contradicciones, y lo que no se ve
 * simplemente se omite. La clave se lee de la variable de entorno NVIDIA_API_KEY y nunca se
 * escribe en logs ni en archivos.
 *
 * Privacidad: el recorte con la cara de la persona se envia al servicio externo.
 */
public class NvidiaOwnerVisionDescriber implements OwnerVisionDescriber {

    static final String DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1";
    static final String DEFAULT_MODEL = "meta/llama-3.2-11b-vision-instruct";
    // Una respuesta normal tarda ~3 s; las que pasan de 20 s se cuelgan (visto en la primera llamada de dos corridas
    // seguidas), asi que se corta pronto y se reintenta una vez en vez de esperar un minuto.
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_ATTEMPTS = 2;
    // A los 6 s sin respuesta (el doble de lo normal) se lanza una segunda peticion igual; gana la primera que responda.
    // Solo se paga el doble de tokens en las llamadas lentas.
    static final Duration DEFAULT_HEDGE_AFTER = Duration.ofSeconds(6);
    // el calentamiento corre aparte y puede tardar lo que tarde en arrancar el modelo
    private static final Duration WARM_UP_TIMEOUT = Duration.ofSeconds(90);

    // Lado mayor maximo de la imagen enviada. 1024 costaba ~6.700 tokens de entrada por llamada (la imagen domina el costo,
    // no el texto). Se subio a 1024 porque con 640 el tatuaje del antebrazo se perdia, pero ahora los tatuajes se
    // revisan en un recorte aparte, asi que se puede probar un valor menor con NVIDIA_IMAGE_MAX_SIDE.
    static final int DEFAULT_MAX_IMAGE_SIDE_PX = 1024;
    private static final int MIN_MAX_IMAGE_SIDE_PX = 256;
    private static final int MAX_FIELD_CHARS = 120;
    private static final int MAX_DESCRIPTION_CHARS = 900;

    // Los marcadores <...> muestran el formato sin darle valores de ejemplo que el modelo pueda copiar. Nunca llevan
    // barras "a | b" como alternativas: el modelo las copia tal cual ("brazo, negro | ninguno") y contradice su respuesta.
    static final String PROMPT = "Analiza la imagen de una persona para un reporte de seguridad. "
            + "Responde SOLO con un objeto JSON, sin texto antes ni despues, con exactamente estas claves y un texto corto "
            + "en espanol como valor: {\"ropa_superior\": \"<tipo y color>\", \"ropa_inferior\": \"<tipo y color>\", "
            + "\"calzado\": \"<tipo>\", \"tatuajes\": \"<parte del cuerpo y color>\", "
            + "\"lentes\": \"<texto>\", \"cabello\": \"<largo y color>\"}. "
            + "Para \"tatuajes\" y \"lentes\" revisa con cuidado brazos, antebrazos, cuello y cara: si hay, describelos; "
            + "escribe exactamente \"ninguno\" si esa zona se ve y no hay, y exactamente \"no se ve\" solo si la zona no "
            + "aparece en la imagen. Usa exactamente \"no se ve\" tambien en cualquier otra clave que no se aprecie. "
            + "Cada valor es UN solo texto: nunca separes alternativas con barras. "
            + "No estimes estatura, edad ni identidad, no interpretes que dibujo es un tatuaje y no uses izquierda ni derecha.";

    // Segunda pregunta, solo sobre el recorte de los antebrazos (sin cara): ahi el tatuaje ocupa gran parte de la imagen
    static final String ARMS_PROMPT = "La imagen muestra los antebrazos de una persona, uno o dos lado a lado. "
            + "Responde SOLO con un objeto JSON, sin texto antes ni despues, con exactamente esta forma: "
            + "{\"tatuajes\": \"<texto>\"}. El valor es UN solo texto corto (nunca una lista ni alternativas separadas "
            + "por barras): si hay tatuajes, di en que parte esta (brazo o antebrazo) y su color y tamano aproximado; "
            + "escribe exactamente \"ninguno\" si se ven los antebrazos y no hay tatuajes, y exactamente \"no se ve\" "
            + "si la imagen no permite saberlo. No interpretes que dibujo es, no uses izquierda ni derecha "
            + "y no supongas nada que no se vea.";

    // clave del JSON -> etiqueta en la frase final (en este orden)
    private static final String[][] FIELDS = {
            {"ropa_superior", "ropa superior"},
            {"ropa_inferior", "ropa inferior"},
            {"calzado", "calzado"},
            {"tatuajes", "tatuajes"},
            {"lentes", "lentes"},
            {"cabello", "cabello"},
    };

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final Duration hedgeAfter;
    private final int maxImageSide;
    // consumo acumulado segun el campo "usage" que devuelve el servicio (solo cuenta la respuesta que se uso)
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicInteger callsWithUsage = new AtomicInteger();

    public NvidiaOwnerVisionDescriber(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL, DEFAULT_TIMEOUT);
    }

    NvidiaOwnerVisionDescriber(String apiKey, String baseUrl, String model, Duration timeout) {
        this(apiKey, baseUrl, model, timeout, DEFAULT_HEDGE_AFTER);
    }

    NvidiaOwnerVisionDescriber(String apiKey, String baseUrl, String model, Duration timeout, Duration hedgeAfter) {
        this(apiKey, baseUrl, model, timeout, hedgeAfter, DEFAULT_MAX_IMAGE_SIDE_PX);
    }

    NvidiaOwnerVisionDescriber(String apiKey, String baseUrl, String model, Duration timeout, Duration hedgeAfter,
                               int maxImageSide) {
        this.maxImageSide = Math.max(MIN_MAX_IMAGE_SIDE_PX, maxImageSide);
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeout = timeout;
        this.hedgeAfter = hedgeAfter;
        // HTTP/1.1 explicito: es lo que se probo a mano (2.9 s por respuesta) antes de integrarlo
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
    }

    /** Devuelve un describidor si existe NVIDIA_API_KEY (y NVIDIA_VISION_MODEL opcional); si no, {@code null}. */
    public static NvidiaOwnerVisionDescriber fromEnvironment() {
        String key = System.getenv("NVIDIA_API_KEY");
        if (key == null || key.isBlank()) {
            return null;
        }
        String model = System.getenv("NVIDIA_VISION_MODEL");
        return new NvidiaOwnerVisionDescriber(key.trim(), DEFAULT_BASE_URL,
                model == null || model.isBlank() ? DEFAULT_MODEL : model.trim(), DEFAULT_TIMEOUT, DEFAULT_HEDGE_AFTER,
                parseMaxImageSide(System.getenv("NVIDIA_IMAGE_MAX_SIDE")));
    }

    /** Lado mayor maximo pedido por variable de entorno (NVIDIA_IMAGE_MAX_SIDE); si falta o no es un numero, el de siempre. */
    static int parseMaxImageSide(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_IMAGE_SIDE_PX;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("[IA] NVIDIA_IMAGE_MAX_SIDE no es un numero (\"" + value + "\"): se usa " + DEFAULT_MAX_IMAGE_SIDE_PX);
            return DEFAULT_MAX_IMAGE_SIDE_PX;
        }
    }

    public int maxImageSide() {
        return maxImageSide;
    }

    public String modelName() {
        return model;
    }

    /**
     * Despierta el modelo en segundo plano con una peticion de solo texto (sin imagenes, ~5 tokens). El servicio
     * gratuito puede tener los modelos apagados (el 90b, casi sin uso, dio timeout hasta con solo texto), y asi el
     * arranque ocurre mientras se prepara la escena. Ojo: NO evita los cuelgues de la primera llamada con imagen (se
     * vieron con el modelo ya despierto); de esos se encarga la segunda peticion a los 6 s de {@link #send}.
     */
    public void warmUpAsync() {
        Thread thread = new Thread(this::warmUp, "owner-vision-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    void warmUp() {
        long startedAt = System.nanoTime();
        System.out.println("[IA] Calentando el modelo (peticion de solo texto)...");
        try {
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", "Responde solo con la palabra: ok");
            JsonArray messages = new JsonArray();
            messages.add(message);
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("messages", messages);
            body.addProperty("max_tokens", 3);
            body.addProperty("temperature", 0);
            body.addProperty("stream", false);

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(WARM_UP_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[IA] Modelo " + (response.statusCode() == 200 ? "listo" : "respondio HTTP " + response.statusCode())
                    + " tras " + secondsSince(startedAt) + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            System.err.println("[IA] El calentamiento no obtuvo respuesta tras " + secondsSince(startedAt) + " s ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public String describe(BufferedImage ownerCrop) {
        long startedAt = System.nanoTime();
        String content = fetchModelContent(ownerCrop);
        if (content != null && !hasJson(content)) {
            // el modelo contesto en texto libre (Markdown) en vez de JSON: se pide una vez mas antes de rendirse
            System.err.println("[IA] La respuesta no venia en JSON; se pide de nuevo. Texto del modelo: " + snippet(content));
            content = fetchModelContent(ownerCrop);
        }
        if (content == null) {
            return null;
        }
        String description = formatDescription(content);
        if (description == null) {
            System.err.println("[IA] La respuesta (" + secondsSince(startedAt) + " s) no traia campos utiles. Texto del modelo: "
                    + snippet(content));
        } else {
            System.out.println("[IA] Respuesta recibida en " + secondsSince(startedAt) + " s");
        }
        return description;
    }

    /** Pregunta solo por los tatuajes sobre el recorte de antebrazos; devuelve el dato, "ninguno", o null si no se pudo saber. */
    @Override
    public String describeArms(BufferedImage armsCrop) {
        long startedAt = System.nanoTime();
        String content = fetchModelContent(armsCrop, ARMS_PROMPT);
        if (content != null && !hasJson(content)) {
            System.err.println("[IA] La respuesta de antebrazos no venia en JSON; se pide de nuevo. Texto del modelo: " + snippet(content));
            content = fetchModelContent(armsCrop, ARMS_PROMPT);
        }
        if (content == null) {
            return null;
        }
        String tattoos = formatTattoos(content);
        System.out.println("[IA] Antebrazos analizados en " + secondsSince(startedAt) + " s"
                + (tattoos == null ? " (sin dato de tatuajes)" : ""));
        return tattoos;
    }

    /** Envia el recorte al servicio y devuelve el texto crudo que escribio el modelo, o null si fallo (ya avisa por consola). */
    String fetchModelContent(BufferedImage ownerCrop) {
        return fetchModelContent(ownerCrop, PROMPT);
    }

    String fetchModelContent(BufferedImage ownerCrop, String prompt) {
        long startedAt = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> response = send(ownerCrop, prompt, false);
                if (response.statusCode() == 400 || response.statusCode() == 422) {
                    // algunos modelos del catalogo (p. ej. phi-3-vision) esperan la imagen como <img> dentro del texto
                    response = send(ownerCrop, prompt, true);
                }
                if (response.statusCode() != 200) {
                    System.err.println("[IA] El servicio respondio HTTP " + response.statusCode() + " en " + secondsSince(startedAt)
                            + " s: " + snippet(response.body()));
                    return null;
                }
                recordUsage(response.body());
                return extractContent(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[IA] Descripcion cancelada tras " + secondsSince(startedAt) + " s");
                return null;
            } catch (IOException e) {
                // una llamada normal tarda ~3 s; si se cuelga (pasa a veces con la primera), otra conexion suele responder
                if (attempt < MAX_ATTEMPTS) {
                    System.err.println("[IA] Sin respuesta tras " + secondsSince(startedAt) + " s ("
                            + e.getClass().getSimpleName() + "); se reintenta");
                    continue;
                }
                System.err.println("[IA] No se pudo describir al dueno tras " + secondsSince(startedAt) + " s ("
                        + e.getClass().getSimpleName() + "): " + e.getMessage());
                return null;
            } catch (RuntimeException e) {
                System.err.println("[IA] No se pudo describir al dueno tras " + secondsSince(startedAt) + " s ("
                        + e.getClass().getSimpleName() + "): " + e.getMessage());
                return null;
            }
        }
    }

    private HttpResponse<String> send(BufferedImage crop, String prompt, boolean imgTagStyle) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(crop, prompt, imgTagStyle)))
                .build();

        CompletableFuture<HttpResponse<String>> first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return first.get(hedgeAfter.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            // Una respuesta normal tarda ~3 s, y de vez en cuando el servicio se cuelga 20 s o mas. En vez de esperar,
            // se lanza una segunda peticion igual (sin cancelar la primera) y se usa la primera que responda.
            System.err.println("[IA] La respuesta tarda mas de " + hedgeAfter.toSeconds()
                    + " s; se envia una segunda peticion igual y se usa la primera que responda");
            return raceWithSecondRequest(first, request);
        } catch (ExecutionException e) {
            throw unwrap(e);
        } catch (InterruptedException e) {
            first.cancel(true);
            throw e;
        }
    }

    /** La primera peticion ya esta en curso: manda otra igual y devuelve la primera respuesta correcta de las dos. */
    private HttpResponse<String> raceWithSecondRequest(CompletableFuture<HttpResponse<String>> first, HttpRequest request)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> second = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> winner = new CompletableFuture<>();
        AtomicInteger failures = new AtomicInteger();
        for (CompletableFuture<HttpResponse<String>> attempt : List.of(first, second)) {
            attempt.whenComplete((response, error) -> {
                if (error == null) {
                    winner.complete(response);
                } else if (failures.incrementAndGet() == 2) {
                    winner.completeExceptionally(error); // fallaron las dos
                }
            });
        }
        try {
            return winner.get(); // cada peticion tiene su propio limite de espera, asi que esto termina
        } catch (ExecutionException e) {
            throw unwrap(e);
        } finally {
            first.cancel(true);
            second.cancel(true);
        }
    }

    private static IOException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        return new IOException(cause);
    }

    /** Anota los tokens que informa el servicio en "usage" (entrada y salida) y los muestra; sin ese campo no hace nada. */
    private void recordUsage(String responseBody) {
        try {
            JsonObject usage = JsonParser.parseString(responseBody).getAsJsonObject().getAsJsonObject("usage");
            if (usage == null || !usage.has("prompt_tokens") || !usage.has("completion_tokens")) {
                return;
            }
            long in = usage.get("prompt_tokens").getAsLong();
            long out = usage.get("completion_tokens").getAsLong();
            promptTokens.addAndGet(in);
            completionTokens.addAndGet(out);
            callsWithUsage.incrementAndGet();
            System.out.println("[IA] Tokens de esta llamada: entrada " + in + ", salida " + out);
        } catch (RuntimeException e) {
            // el consumo es solo informativo: una respuesta con otro formato no debe romper la descripcion
        }
    }

    /** Resumen del consumo de la corrida, para imprimirlo al final. */
    public String usageSummary() {
        return "[IA] Consumo de la corrida: " + callsWithUsage.get() + " llamada(s) con dato de uso, "
                + promptTokens.get() + " tokens de entrada y " + completionTokens.get() + " de salida"
                + " (no cuenta una segunda peticion descartada ni el calentamiento)";
    }

    private static String secondsSince(long startNanos) {
        return String.format(Locale.ROOT, "%.1f", (System.nanoTime() - startNanos) / 1_000_000_000.0);
    }

    private static String snippet(String text) {
        if (text == null) {
            return "(vacio)";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }

    /** imgTagStyle=false: formato estandar de mensajes con partes texto/imagen; true: la imagen como &lt;img&gt; dentro del texto. */
    String buildRequestBody(BufferedImage crop, String prompt, boolean imgTagStyle) throws IOException {
        String dataUri = "data:image/jpeg;base64," + encodeJpegBase64(crop, maxImageSide);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        if (imgTagStyle) {
            message.addProperty("content", prompt + " <img src=\"" + dataUri + "\" />");
        } else {
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", prompt);

            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", dataUri);
            JsonObject imagePart = new JsonObject();
            imagePart.addProperty("type", "image_url");
            imagePart.add("image_url", imageUrl);

            JsonArray content = new JsonArray();
            content.add(textPart);
            content.add(imagePart);
            message.add("content", content);
        }
        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", 200);
        body.addProperty("temperature", 0.1);
        body.addProperty("stream", false);
        return body.toString();
    }

    static String encodeJpegBase64(BufferedImage src) throws IOException {
        return encodeJpegBase64(src, DEFAULT_MAX_IMAGE_SIDE_PX);
    }

    /** Reduce el recorte (lado mayor maximo maxSide) y lo codifica en JPEG base64: la foto original no sale entera. */
    static String encodeJpegBase64(BufferedImage src, int maxSide) throws IOException {
        double scale = Math.min(1.0, (double) maxSide / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** Extrae el texto del modelo de la respuesta del servicio y lo convierte en la frase final (o null). */
    static String parseResponse(String responseBody) {
        return formatDescription(extractContent(responseBody));
    }

    /** Texto que escribio el modelo: choices[0].message.content de la respuesta del servicio. */
    static String extractContent(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        return root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
    }

    /**
     * Toma el JSON que devolvio el modelo (a veces envuelto en ```json o con texto alrededor) y arma
     * "ropa superior: ...; ropa inferior: ...". Devuelve null si no hay JSON o no quedo ningun dato util.
     */
    static String formatDescription(String modelContent) {
        JsonObject fields = parseJsonObject(modelContent);
        if (fields == null) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (String[] field : FIELDS) {
            String value = withoutSides(resolveAlternatives(renderValue(fields.get(field[0]))));
            String normalized = value.toLowerCase(Locale.ROOT);
            if (value.isEmpty() || normalized.equals("no se ve") || normalized.equals("no se aprecia")) {
                continue;
            }
            if (value.length() > MAX_FIELD_CHARS) {
                value = value.substring(0, MAX_FIELD_CHARS);
            }
            parts.add(field[1] + ": " + value);
        }
        if (parts.isEmpty()) {
            return null;
        }
        String description = String.join("; ", parts);
        return description.length() > MAX_DESCRIPTION_CHARS ? description.substring(0, MAX_DESCRIPTION_CHARS) : description;
    }

    /** Valor de "tatuajes" del JSON del modelo ("ninguno" incluido), o null si no hay JSON o dice que no se ve. */
    static String formatTattoos(String modelContent) {
        JsonObject fields = parseJsonObject(modelContent);
        if (fields == null) {
            return null;
        }
        String value = withoutSides(resolveAlternatives(renderValue(fields.get("tatuajes"))));
        String normalized = value.toLowerCase(Locale.ROOT);
        if (value.isEmpty() || normalized.equals("no se ve") || normalized.equals("no se aprecia")) {
            return null;
        }
        return value.length() > MAX_FIELD_CHARS ? value.substring(0, MAX_FIELD_CHARS) : value;
    }

    /**
     * Convierte un valor del JSON del modelo en texto legible. El modelo a veces devuelve listas u
     * objetos en vez de una frase (ej. [{"descripcion": "un arco", "parte": "antebrazo"}]): se leen
     * como "un arco (antebrazo)". Devuelve "" si no hay nada.
     */
    private static String renderValue(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "";
        }
        if (el.isJsonPrimitive()) {
            return el.getAsString().trim();
        }
        if (el.isJsonArray()) {
            List<String> items = new ArrayList<>();
            for (JsonElement item : el.getAsJsonArray()) {
                String text = renderValue(item);
                if (!text.isBlank()) {
                    items.add(text);
                }
            }
            return String.join(", ", items);
        }
        JsonObject object = el.getAsJsonObject();
        JsonElement description = object.get("descripcion");
        JsonElement part = object.get("parte");
        if (description != null && part != null) {
            return renderValue(description) + " (" + renderValue(part) + ")";
        }
        List<String> values = new ArrayList<>();
        for (var entry : object.entrySet()) {
            String text = renderValue(entry.getValue());
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return String.join(" ", values);
    }

    /**
     * Si el modelo copio las alternativas del formato ("brazo, negro | antebrazo, negro | ninguno"), se queda con lo
     * concreto y descarta "ninguno"/"no se ve", que contradicen a lo demas. Si solo hay "ninguno" queda "ninguno".
     * Un texto sin barras no cambia.
     */
    static String resolveAlternatives(String value) {
        if (!value.contains("|")) {
            return value;
        }
        List<String> concrete = new ArrayList<>();
        boolean none = false;
        for (String part : value.split("\\|")) {
            String text = part.trim();
            String normalized = text.toLowerCase(Locale.ROOT);
            if (text.isEmpty() || normalized.equals("no se ve") || normalized.equals("no se aprecia")) {
                continue;
            }
            if (normalized.equals("ninguno") || normalized.equals("ninguna") || normalized.equals("no hay")
                    || normalized.equals("sin tatuajes")) {
                none = true;
                continue;
            }
            concrete.add(text);
        }
        if (!concrete.isEmpty()) {
            return String.join(" y ", concrete);
        }
        return none ? "ninguno" : "no se ve";
    }

    /**
     * Quita "izquierdo/derecha..." del texto: aunque el prompt lo pide, los modelos lo ponen igual y se equivocan de
     * lado con frecuencia (el brazo de la persona es el opuesto en la imagen). "En el brazo izquierdo, un tatuaje"
     * queda "En el brazo, un tatuaje".
     */
    static String withoutSides(String text) {
        String cleaned = text.replaceAll("(?i)\\s*\\b(izquierd[oa]s?|derech[oa]s?)\\b", "");
        return cleaned.replaceAll("\\s+,", ",").replaceAll("\\s{2,}", " ").trim();
    }

    /** true si el texto del modelo contiene un objeto JSON (aunque no traiga datos utiles). */
    static boolean hasJson(String modelContent) {
        return parseJsonObject(modelContent) != null;
    }

    /** Extrae el primer objeto JSON del texto del modelo (a veces viene dentro de ```json o con texto alrededor), o null. */
    private static JsonObject parseJsonObject(String modelContent) {
        if (modelContent == null) {
            return null;
        }
        int start = modelContent.indexOf('{');
        int end = modelContent.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JsonParser.parseString(modelContent.substring(start, end + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
