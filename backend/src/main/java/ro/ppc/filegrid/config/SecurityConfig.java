package ro.ppc.filegrid.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token API. Tokens are issued by Keycloak (realm `filegrid`);
 * realm roles become authorities: `client` -> ROLE_CLIENT, `admin` -> ROLE_ADMIN.
 */
@Configuration
public class SecurityConfig {

    public static final String CLIENT = "CLIENT";
    public static final String ADMIN = "ADMIN";

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                        .requestMatchers("/api/v1/connection-requests/**").hasRole(CLIENT)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer tokens are not sent automatically by browsers, so CSRF does not apply.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
