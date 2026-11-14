package ro.ppc.filegrid.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Returns the operation's trace id as {@code X-Trace-Id} on every API response, so the caller
 * (UI, tool, support ticket) can follow that exact operation in Tempo from the proxy down to the
 * storage call. The same id is stored with every file and raw meter reading.
 * Runs after Spring's observation filter, so the request span is already current.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    private final TraceIds traceIds;

    public TraceIdHeaderFilter(TraceIds traceIds) {
        this.traceIds = traceIds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = traceIds.current();
        if (traceId != null) {
            response.setHeader(HEADER, traceId);
        }
        chain.doFilter(request, response);
    }
}
