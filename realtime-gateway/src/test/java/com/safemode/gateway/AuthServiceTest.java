package com.safemode.gateway;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final AuthService auth = new AuthService("cid", Set.of("Ana@Gmail.com"),
            token -> token.equals("bueno") ? Optional.of(new AuthService.User("ana@gmail.com", "Ana")) : Optional.empty(),
            clock);

    @Test
    void soloEntranLosCorreosPermitidosSinImportarMayusculas() {
        assertTrue(auth.isAllowed(new AuthService.User("ANA@gmail.com", "Ana")));
        assertFalse(auth.isAllowed(new AuthService.User("otro@gmail.com", "Otro")));
    }

    @Test
    void laSesionSeEncuentraPorCookieYSeCierra() {
        String id = auth.createSession(new AuthService.User("ana@gmail.com", "Ana"));
        String header = "otra=1; " + AuthService.COOKIE_NAME + "=" + id;
        assertEquals("Ana", auth.userFor(header).orElseThrow().name());
        auth.logout(header);
        assertTrue(auth.userFor(header).isEmpty());
    }

    @Test
    void laSesionVenceConElTiempo() {
        String id = auth.createSession(new AuthService.User("ana@gmail.com", "Ana"));
        clock.now = clock.now.plus(AuthService.SESSION_TTL).plus(Duration.ofSeconds(1));
        assertTrue(auth.userFor(AuthService.COOKIE_NAME + "=" + id).isEmpty());
    }

    @Test
    void sinCookieOConCookieInventadaNoHaySesion() {
        assertTrue(auth.userFor(null).isEmpty());
        assertTrue(auth.userFor(AuthService.COOKIE_NAME + "=inventada").isEmpty());
    }

    @Test
    void parseEmailsLimpiaEspaciosYMayusculas() {
        assertEquals(Set.of("a@x.com", "b@y.com"), AuthService.parseEmails(" A@x.com , b@y.com ,, "));
    }
}