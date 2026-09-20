package com.safemode.presence;

import java.awt.image.BufferedImage;

/**
 * Describe al dueno de un objeto a partir de su recorte, tipicamente con un modelo de vision y
 * lenguaje remoto. Puede tardar segundos: {@link ObjectTracker} lo llama en un hilo aparte y
 * nunca dentro del ciclo de frames.
 */
public interface OwnerVisionDescriber {

    /** Devuelve una descripcion en espanol, o {@code null} si no se pudo obtener (no debe lanzar excepciones). */
    String describe(BufferedImage ownerCrop);
}
