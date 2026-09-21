package com.safemode.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayRunnerTest {

    @Test
    void sinVariableEscuchaSoloEnEstaMaquina() {
        assertEquals("localhost", GatewayRunner.bindHost(null));
        assertEquals("localhost", GatewayRunner.bindHost("  "));
    }

    @Test
    void conVariableEscuchaDondeSeIndique() {
        assertEquals("0.0.0.0", GatewayRunner.bindHost(" 0.0.0.0 "));
    }
}
