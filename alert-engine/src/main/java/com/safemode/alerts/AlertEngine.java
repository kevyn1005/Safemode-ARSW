package com.safemode.alerts;

import com.safemode.presence.PresenceEventStore;
import com.safemode.presence.PresenceEventStore.StoredEvent;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Motor de alertas: revisa los eventos de retiro que guarda el tracker, aplica {@link AlertRules} y guarda una alerta
 * por cada retiro que lo merezca. Quien quiera enterarse al instante (por ejemplo el realtime-gateway, para empujar la
 * alerta al dashboard por WebSocket) se registra con {@link #addListener}.
 */
public class AlertEngine implements AutoCloseable {

    private final PresenceEventStore events;
    private final AlertStore alerts;
    private final Clock clock;
    private final List<Consumer<Alert>> listeners = new CopyOnWriteArrayList<>();
    private long lastEventId = 0;
    private ScheduledExecutorService scheduler;

    public AlertEngine(PresenceEventStore events, AlertStore alerts) {
        this(events, alerts, Clock.systemUTC());
    }

    AlertEngine(PresenceEventStore events, AlertStore alerts, Clock clock) {
        this.events = events;
        this.alerts = alerts;
        this.clock = clock;
    }

    public void addListener(Consumer<Alert> listener) {
        listeners.add(listener);
    }

    /**
     * Procesa los retiros nuevos desde la ultima vez y devuelve las alertas creadas. Si la tabla de eventos se vacio
     * (ids reiniciados) vuelve a leer desde el principio; un evento que ya tiene alerta no genera otra.
     */
    public synchronized List<Alert> processNewEvents() {
        if (events.maxEventId() < lastEventId) {
            lastEventId = 0;
        }
        List<Alert> created = new ArrayList<>();
        for (StoredEvent event : events.findRemovedAfter(lastEventId)) {
            lastEventId = event.id();
            Optional<AlertRules.Decision> decision = AlertRules.evaluate(event);
            if (decision.isEmpty()) {
                continue;
            }
            AlertRules.Decision d = decision.get();
            alerts.insertIfAbsent(event.id(), event.occurredAt(), d.type(), d.severity(), event.className(),
                            d.message(), event.framePath(), clock.instant())
                    .ifPresent(alert -> {
                        created.add(alert);
                        notifyListeners(alert);
                    });
        }
        return created;
    }

    private void notifyListeners(Alert alert) {
        for (Consumer<Alert> listener : listeners) {
            try {
                listener.accept(alert);
            } catch (RuntimeException e) {
                System.err.println("[ALERTAS] Un listener fallo: " + e.getMessage());
            }
        }
    }

    /** El evento de presencia que origino la alerta, con los datos mas recientes (la descripcion por IA llega despues). */
    public Optional<StoredEvent> evidenceOf(Alert alert) {
        return events.findById(alert.eventId());
    }

    /** Revisa cada cierto tiempo, en un hilo aparte, hasta {@link #stop()} o {@link #close()}. */
    public synchronized void start(Duration every) {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "alert-engine");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                processNewEvents();
            } catch (RuntimeException e) {
                System.err.println("[ALERTAS] No se pudieron procesar los eventos: " + e.getMessage());
            }
        }, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
