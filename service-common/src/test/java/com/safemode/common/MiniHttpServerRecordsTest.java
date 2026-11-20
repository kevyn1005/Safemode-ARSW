package com.safemode.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Response lleva un arreglo de bytes: igualdad, hash y texto deben ir por contenido, no por identidad del arreglo. */
class MiniHttpServerRecordsTest {

    private static MiniHttpServer.Response respuesta(int estado, String texto) {
        return new MiniHttpServer.Response(estado, "text/plain", texto.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void dosRespuestasConElMismoContenidoSonIguales() {
        MiniHttpServer.Response a = respuesta(200, "hola");
        MiniHttpServer.Response b = respuesta(200, "hola");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.equals(a));
    }

    @Test
    void cambiaCualquierCampoYDejanDeSerIguales() {
        MiniHttpServer.Response base = respuesta(200, "hola");
        assertNotEquals(base, respuesta(404, "hola"));
        assertNotEquals(base, respuesta(200, "adios"));
        assertNotEquals(base, new MiniHttpServer.Response(200, "application/json", "hola".getBytes(StandardCharsets.UTF_8)));
        assertFalse(base.equals(null));
        assertFalse(base.equals("hola"));
    }

    @Test
    void elCuerpoPuedeSerNuloYElTextoNoVuelcaLosBytes() {
        MiniHttpServer.Response sinCuerpo = new MiniHttpServer.Response(204, "text/plain", null);
        assertEquals(sinCuerpo, new MiniHttpServer.Response(204, "text/plain", null));
        assertEquals("Response[status=204, contentType=text/plain, body=null]", sinCuerpo.toString());
        assertEquals("Response[status=200, contentType=text/plain, body=4 bytes]", respuesta(200, "hola").toString());
    }
}
