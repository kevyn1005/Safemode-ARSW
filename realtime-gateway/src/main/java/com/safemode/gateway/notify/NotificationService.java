package com.safemode.gateway.notify;

import com.safemode.alerts.Alert;
import com.safemode.alerts.Severity;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Servicio de notificaciones (ADR 0004): se engancha al motor de alertas con {@code engine.addListener(service::onAlert)} y avisa
 * de cada alerta NUEVA por los canales configurados.
 * <ul>
 *   <li>Solo avisan las severidades ALTA y MEDIA; la BAJA queda solo en el panel para no saturar al operador.</li>
 *   <li>Cada aviso se manda UNA sola vez por alerta, canal y destinatario.</li>
 *   <li>Todo el envio ocurre en un grupo de hilos aparte: {@link #onAlert} vuelve al instante, y un canal que falla o se cuelga
 *       nunca retrasa la alerta ni a los otros canales (mismo principio que la IA en segundo plano).</li>
 *   <li>Si un envio falla se reintenta un numero limitado de veces con espera entre intentos, sin bloquear ningun hilo; el fallo
 *       queda en el registro SIN claves, URLs ni destinatarios.</li>
 * </ul>
 */
public final class NotificationService implements AutoCloseable {

    static final int MAX_ATTEMPTS = 3;
    private static final long[] DEFAULT_BACKOFF_MILLIS = {1_000, 4_000};   // antes del 2.o y del 3.er intento
    private static final int REMEMBERED_NOTICES = 10_000;

    private final List<NotificationChannel> channels;
    private final Function<Alert, String> cameraNameOf;
    private final ZoneId zone;
    private final long[] backoffMillis;
    private final ScheduledThreadPoolExecutor pool;
    private final Set<String> alreadySent = ConcurrentHashMap.newKeySet();

    public NotificationService(List<NotificationChannel> channels, Function<Alert, String> cameraNameOf) {
        this(channels, cameraNameOf, ZoneId.systemDefault(), DEFAULT_BACKOFF_MILLIS);
    }

    NotificationService(List<NotificationChannel> channels, Function<Alert, String> cameraNameOf, ZoneId zone, long[] backoffMillis) {
        this.channels = List.copyOf(channels);
        this.cameraNameOf = cameraNameOf;
        this.zone = zone;
        this.backoffMillis = backoffMillis.clone();
        this.pool = new ScheduledThreadPoolExecutor(4, runnable -> {
            Thread thread = new Thread(runnable, "notify");
            thread.setDaemon(true);
            return thread;
        });
        this.pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /** El servicio con los canales que haya configurados en las variables de entorno (puede no tener ninguno). */
    public static NotificationService fromEnvironment(Map<String, String> env, Function<Alert, String> cameraNameOf) {
        List<NotificationChannel> channels = new ArrayList<>();
        TelegramChannel.fromEnvironment(env).ifPresent(channels::add);
        return new NotificationService(channels, cameraNameOf);
    }

    /** Si hay algun canal por el que avisar. */
    public boolean isActive() {
        return !channels.isEmpty();
    }

    /** Resumen para el arranque, sin destinatarios: por ejemplo {@code telegram (2 chats)}. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (NotificationChannel channel : channels) {
            int n = channel.recipients().size();
            parts.add(channel.name() + " (" + n + (n == 1 ? " destinatario)" : " destinatarios)"));
        }
        return String.join(", ", parts);
    }

    static boolean notifies(Severity severity) {
        return severity == Severity.HIGH || severity == Severity.MEDIUM;
    }

    /** Llamado por el motor de alertas con cada alerta nueva. Nunca bloquea ni lanza. */
    public void onAlert(Alert alert) {
        if (!notifies(alert.severity()) || channels.isEmpty()) {
            return;
        }
        try {
            pool.execute(() -> fanOut(alert));
        } catch (RejectedExecutionException e) {
            // el servicio ya se cerro
        }
    }

    private void fanOut(Alert alert) {
        try {
            String text = NotificationText.of(alert, cameraNameOf.apply(alert), zone);
            for (NotificationChannel channel : channels) {
                for (String recipient : channel.recipients()) {
                    if (!alreadySent.add(alert.id() + "|" + alert.eventOccurredAt().toEpochMilli() + "|" + channel.name() + "|" + recipient)) {
                        continue;   // ya se aviso de esta alerta a este destinatario por este canal
                    }
                    pool.execute(() -> attempt(channel, recipient, text, alert.id(), 1));
                }
            }
            if (alreadySent.size() > REMEMBERED_NOTICES) {
                alreadySent.clear();   // acotado: una alerta tan vieja ya no se repite
            }
        } catch (RejectedExecutionException e) {
            // el servicio ya se cerro
        } catch (RuntimeException e) {
            System.err.println("[NOTIF] No se pudo preparar el aviso de la alerta #" + alert.id() + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void attempt(NotificationChannel channel, String recipient, String text, long alertId, int attempt) {
        try {
            channel.send(recipient, text);
            System.out.println("[NOTIF] Aviso de la alerta #" + alertId + " enviado por " + channel.name());
        } catch (NotificationException | RuntimeException e) {
            String reason = e instanceof NotificationException ? e.getMessage()
                    : "error inesperado (" + e.getClass().getSimpleName() + ")";
            if (attempt >= MAX_ATTEMPTS) {
                System.err.println("[NOTIF] No se pudo avisar de la alerta #" + alertId + " por " + channel.name() + ": " + reason
                        + "; se deja de intentar.");
                return;
            }
            System.err.println("[NOTIF] Fallo el aviso de la alerta #" + alertId + " por " + channel.name() + " (intento " + attempt + "/"
                    + MAX_ATTEMPTS + "): " + reason + "; se reintenta.");
            try {
                pool.schedule(() -> attempt(channel, recipient, text, alertId, attempt + 1),
                        backoffMillis[Math.min(attempt - 1, backoffMillis.length - 1)], TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException closed) {
                // el servicio se cerro mientras tanto
            }
        }
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
