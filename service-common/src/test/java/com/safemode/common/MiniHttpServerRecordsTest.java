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

    private static final MiniHttpServer.Handler MANEJADOR = peticion -> MiniHttpServer.Response.text(200, "ok");

    private static MiniHttpServer.Route ruta(String metodo, String patron, String[] segmentos, MiniHttpServer.Handler manejador) {
        return new MiniHttpServer.Route(metodo, patron, segmentos, manejador);
    }

    @Test
    void dosRutasConLosMismosSegmentosSonIgualesAunqueSeanArreglosDistintos() {
        MiniHttpServer.Route a = ruta("GET", "/api/puestos/{id}", new String[] {"api", "puestos", "{id}"}, MANEJADOR);
        MiniHttpServer.Route b = ruta("GET", "/api/puestos/{id}", new String[] {"api", "puestos", "{id}"}, MANEJADOR);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.equals(a));
    }

    @Test
    void unaRutaDistintaEnCualquierCampoNoEsIgual() {
        MiniHttpServer.Route base = ruta("GET", "/a", new String[] {"a"}, MANEJADOR);
        assertNotEquals(base, ruta("PUT", "/a", new String[] {"a"}, MANEJADOR));
        assertNotEquals(base, ruta("GET", "/b", new String[] {"a"}, MANEJADOR));
        assertNotEquals(base, ruta("GET", "/a", new String[] {"b"}, MANEJADOR));
        assertNotEquals(base, ruta("GET", "/a", new String[] {"a"}, peticion -> MiniHttpServer.Response.text(204, "")));
        assertFalse(base.equals(null));
        assertFalse(base.equals("GET /a"));
    }

    @Test
    void elTextoDeUnaRutaDiceMetodoYPatron() {
        assertEquals("Route[GET /api/puestos/{id}]", ruta("GET", "/api/puestos/{id}", new String[] {"api"}, MANEJADOR).toString());
    }

    @Test
    void elCuerpoPuedeSerNuloYElTextoNoVuelcaLosBytes() {
        MiniHttpServer.Response sinCuerpo = new MiniHttpServer.Response(204, "text/plain", null);
        assertEquals(sinCuerpo, new MiniHttpServer.Response(204, "text/plain", null));
        assertEquals("Response[status=204, contentType=text/plain, body=null]", sinCuerpo.toString());
        assertEquals("Response[status=200, contentType=text/plain, body=4 bytes]", respuesta(200, "hola").toString());
    }
}
