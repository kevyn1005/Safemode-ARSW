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
    // el servicio gratuito a veces encola: se da margen porque la llamada corre en segundo plano
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private static final int MAX_IMAGE_SIDE_PX = 640;
    private static final int MAX_FIELD_CHARS = 120;
    private static final int MAX_DESCRIPTION_CHARS = 900;

    private static final String PROMPT = "Analiza la imagen de una persona para un reporte de seguridad. "
            + "Responde SOLO con un objeto JSON con estas claves, cada valor en espanol y muy corto: "
            + "\"ropa_superior\", \"ropa_inferior\", \"calzado\", \"tatuajes\", \"lentes\", \"cabello\". "
            + "Para \"tatuajes\" y \"lentes\" revisa con cuidado brazos, antebrazos, cuello y cara: si hay, "
            + "di cuales y en que parte del cuerpo; escribe \"ninguno\" si esa zona se ve y no hay; "
            + "usa \"no se ve\" solo si la zona no aparece en la imagen. "
            + "Usa \"no se ve\" tambien en cualquier otra clave que no se aprecie. "
            + "No estimes estatura, edad ni identidad, y no uses izquierda ni derecha.";

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

    public NvidiaOwnerVisionDescriber(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL, DEFAULT_TIMEOUT);
    }

    NvidiaOwnerVisionDescriber(String apiKey, String baseUrl, String model, Duration timeout) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeout = timeout;
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
                model == null || model.isBlank() ? DEFAULT_MODEL : model.trim(), DEFAULT_TIMEOUT);
    }

    public String modelName() {
        return model;
    }

    @Override
    public String describe(BufferedImage ownerCrop) {
        long startedAt = System.nanoTime();
        String content = fetchModelContent(ownerCrop);
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

    /** Envia el recorte al servicio y devuelve el texto crudo que escribio el modelo, o null si fallo (ya avisa por consola). */
    String fetchModelContent(BufferedImage ownerCrop) {
        long startedAt = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(ownerCrop)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[IA] El servicio respondio HTTP " + response.statusCode() + " en " + secondsSince(startedAt)
                        + " s: " + snippet(response.body()));
                return null;
            }
            return extractContent(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[IA] Descripcion cancelada tras " + secondsSince(startedAt) + " s");
            return null;
        } catch (IOException | RuntimeException e) {
            System.err.println("[IA] No se pudo describir al dueno tras " + secondsSince(startedAt) + " s ("
                    + e.getClass().getSimpleName() + "): " + e.getMessage());
            return null;
        }
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

    String buildRequestBody(BufferedImage crop) throws IOException {
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", PROMPT);

        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/jpeg;base64," + encodeJpegBase64(crop));
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", imageUrl);

        JsonArray content = new JsonArray();
        content.add(textPart);
        content.add(imagePart);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
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

    /** Reduce el recorte (max. 640 px por lado) y lo codifica en JPEG base64: la foto original no sale entera. */
    static String encodeJpegBase64(BufferedImage src) throws IOException {
        double scale = Math.min(1.0, (double) MAX_IMAGE_SIDE_PX / Math.max(src.getWidth(), src.getHeight()));
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
        if (modelContent == null) {
            return null;
        }
        int start = modelContent.indexOf('{');
        int end = modelContent.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonObject fields;
        try {
            fields = JsonParser.parseString(modelContent.substring(start, end + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (String[] field : FIELDS) {
            JsonElement el = fields.get(field[0]);
            if (el == null || el.isJsonNull()) {
                continue;
            }
            String value = (el.isJsonPrimitive() ? el.getAsString() : el.toString()).trim();
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
}
