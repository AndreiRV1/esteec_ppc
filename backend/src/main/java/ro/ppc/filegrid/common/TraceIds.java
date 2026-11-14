package ro.ppc.filegrid.common;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
}
