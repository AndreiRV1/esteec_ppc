package ro.ppc.filegrid.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static ro.ppc.filegrid.support.TestUsers.user;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ro.ppc.filegrid.support.TestStorages;

/**
 * Every route, as anonymous, client, employee and admin: who gets past the door.
 * Only authentication/authorisation is asserted (401/403 vs anything else); the
 * requests are deliberately empty, so allowed callers get 400/404/415.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleMatrixTest {

    private static final String ID = UUID.randomUUID().toString();
    private static final Set<String> NONE = Set.of();
    private static final Set<String> STAFF = Set.of("employee", "admin");
    private static final Set<String> ADMIN = Set.of("admin");
    private static final Set<String> APPLICANTS = Set.of("client", "employee");
    private static final Set<String> ANY_USER = Set.of("client", "employee", "admin");

    private record Route(HttpMethod method, String path, Set<String> allowed) {
    }

    private static final List<Route> ROUTES = List.of(
            new Route(HttpMethod.GET, "/api/v1/me", ANY_USER),
            new Route(HttpMethod.GET, "/api/v1/me/consumption", ANY_USER),
            new Route(HttpMethod.GET, "/api/v1/me/consumption/summary", ANY_USER),
            new Route(HttpMethod.GET, "/api/v1/me/faults", ANY_USER),
            new Route(HttpMethod.POST, "/api/v1/connection-requests", APPLICANTS),
            new Route(HttpMethod.GET, "/api/v1/connection-requests", APPLICANTS),
            new Route(HttpMethod.GET, "/api/v1/connection-requests/" + ID, APPLICANTS),
            new Route(HttpMethod.GET, "/api/v1/admin/connection-requests", ADMIN),
            new Route(HttpMethod.GET, "/api/v1/admin/connection-requests/" + ID, ADMIN),
            new Route(HttpMethod.GET, "/api/v1/admin/connection-requests/" + ID + "/archive", ADMIN),
            new Route(HttpMethod.GET, "/api/v1/admin/connection-requests/" + ID + "/documents/" + ID, ADMIN),
            new Route(HttpMethod.POST, "/api/v1/admin/connection-requests/" + ID + "/decision", ADMIN),
            new Route(HttpMethod.GET, "/api/v1/admin/storage-policies", ADMIN),
            new Route(HttpMethod.PUT, "/api/v1/admin/storage-policies/FAULT_RECORD/BANAT", ADMIN),
            new Route(HttpMethod.POST, "/api/v1/fault-records", STAFF),
            new Route(HttpMethod.GET, "/api/v1/fault-records", STAFF),
            new Route(HttpMethod.GET, "/api/v1/fault-records/" + ID, STAFF),
            new Route(HttpMethod.GET, "/api/v1/fault-records/" + ID + "/file", STAFF),
            new Route(HttpMethod.POST, "/api/v1/fault-records/" + ID + "/restore", STAFF),
            new Route(HttpMethod.POST, "/api/v1/drone-videos/uploads", STAFF),
            new Route(HttpMethod.PUT, "/api/v1/drone-videos/uploads/" + ID + "/parts/1", STAFF),
            new Route(HttpMethod.GET, "/api/v1/drone-videos/uploads/" + ID, STAFF),
            new Route(HttpMethod.POST, "/api/v1/drone-videos/uploads/" + ID + "/complete", STAFF),
            new Route(HttpMethod.GET, "/api/v1/drone-videos", STAFF),
            new Route(HttpMethod.GET, "/api/v1/drone-videos/" + ID, STAFF),
            new Route(HttpMethod.GET, "/api/v1/drone-videos/" + ID + "/file", STAFF),
            // Meters authenticate with X-API-Key, not a user token: a token alone is not enough.
            new Route(HttpMethod.GET, "/api/v1/meter-readings", NONE),
            new Route(HttpMethod.GET, "/api/v1/unknown", NONE));

    @DynamicPropertySource
    static void storages(DynamicPropertyRegistry registry) {
        TestStorages.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void everyRouteIsOpenOnlyToItsRoles() throws Exception {
        for (Route route : ROUTES) {
            assertThat(statusOf(route, null)).as("anonymous %s %s", route.method(), route.path()).isEqualTo(401);
            for (String role : List.of("client", "employee", "admin")) {
                int status = statusOf(route, role);
                if (route.allowed().contains(role)) {
                    assertThat(status).as("%s %s %s", role, route.method(), route.path()).isNotIn(401, 403);
                } else {
                    assertThat(status).as("%s %s %s", role, route.method(), route.path()).isEqualTo(403);
                }
            }
        }
    }

    @Test
    void meterIngestNeedsTheApiKeyNotAUserToken() throws Exception {
        assertThat(mvc.perform(request(HttpMethod.POST, "/api/v1/meter-readings").with(user("adm", "admin"))
                .contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(request(HttpMethod.POST, "/api/v1/meter-readings").header("X-API-Key", "test-meter-key")
                .contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    private int statusOf(Route route, String role) throws Exception {
        MockHttpServletRequestBuilder request = request(route.method(), route.path());
        if (role != null) {
            request.with(user("matrix-" + role, role));
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
}
