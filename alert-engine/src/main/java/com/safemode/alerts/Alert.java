package com.safemode.alerts;

import java.time.Instant;

/**
 * Una alerta lista para mostrarse en el centro de alertas. eventId + eventOccurredAt identifican el evento de presencia
 * que la origino (los ids se reinician si se vacia la tabla, por eso se guarda tambien la hora).
 */
public record Alert(long id, long eventId, Instant eventOccurredAt, AlertType type, Severity severity, String className,
                    String message, String framePath, Instant createdAt, boolean acknowledged) {}
