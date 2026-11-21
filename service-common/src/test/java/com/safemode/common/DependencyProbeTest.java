package com.safemode.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyProbeTest {

    /** Valor publicado para una dependencia: 1.0 responde, 0.0 caida (o null si no se publico). */
    private static Double published(ServiceMetrics metrics, String dependency) {
        Matcher m = Pattern.compile("safemode_dependency_up\\{[^}]*dependency=\"" + dependency + "\"[^}]*}\\s+([0-9.]+)").matcher(metrics.scrape());
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

    @Test
    void publicaUnoSiLaDependenciaResponde() {
        ServiceMetrics metrics = new ServiceMetrics("prueba");
        try (DependencyProbe probe = new DependencyProbe(metrics).add("postgres", () -> true)) {
            probe.probeAll();
        }
        assertEquals(1.0, published(metrics, "postgres"));
    }

    @Test
    void publicaCeroSiLaDependenciaFallaOLanzaExcepcion() {
        ServiceMetrics metrics = new ServiceMetrics("prueba");
        try (DependencyProbe probe = new DependencyProbe(metrics)
                .add("redis", () -> false)
                .add("mongo", () -> {
                    throw new IllegalStateException("sin conexion");
                })) {
            probe.probeAll();
        }
        assertEquals(0.0, published(metrics, "redis"));
        assertEquals(0.0, published(metrics, "mongo"), "una excepcion del sondeo es 'caida', no rompe al sondeador");
    }

    @Test
    void siLaDependenciaVuelveElValorVuelveAUno() {
        ServiceMetrics metrics = new ServiceMetrics("prueba");
        AtomicBoolean up = new AtomicBoolean(false);
        try (DependencyProbe probe = new DependencyProbe(metrics).add("postgres", up::get)) {
            probe.probeAll();
            assertEquals(0.0, published(metrics, "postgres"));
            up.set(true);
            probe.probeAll();
            assertEquals(1.0, published(metrics, "postgres"));
        }
    }

    @Test
    void startHaceUnPrimerSondeoEnSegundoPlano() throws Exception {
        ServiceMetrics metrics = new ServiceMetrics("prueba");
        try (DependencyProbe probe = new DependencyProbe(metrics).add("postgres", () -> true).start(60)) {
            long limite = System.currentTimeMillis() + 5_000;
            while (published(metrics, "postgres") == null && System.currentTimeMillis() < limite) {
                Thread.sleep(25);
            }
            assertTrue(published(metrics, "postgres") != null, "el primer sondeo corre sin esperar el periodo");
        }
    }
}
