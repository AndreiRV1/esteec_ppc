package ro.ppc.filegrid.meter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gauges for the "no reading is lost" requirement: how many raw messages wait to be
 * processed and how long the oldest has waited. Read from the database (at most every
 * 10 s), so every instance reports the same queue whether or not it runs the processor.
 */
@Component
class MeterBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(MeterBacklogMetrics.class);
    private static final long REFRESH_MILLIS = 10_000;

    private final MeterReadingRepository repository;
    private long[] cached = {0, 0};
    private long loadedAt;

    MeterBacklogMetrics(MeterReadingRepository repository, MeterRegistry metrics) {
        this.repository = repository;
        Gauge.builder("filegrid.meter.raw.pending", this, m -> m.backlog()[0])
                .description("Raw meter messages waiting to be processed").register(metrics);
        Gauge.builder("filegrid.meter.raw.oldest.pending.seconds", this, m -> m.backlog()[1])
                .description("Age of the oldest raw meter message still waiting").register(metrics);
    }

    private synchronized long[] backlog() {
        long now = System.currentTimeMillis();
        if (now - loadedAt >= REFRESH_MILLIS) {
            loadedAt = now;
            try {
                cached = repository.backlog();
            } catch (RuntimeException e) {
                log.warn("Could not read the meter backlog: {}", e.toString());
            }
        }
        return cached;
    }
}
