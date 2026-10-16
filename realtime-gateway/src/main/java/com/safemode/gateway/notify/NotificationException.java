package com.safemode.gateway.notify;

/** Fallo al avisar por un canal. El mensaje es seguro para los registros: nunca lleva claves, URLs ni destinatarios. */
public class NotificationException extends Exception {

    public NotificationException(String safeMessage) {
        super(safeMessage);
    }
}
