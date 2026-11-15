package ro.ppc.filegrid.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP limits on the two endpoints anyone can call without logging in: registration (creates a Keycloak
 * user and stores up to 30 MB) and document extraction (OCR is CPU-heavy). Over the limit: 429 with
 * Retry-After. The client IP is the one the proxy forwards (Caddy overwrites X-Forwarded-For with the real
 * peer, and server.forward-headers-strategy applies it), so a client cannot pick its own address.
 * shortcut: counters live in this replica's memory, so N replicas allow N times the limit; move the limit
 * to the proxy or a shared store if the public endpoints ever run on many replicas.
 */
@Component
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private record Limit(String path, int max, Duration window) {
    }

    private record Window(long startMillis, int count) {
    }

    private final Limit[] limits;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    @Autowired
    public PublicRateLimitFilter(@Value("${filegrid.rate-limit.registrations-per-hour:10}") int registrations,
                                 @Value("${filegrid.rate-limit.extractions-per-10-minutes:30}") int extractions) {
        this(registrations, extractions, System::currentTimeMillis);
    }

    PublicRateLimitFilter(int registrations, int extractions, LongSupplier clock) {
        this.limits = new Limit[] {
                new Limit("/api/v1/public/connection-requests", registrations, Duration.ofHours(1)),
                new Limit("/api/v1/public/document-extraction", extractions, Duration.ofMinutes(10)),
        };
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Limit limit = "POST".equals(request.getMethod()) ? limitFor(request.getRequestURI()) : null;
        long retryAfter = limit == null ? 0 : take(limit, request.getRemoteAddr());
        if (retryAfter > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"type":"about:blank","title":"Too Many Requests","status":429,"code":"rate_limited",\
                    "detail":"Too many requests from this address; try again in %d seconds"}""".formatted(retryAfter));
            return;
        }
        chain.doFilter(request, response);
    }

    private Limit limitFor(String uri) {
        for (Limit limit : limits) {
            if (uri.equals(limit.path())) {
                return limit;
            }
        }
        return null;
    }

    /** Counts one request; returns 0 if allowed, otherwise the seconds until the window resets. */
    long take(Limit limit, String ip) {
        long now = clock.getAsLong();
        long windowMillis = limit.window().toMillis();
        if (windows.size() > 50_000) { // drop finished windows so the map cannot grow without bound
            windows.values().removeIf(w -> now - w.startMillis() >= Duration.ofHours(1).toMillis());
        }
        Window w = windows.compute(limit.path() + "|" + ip, (key, old) ->
                old == null || now - old.startMillis() >= windowMillis ? new Window(now, 1) : new Window(old.startMillis(), old.count() + 1));
        return w.count() <= limit.max() ? 0 : Math.max(1, (w.startMillis() + windowMillis - now + 999) / 1000);
    }
}
