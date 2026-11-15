package ro.ppc.filegrid.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicRateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final PublicRateLimitFilter filter = new PublicRateLimitFilter(2, 3, now::get);

    private int post(String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void limitsRegistrationsPerAddressAndResetsAfterTheWindow() throws Exception {
        String register = "/api/v1/public/connection-requests";
        assertThat(post(register, "1.1.1.1")).isEqualTo(200);
        assertThat(post(register, "1.1.1.1")).isEqualTo(200);
        assertThat(post(register, "1.1.1.1")).isEqualTo(429);
        assertThat(post(register, "2.2.2.2")).isEqualTo(200); // other addresses are not affected

        now.addAndGet(60 * 60 * 1000); // one hour later
        assertThat(post(register, "1.1.1.1")).isEqualTo(200);
    }

    @Test
    void extractionHasItsOwnLimitAndOtherEndpointsAreFree() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/v1/public/document-extraction", "3.3.3.3")).isEqualTo(200);
        }
        assertThat(post("/api/v1/public/document-extraction", "3.3.3.3")).isEqualTo(429);
        assertThat(post("/api/v1/public/connection-requests", "3.3.3.3")).isEqualTo(200);
        for (int i = 0; i < 10; i++) {
            assertThat(post("/api/v1/fault-records", "3.3.3.3")).isEqualTo(200);
        }
    }

    @Test
    void answersWithProblemJsonAndRetryAfter() throws Exception {
        String path = "/api/v1/public/connection-requests";
        post(path, "4.4.4.4");
        post(path, "4.4.4.4");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("4.4.4.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(response.getContentAsString()).contains("\"code\":\"rate_limited\"");
    }
}
