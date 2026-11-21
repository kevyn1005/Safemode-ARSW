package com.safemode.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRunnerTest {

    @Test
    void sinVariableEscuchaSoloEnEstaMaquina() {
        assertEquals("localhost", GatewayRunner.bindHost(null));
        assertEquals("localhost", GatewayRunner.bindHost("  "));
    }

    @Test
    void elBotonDeVigilanciaSoloSeOfreceCuandoElGatewayEscuchaSoloEnEstaMaquina() {
        assertTrue(GatewayRunner.trackerButtonAllowed("localhost"));
        assertTrue(GatewayRunner.trackerButtonAllowed("127.0.0.1"));
        assertFalse(GatewayRunner.trackerButtonAllowed("0.0.0.0"));   // contenedor / abierto a la red
        assertFalse(GatewayRunner.trackerButtonAllowed("192.168.1.5"));
    }

    @Test
    void conVariableEscuchaDondeSeIndique() {
        assertEquals("0.0.0.0", GatewayRunner.bindHost(" 0.0.0.0 "));
    }

    @Test
    void lasMetricasEscuchanDondeEscuchaElPanelSiNoSeIndicaOtraDireccion() {
        // en el computador: solo esta maquina (antes era 0.0.0.0 y cualquier equipo de la red las podia leer)
        assertEquals("localhost", GatewayRunner.metricsHost(null, GatewayRunner.bindHost(null)));
        assertEquals("localhost", GatewayRunner.metricsHost("  ", "localhost"));
        // en Docker la imagen fija SAFEMODE_BIND_HOST=0.0.0.0: Prometheus sigue llegando por la red del compose
        assertEquals("0.0.0.0", GatewayRunner.metricsHost(null, GatewayRunner.bindHost("0.0.0.0")));
    }

    @Test
    void conVariableLasMetricasEscuchanDondeSeIndique() {
        assertEquals("0.0.0.0", GatewayRunner.metricsHost(" 0.0.0.0 ", "localhost"));  // p. ej. un Prometheus en Docker sobre Linux
        assertEquals("127.0.0.1", GatewayRunner.metricsHost("127.0.0.1", "0.0.0.0"));
    }

    @Test
    void soloLocalhostYLoopbackSonSoloEstaMaquina() {
        assertTrue(GatewayRunner.loopbackOnly("localhost"));
        assertTrue(GatewayRunner.loopbackOnly("127.0.0.1"));
        assertFalse(GatewayRunner.loopbackOnly("0.0.0.0"));
        assertFalse(GatewayRunner.loopbackOnly("192.168.1.5"));
    }

    @Test
    void sinConfigurarLasSesionesSiguenEnMemoriaComoSiempre() {
        assertTrue(GatewayRunner.sessionStoreFor(null, "x") instanceof SessionStore.InMemory);
        assertTrue(GatewayRunner.sessionStoreFor("  ", "x") instanceof SessionStore.InMemory);
        assertTrue(GatewayRunner.sessionStoreFor("memory", "x") instanceof SessionStore.InMemory);
    }

    @Test
    void unModoDeSesionesDesconocidoFallaAlArrancarEnVezDeCaerEnMemoriaEnSilencio() {
        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> GatewayRunner.sessionStoreFor("databse", "x"));   // error de escritura tipico
        assertTrue(error.getMessage().contains("'memory' o 'database'"));
    }
}
