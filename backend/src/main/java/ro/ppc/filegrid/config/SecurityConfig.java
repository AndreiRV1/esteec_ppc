package ro.ppc.filegrid.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token API. Tokens are issued by Keycloak (realm `filegrid`);
 * realm roles become authorities: `client` -> ROLE_CLIENT, `employee` -> ROLE_EMPLOYEE,
 * `admin` -> ROLE_ADMIN.
 */
@Configuration
public class SecurityConfig {

    public static final String CLIENT = "CLIENT";
    public static final String ADMIN = "ADMIN";
    public static final String EMPLOYEE = "EMPLOYEE";

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                        // Smart meters have no user account: MeterReadingController checks X-API-Key.
                        .requestMatchers(HttpMethod.POST, "/api/v1/meter-readings").permitAll()
                        // Registration: a racordare without an account creates the applicant's login.
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/connection-requests").permitAll()
                        // Form autofill reads a document and stores nothing; /register needs it before login.
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/document-extraction").permitAll()
                        // Applicants file their own requests; employees file them on an applicant's behalf.
                        .requestMatchers("/api/v1/connection-requests/**").hasAnyRole(CLIENT, EMPLOYEE)
                        .requestMatchers("/api/v1/fault-records/**", "/api/v1/drone-videos/**")
                        .hasAnyRole(EMPLOYEE, ADMIN)
                        .requestMatchers("/api/v1/me/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer tokens are not sent automatically by browsers, so CSRF does not apply.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
