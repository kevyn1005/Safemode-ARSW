package com.safemode.gateway.notify;

import java.util.List;

/**
 * Un canal por el que se avisa de una alerta (Telegram, y mas adelante correo). Cada canal sabe a quienes avisar y como mandar UN
 * mensaje de texto; los reintentos, el "una sola vez por alerta" y que un canal lento no frene a los demas los resuelve
 * {@link NotificationService}. Asi un canal nuevo no toca el resto y las pruebas usan un canal falso.
 */
public interface NotificationChannel {

    /** Nombre corto del canal para los mensajes de registro (sin datos personales). */
    String name();

    /** A quienes se avisa por este canal (por ejemplo los chats de Telegram). Nunca se escriben en los registros. */
    List<String> recipients();

    /**
     * Manda el texto a un destinatario. Si falla lanza {@link NotificationException} con un mensaje que SE PUEDE escribir en los
     * registros: sin la clave, sin la URL y sin el destinatario.
     */
    void send(String recipient, String text) throws NotificationException;
}
