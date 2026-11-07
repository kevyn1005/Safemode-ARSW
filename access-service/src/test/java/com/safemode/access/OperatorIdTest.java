package com.safemode.access;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El id de operador que se deriva de un correo debe cumplir el formato que aceptan todos los servicios. */
class OperatorIdTest {

    /** El formato de Guards.identifier de service-common: empieza con letra o digito, de 1 a 40 caracteres. */
    private static final Pattern SERVICE_FORMAT = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    @Test
    void usaLaParteLocalDelCorreoEnMinusculas() {
        assertEquals("ana", AccessRepository.operatorIdFor("Ana@example.com", 1));
        assertEquals("hever-barrera", AccessRepository.operatorIdFor("hever.barrera@mail.escuelaing.edu.co", 1));
    }

    @Test
    void siElIdYaLoUsaOtroCorreoSeLeAgregaUnNumero() {
        assertEquals("ana", AccessRepository.operatorIdFor("ana@a.com", 1));
        assertEquals("ana-2", AccessRepository.operatorIdFor("ana@b.com", 2));
        assertEquals("ana-3", AccessRepository.operatorIdFor("ana@c.com", 3));
    }

    @Test
    void lasPartesLocalesRarasDanUnIdValidoSiempre() {
        for (String email : new String[] {"a+b@x.com", "UPPER.CASE@x.com", "__raro__@x.com", "ñandú@x.com", "...@x.com", "-guion@x.com",
                "a".repeat(80) + "@x.com", "@x.com", "sin-arroba"}) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                String id = AccessRepository.operatorIdFor(email, attempt);
                assertTrue(SERVICE_FORMAT.matcher(id).matches(), email + " (" + attempt + ") -> " + id);
            }
        }
    }

    @Test
    void unaParteLocalSinCaracteresUtilesCaeEnUnNombreGenerico() {
        assertEquals("operador", AccessRepository.operatorIdFor("...@x.com", 1));
        assertEquals("operador-2", AccessRepository.operatorIdFor("@x.com", 2));
    }

    @Test
    void elIdSeAcortaA30Caracteres() {
        assertEquals(30, AccessRepository.operatorIdFor("a".repeat(80) + "@x.com", 1).length());
    }
}
