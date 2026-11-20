package com.safemode.common;

import org.junit.jupiter.api.Test;

import java.security.Security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvAndNetworkDefaultsTest {

    /** Un nombre que no existe en ningun entorno real. */
    private static final String AUSENTE = "SAFEMODE_PRUEBA_VARIABLE_QUE_NO_EXISTE";

    @Test
    void sinVariableDevuelveElValorPorDefecto() {
        assertEquals("por-defecto", Env.get(AUSENTE, "por-defecto"));
        assertEquals(42, Env.getInt(AUSENTE, 42));
    }

    @Test
    void unaVariableDefinidaGanaAlValorPorDefecto() {
        // PATH existe en Windows, Linux y macOS
        assertNotEquals("por-defecto", Env.get("PATH", "por-defecto"));
    }

    @Test
    void unEnteroInvalidoEsErrorDeConfiguracionNoUnValorSilencioso() {
        assertThrows(IllegalStateException.class, () -> Env.getInt("PATH", 1));
    }

    @Test
    void laCacheNegativaDeDnsQuedaEnUnSegundo() {
        String antes = Security.getProperty("networkaddress.cache.negative.ttl");
        try {
            NetworkDefaults.shortNegativeDnsCache();
            assertEquals("1", Security.getProperty("networkaddress.cache.negative.ttl"));
        } finally {
            Security.setProperty("networkaddress.cache.negative.ttl", antes == null ? "10" : antes);
        }
    }
}
