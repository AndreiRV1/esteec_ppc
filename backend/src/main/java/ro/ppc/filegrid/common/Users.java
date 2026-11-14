package ro.ppc.filegrid.common;

import java.util.Locale;
import org.springframework.security.oauth2.jwt.Jwt;

/** How a caller is named in stored records. */
public final class Users {

    private Users() {
    }

    /** Keycloak username (lower case, as Keycloak stores it); falls back to the subject. */
    public static String username(Jwt jwt) {
        String name = jwt.getClaimAsString("preferred_username");
        return name == null || name.isBlank() ? jwt.getSubject() : name.toLowerCase(Locale.ROOT);
    }
}
