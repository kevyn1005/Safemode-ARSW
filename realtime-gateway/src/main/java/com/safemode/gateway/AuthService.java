package com.safemode.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Inicio de sesion solo con Google (ADR 0004). El navegador manda el ID token que le da Google; aqui se verifica
 * (firma, audiencia, emisor, vencimiento y correo verificado) y solo entran los correos de la lista permitida.
 * La sesion es un identificador aleatorio guardado en memoria y entregado en una cookie HttpOnly.
 */
public class AuthService {

    public static final String COOKIE_NAME = "safemode_session";
    public static final Duration SESSION_TTL = Duration.ofHours(12);

    public static final class User {
        private final String email;
        private final String name;

        public User(String email, String name) {
            this.email = email;
            this.name = name;
        }

        public String email() { return email; }
        public String name() { return name; }
    }

    /** Verifica un ID token de Google; vacio si no es valido. Es una interfaz para poder probar sin internet. */
    @FunctionalInterface
    public interface TokenVerifier {
        Optional<User> verify(String idToken);
    }

    private static final class Session {
        final User user;
        final Instant expires;

        Session(User user, Instant expires) {
            this.user = user;
            this.expires = expires;
        }
    }

    private final String clientId;
    private final Set<String> allowedEmails;
    private final TokenVerifier verifier;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(String clientId, Set<String> allowedEmails, TokenVerifier verifier, Clock clock) {
        this.clientId = clientId;
        Set<String> lower = new HashSet<>();
        for (String email : allowedEmails) {
            lower.add(email.trim().toLowerCase(Locale.ROOT));
        }
        this.allowedEmails = Set.copyOf(lower);
        this.verifier = verifier;
        this.clock = clock;
    }

    /** Servicio real: verifica contra Google. */
    public static AuthService google(String clientId, Set<String> allowedEmails) {
        return new AuthService(clientId, allowedEmails, new GoogleTokenVerifier(clientId), Clock.systemUTC());
    }

    /** "a@x.com, B@y.com" -> {a@x.com, b@y.com}. */
    public static Set<String> parseEmails(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String email = part.trim().toLowerCase(Locale.ROOT);
                if (!email.isEmpty()) {
                    out.add(email);
                }
            }
        }
        return out;
    }

    public String clientId() {
        return clientId;
    }

    public Optional<User> authenticate(String idToken) {
        return verifier.verify(idToken);
    }

    public boolean isAllowed(User user) {
        return allowedEmails.contains(user.email().toLowerCase(Locale.ROOT));
    }

    /** Crea una sesion y devuelve su identificador (el valor de la cookie). */
    public String createSession(User user) {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> s.expires.isBefore(now)); // limpia las vencidas
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(id, new Session(user, now.plus(SESSION_TTL)));
        return id;
    }

    /** Usuario de la sesion que trae la cabecera Cookie, o vacio si no hay sesion valida. */
    public Optional<User> userFor(String cookieHeader) {
        String id = sessionId(cookieHeader);
        if (id == null) {
            return Optional.empty();
        }
        Session session = sessions.get(id);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expires.isBefore(clock.instant())) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(session.user);
    }

    public void logout(String cookieHeader) {
        String id = sessionId(cookieHeader);
        if (id != null) {
            sessions.remove(id);
        }
    }

    static String sessionId(String cookieHeader) {
        if (cookieHeader == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String cookie = part.trim();
            if (cookie.startsWith(COOKIE_NAME + "=")) {
                return cookie.substring(COOKIE_NAME.length() + 1);
            }
        }
        return null;
    }

    /** Verifica el ID token preguntandole a Google (endpoint tokeninfo): suficiente para este proyecto. */
    static final class GoogleTokenVerifier implements TokenVerifier {
        private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        private final String clientId;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        GoogleTokenVerifier(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public Optional<User> verify(String idToken) {
            // solo la forma de un JWT: nada de texto libre en la URL que se le manda a Google
            if (idToken == null || idToken.length() > 4096 || !JWT.matcher(idToken).matches()) {
                return Optional.empty();
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                "https://oauth2.googleapis.com/tokeninfo?id_token="
                                        + URLEncoder.encode(idToken, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return Optional.empty();
                }
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                String issuer = json.get("iss").getAsString();
                boolean issuerOk = issuer.equals("accounts.google.com") || issuer.equals("https://accounts.google.com");
                boolean audienceOk = clientId.equals(json.get("aud").getAsString());
                boolean notExpired = json.get("exp").getAsLong() > Instant.now().getEpochSecond();
                boolean emailVerified = "true".equals(json.get("email_verified").getAsString());
                if (!(issuerOk && audienceOk && notExpired && emailVerified)) {
                    return Optional.empty();
                }
                String email = json.get("email").getAsString();
                String name = json.has("name") ? json.get("name").getAsString() : email;
                return Optional.of(new User(email, name));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (IOException | RuntimeException e) {
                System.err.println("[AUTH] No se pudo verificar el token de Google: " + e.getMessage());
                return Optional.empty();
            }
        }
    }
}