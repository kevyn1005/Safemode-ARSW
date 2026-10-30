package com.safemode.gateway.notify;

import com.safemode.alerts.Alert;
import com.safemode.alerts.AlertType;
import com.safemode.alerts.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationServiceTest {

    private static final Instant WHEN = Instant.parse("2026-10-11T15:42:10Z");
    private static final long[] FAST_RETRIES = {10, 10};

    /** Canal falso: anota lo que se le manda; puede fallar las primeras veces o quedarse esperando. */
    private static class FakeChannel implements NotificationChannel {
        final String name;
        final List<String> recipients;
        final List<String> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        volatile int failures;                 // cuantos envios fallan antes de que uno salga bien
        volatile CountDownLatch hold;          // si no es null, el envio espera a que se abra

        FakeChannel(String name, String... recipients) {
            this.name = name;
            this.recipients = List.of(recipients);
        }

        @Override public String name() { return name; }

        @Override public List<String> recipients() { return recipients; }

        @Override
        public void send(String recipient, String text) throws NotificationException {
            attempts.incrementAndGet();
            if (hold != null) {
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failures > 0) {
                failures--;
                throw new NotificationException("fallo de prueba");
            }
            sent.add(recipient + " | " + text);
        }
    }

    private NotificationService service;
    private PrintStream originalErr;
    private ByteArrayOutputStream errors;

    private NotificationService service(FakeChannel... channels) {
        service = new NotificationService(List.of(channels), alert -> "CAM 1", ZoneId.of("UTC"), FAST_RETRIES);
        return service;
    }

    private void captureErrors() {
        originalErr = System.err;
        errors = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void cleanUp() {
        if (originalErr != null) {
            System.setErr(originalErr);
        }
        if (service != null) {
            service.close();
        }
    }

    private static Alert alert(long id, Severity severity, AlertType type) {
        return new Alert(id, id, WHEN, type, severity, "backpack", "mensaje", "frames/x.png", WHEN, false);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "no se cumplio a tiempo");
    }

    @Test
    void avisanLasAltasYMediasYNoLasBajas() throws Exception {
        FakeChannel channel = new FakeChannel("fake", "u1");
        service(channel);

        service.onAlert(alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT));
        service.onAlert(alert(2, Severity.MEDIUM, AlertType.POSSIBLE_OWNER_RETURN));
        service.onAlert(alert(3, Severity.LOW, AlertType.UNKNOWN_OWNER_REMOVAL));

        waitUntil(() -> channel.sent.size() >= 2);
        Thread.sleep(200);
        assertEquals(2, channel.sent.size(), "la baja queda solo en el panel");
        assertTrue(channel.sent.get(0).contains("posible robo") || channel.sent.get(1).contains("posible robo"));
    }

    @Test
    void elTextoEsElAcordadoYSoloTexto() throws Exception {
        FakeChannel channel = new FakeChannel("fake", "u1");
        service(channel);

        service.onAlert(alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT));

        waitUntil(() -> channel.sent.size() == 1);
        assertEquals("u1 | SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.", channel.sent.get(0));
    }

    @Test
    void cadaAlertaSeAvisaUnaSolaVezPorCanalYDestinatario() throws Exception {
        FakeChannel channel = new FakeChannel("fake", "u1", "u2");
        service(channel);
        Alert alert = alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT);

        service.onAlert(alert);
        service.onAlert(alert);   // el mismo aviso otra vez (por ejemplo, un reproceso)

        waitUntil(() -> channel.sent.size() >= 2);
        Thread.sleep(200);
        assertEquals(2, channel.sent.size());
        assertEquals(2, channel.attempts.get());
    }

    @Test
    void onAlertVuelveAlInstanteAunqueElCanalSeCuelgue() throws Exception {
        FakeChannel stuck = new FakeChannel("lento", "u1");
        stuck.hold = new CountDownLatch(1);
        service(stuck);

        long start = System.nanoTime();
        service.onAlert(alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(millis < 500, "tardo " + millis + " ms");
        stuck.hold.countDown();
    }

    @Test
    void unCanalColgadoNoFrenaALosOtros() throws Exception {
        FakeChannel stuck = new FakeChannel("lento", "u1");
        stuck.hold = new CountDownLatch(1);
        FakeChannel quick = new FakeChannel("rapido", "u2");
        service(stuck, quick);

        service.onAlert(alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT));

        waitUntil(() -> quick.sent.size() == 1);
        assertEquals(0, stuck.sent.size(), "el otro canal sigue esperando y no impidio el aviso");
        stuck.hold.countDown();
        waitUntil(() -> stuck.sent.size() == 1);
    }

    @Test
    void siUnEnvioFallaSeReintentaYAlFinalSale() throws Exception {
        captureErrors();
        FakeChannel flaky = new FakeChannel("fake", "u1");
        flaky.failures = 2;
        service(flaky);

        service.onAlert(alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT));

        waitUntil(() -> flaky.sent.size() == 1);
        assertEquals(3, flaky.attempts.get());
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("(intento 1/3)"));
    }

    @Test
    void sinExitoSeDejaDeIntentarTrasLosIntentosMaximosYElRegistroNoLlevaDestinatarios() throws Exception {
        captureErrors();
        FakeChannel broken = new FakeChannel("fake", "999888777");
        broken.failures = 100;
        service(broken);

        service.onAlert(alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT));

        waitUntil(() -> errors.toString(StandardCharsets.UTF_8).contains("se deja de intentar"));
        Thread.sleep(200);
        assertEquals(NotificationService.MAX_ATTEMPTS, broken.attempts.get(), "ni uno mas");
        String log = errors.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("999888777"), "los destinatarios no se escriben en los registros");
        assertTrue(log.contains("fallo de prueba"));
    }

    @Test
    void sinCanalesNoHaceNadaNiLanza() {
        service = new NotificationService(List.of(), alert -> "CAM 1", ZoneId.of("UTC"), FAST_RETRIES);

        assertFalse(service.isActive());
        service.onAlert(alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT));
    }

    @Test
    void sinVariablesDeEntornoNoHayCanalesYConLasVariablesSiHay() {
        service = NotificationService.fromEnvironment(Map.of(), alert -> "CAM 1");
        assertFalse(service.isActive());
        service.close();

        service = NotificationService.fromEnvironment(Map.of(TelegramChannel.TOKEN_VARIABLE, "123456789:AAH-fake_TOKEN-0123456789",
                TelegramChannel.CHATS_VARIABLE, "111"), alert -> "CAM 1");
        assertTrue(service.isActive());
        assertEquals("telegram (1 destinatario)", service.describe());
    }

    @Test
    void despuesDeCerrarNoEnviaNiLanza() throws Exception {
        FakeChannel channel = new FakeChannel("fake", "u1");
        service(channel);
        service.close();

        service.onAlert(alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT));

        Thread.sleep(200);
        assertEquals(0, channel.sent.size());
    }

    @Test
    void siNoSePuedeResolverLaCamaraElServicioNoLanzaYAnotaElFallo() throws Exception {
        captureErrors();
        FakeChannel channel = new FakeChannel("fake", "u1");
        service = new NotificationService(List.of(channel), alert -> {
            throw new IllegalStateException("base cerrada con datos secretos");
        }, ZoneId.of("UTC"), FAST_RETRIES);

        service.onAlert(alert(1, Severity.HIGH, AlertType.POSSIBLE_THEFT));

        waitUntil(() -> errors.toString(StandardCharsets.UTF_8).contains("No se pudo preparar el aviso"));
        String log = errors.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("datos secretos"), "solo el tipo de la excepcion, no su mensaje");
        assertEquals(0, channel.sent.size());
    }
}
