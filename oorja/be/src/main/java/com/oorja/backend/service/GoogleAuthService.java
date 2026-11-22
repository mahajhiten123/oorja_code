package com.oorja.backend.service;

import com.oorja.backend.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Verifies Google Sign-In ID tokens.
 *
 * The frontend uses Google Identity Services (GIS) to get the user to pick /
 * sign in with a real Google account, which returns a signed ID token (JWT).
 * We never trust that token's contents blindly - we verify it directly with
 * Google's tokeninfo endpoint, which validates the signature for us and
 * confirms the token was actually issued by Google for our client ID.
 *
 * (For very high request volumes Google recommends verifying the JWT
 * signature locally against their JWKS instead of calling tokeninfo per
 * request, but tokeninfo is simple, correct, and plenty fast for this app's
 * login volume.)
 */
@Service
@Slf4j
public class GoogleAuthService {

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";

    @Value("${google.client-id:}")
    private String clientId;

    public record GoogleIdentity(String googleId, String email, boolean emailVerified, String name) {}

    public GoogleIdentity verify(String idToken) {
        if (clientId.isBlank()) {
            throw ApiException.badRequest(
                    "Google sign-in is not configured on the server yet. Set google.client-id.");
        }

        Map<String, Object> payload;
        try {
            payload = restTemplate.getForObject(TOKENINFO_URL + idToken, Map.class);
        } catch (RestClientException e) {
            log.warn("Google tokeninfo verification failed: {}", e.getMessage());
            throw ApiException.unauthorized("We couldn't verify that Google sign-in. Please try again.");
        }

        if (payload == null) {
            throw ApiException.unauthorized("We couldn't verify that Google sign-in. Please try again.");
        }

        String audience = String.valueOf(payload.get("aud"));
        if (!clientId.equals(audience)) {
            log.warn("Google ID token audience mismatch: expected {}, got {}", clientId, audience);
            throw ApiException.unauthorized("This sign-in wasn't issued for this app.");
        }

        boolean emailVerified = "true".equals(String.valueOf(payload.get("email_verified")));
        String email = (String) payload.get("email");
        String sub = (String) payload.get("sub");
        String name = payload.getOrDefault("name", email) instanceof String s ? s : email;

        if (email == null || sub == null) {
            throw ApiException.unauthorized("Google did not return an account we can sign you in with.");
        }

        return new GoogleIdentity(sub, email.toLowerCase(), emailVerified, name);
    }
}
