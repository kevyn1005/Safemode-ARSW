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

    /**
     * Pregunta solo por los tatuajes sobre un recorte de los antebrazos (ver {@link ArmsCropper}).
     * Devuelve lo que se ve, "ninguno" si se ven y no hay, o {@code null} si no se pudo saber.
     * Por defecto no hace nada: es opcional para quien implemente esta interfaz.
     */
    default String describeArms(BufferedImage armsCrop) {
        return null;
    }
}
