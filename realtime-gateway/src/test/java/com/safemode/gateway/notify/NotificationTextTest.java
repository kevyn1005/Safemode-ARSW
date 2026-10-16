package com.safemode.gateway.notify;

import com.safemode.alerts.Alert;
import com.safemode.alerts.AlertType;
import com.safemode.alerts.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationTextTest {

    private static final Instant WHEN = Instant.parse("2026-10-11T15:42:10Z");

    private static Alert alert(AlertType type) {
        return new Alert(5, 9, WHEN, type, Severity.HIGH, "backpack", "Posible robo: la mochila fue retirada por alguien distinto del dueño",
                "frames/obj1_REMOVED_1.png", WHEN.plusSeconds(1), false);
    }

    @Test
    void elAvisoDiceQuePasoEnQueCamaraYAQueHoraYMandaAlPanel() {
        assertEquals("SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.",
                NotificationText.of(alert(AlertType.POSSIBLE_THEFT), "CAM 1", ZoneId.of("UTC")));
    }

    @Test
    void laHoraEsLaDeLaZonaIndicada() {
        assertTrue(NotificationText.of(alert(AlertType.POSSIBLE_THEFT), "CAM 1", ZoneId.of("America/Bogota")).contains("a las 10:42."));
    }

    @Test
    void cadaTipoDeAlertaTieneSuFrase() {
        for (AlertType type : AlertType.values()) {
            String text = NotificationText.of(alert(type), "Cancha sur", ZoneId.of("UTC"));
            assertTrue(text.startsWith("SafeMode: " + NotificationText.phrase(type) + " en Cancha sur a las "), text);
        }
        assertEquals("posible dueño que volvió", NotificationText.phrase(AlertType.POSSIBLE_OWNER_RETURN));
    }

    @Test
    void elAvisoNoLlevaDatosDePersonasNiDelObjeto() {
        Alert alert = alert(AlertType.POSSIBLE_THEFT);
        String text = NotificationText.of(alert, "CAM 1", ZoneId.of("UTC"));

        assertFalse(text.contains(alert.message()), "no se copia el mensaje de la alerta");
        assertFalse(text.contains(alert.className()));
        assertFalse(text.contains(alert.framePath()), "ni rutas de fotos");
        assertFalse(text.toLowerCase().contains("camisa"));
    }

    @Test
    void elNombreDeLaCamaraSeLimpia() {
        assertEquals("CAM 1", NotificationText.cleanCamera("  CAM 1\n"));
        assertEquals("a b", NotificationText.cleanCamera("a\u0000b"));
        assertEquals("la cámara", NotificationText.cleanCamera(null));
        assertEquals("la cámara", NotificationText.cleanCamera("   "));
        assertEquals(40, NotificationText.cleanCamera("x".repeat(100)).length());
    }
}
