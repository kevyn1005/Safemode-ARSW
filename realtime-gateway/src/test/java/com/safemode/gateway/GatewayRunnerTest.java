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
}
