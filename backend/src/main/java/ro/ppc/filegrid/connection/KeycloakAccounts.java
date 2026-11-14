package ro.ppc.filegrid.connection;

import java.net.URI;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ro.ppc.filegrid.common.ApiException;

/**
 * Creates applicant logins in the Keycloak realm, as the confidential client
 * {@code filegrid-backend} (service account with realm-management/manage-users only).
 * New users get the realm's default roles, which include {@code client}.
 */
@Component
public class KeycloakAccounts {

    public record Login(String userId, String username, String password) {
    }

    private static final Logger log = LoggerFactory.getLogger(KeycloakAccounts.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final RestClient http = RestClient.create();
    private final String url;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    public KeycloakAccounts(@Value("${filegrid.keycloak.url}") String url,
                            @Value("${filegrid.keycloak.realm:filegrid}") String realm,
                            @Value("${filegrid.keycloak.client-id:filegrid-backend}") String clientId,
                            @Value("${filegrid.keycloak.client-secret:}") String clientSecret) {
        this.url = url;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** A new login named {@code cl-<8 digits>} with a random 16-character password. */
    public Login createApplicant() {
        if (clientSecret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "registration_unavailable",
                    "Online registration is not configured (FILEGRID_KEYCLOAK_BACKEND_SECRET)");
        }
        try {
            String token = token();
            for (int attempt = 0; attempt < 5; attempt++) {
                String username = "cl-%08d".formatted(RANDOM.nextInt(100_000_000));
                String password = password(16);
                try {
                    URI location = http.post().uri(url + "/admin/realms/{realm}/users", realm)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            // The realm's user profile requires these; personal data stays only in the
                            // request's encrypted archive, so they are placeholders (like seed-clients.py).
                            .body(Map.of("username", username, "enabled", true, "emailVerified", true,
                                    "firstName", "Client", "lastName", username.toUpperCase(java.util.Locale.ROOT),
                                    "email", username + "@clients.filegrid.local",
                                    "credentials", List.of(Map.of("type", "password", "value", password,
                                            "temporary", false))))
                            .retrieve().toBodilessEntity().getHeaders().getLocation();
                    String path = location.getPath();
                    return new Login(path.substring(path.lastIndexOf('/') + 1), username, password);
                } catch (HttpClientErrorException.Conflict taken) {
                    // username already exists: draw another one
                }
            }
            throw new IllegalStateException("No free username after 5 attempts");
        } catch (RestClientException e) {
            log.warn("Creating an applicant login in Keycloak failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "registration_unavailable",
                    "The login service did not answer; try again in a few minutes");
        }
    }

    /** Removes a login whose request could not be filed, so no orphan account is left. */
    public void delete(String userId) {
        try {
            http.delete().uri(url + "/admin/realms/{realm}/users/{id}", realm, userId)
                    .header("Authorization", "Bearer " + token()).retrieve().toBodilessEntity();
        } catch (RestClientException ignored) {
            // best effort: an unused login without requests grants nothing but an empty portal
        }
    }

    private String token() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<?, ?> body = http.post().uri(url + "/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(Map.class);
        return (String) body.get("access_token");
    }

    private static String password(int length) {
        StringBuilder s = new StringBuilder(length);
        for (int i = 0; i < length; i++) s.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        return s.toString();
    }
}
