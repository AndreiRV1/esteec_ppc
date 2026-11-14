package ro.ppc.filegrid.common;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Current W3C trace id, stored with every write so one operation can be followed end to end. */
@Component
public class TraceIds {

    private final ObjectProvider<Tracer> tracer;

    public TraceIds(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    public String current() {
        Tracer t = tracer.getIfAvailable();
        if (t == null) {
            return null;
        }
        Span span = t.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    /** Adds a tag to the current span (no-op without tracing). */
    public void tag(String key, String value) {
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span != null && value != null) {
            span.tag(key, value);
        }
    }

    /** Runs {@code action} in a child span, so a request can be followed down to the storage call. */
    public <T> T inSpan(String name, Map<String, String> tags, Supplier<T> action) {
        Tracer t = tracer.getIfAvailable();
        if (t == null) {
            return action.get();
        }
        Span span = t.nextSpan().name(name);
        tags.forEach(span::tag);
        span.start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            return action.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
