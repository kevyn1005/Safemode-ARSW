package com.safemode.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Gson compartido y lectura estricta de cuerpos JSON (un cuerpo mal formado es un 400, nunca un 500). */
public final class Json {

    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Json() {}

    /** Lee un objeto JSON; {@link HttpError} 400 si el texto esta vacio, mal formado o no es un objeto. */
    public static JsonObject parseObject(String text) {
        if (text == null || text.isBlank()) {
            throw new HttpError(400, "Se espera un cuerpo JSON");
        }
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw new HttpError(400, "Se espera un objeto JSON");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new HttpError(400, "JSON mal formado");
        }
    }
}
