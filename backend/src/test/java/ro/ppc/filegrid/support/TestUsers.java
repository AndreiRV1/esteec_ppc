package ro.ppc.filegrid.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.ppc.filegrid.config.KeycloakRealmRoleConverter;

/** Mock Keycloak tokens for MockMvc: subject = username, one realm role. */
public final class TestUsers {

    private TestUsers() {
    }

    public static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    public static RequestPostProcessor client(String username) {
        return user(username, "client");
    }

    public static RequestPostProcessor employee(String username) {
        return user(username, "employee");
    }

    public static RequestPostProcessor admin(String username) {
        return user(username, "admin");
    }
}
