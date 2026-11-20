package com.safemode.common;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuardsTest {

    private static MiniHttpServer.Request withHeaders(Map<String, String> headers) {
        return new MiniHttpServer.Request("POST", "/operators/provision", Map.of(), Map.of(), headers, "");
    }

    @Test
    void aceptaIdentificadoresCortosEnMinusculas() {
        assertEquals("puesto-1", Guards.identifier("puesto-1", "puesto"));
        assertEquals("a_b-9", Guards.identifier("a_b-9", "operador"));
        assertEquals("x".repeat(40), Guards.identifier("x".repeat(40), "operador"));
    }

    @Test
    void rechazaTextoLibreMayusculasVaciosYLargos() {
        for (String malo : new String[] {null, "", "Mayuscula", "con espacio", "../etc/passwd", "a;drop table", "-empieza-con-guion", "x".repeat(41)}) {
            HttpError error = assertThrows(HttpError.class, () -> Guards.identifier(malo, "puesto"), "debia rechazar: " + malo);
            assertEquals(400, error.status());
        }
    }

    @Test
    void laClaveDeAdministracionCorrectaPasa() {
        Guards.requireAdminKey(withHeaders(Map.of("x-lab-key", "secreto")), "secreto");
    }

    @Test
    void sinClaveOConClaveDistintaEs403() {
        for (Map<String, String> cabeceras : new Map[] {Map.of(), Map.of("x-lab-key", "otro"), Map.of("x-lab-key", "secreto ")}) {
            HttpError error = assertThrows(HttpError.class, () -> Guards.requireAdminKey(withHeaders(cabeceras), "secreto"));
            assertEquals(403, error.status());
        }
    }
}
