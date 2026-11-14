package ro.filegrid.simulator.config;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ro.filegrid.simulator.anomaly.Anomaly;
import ro.filegrid.simulator.fleet.Region;

/** All simulator settings ({@code simulator.*}); defaults live in application.yml. */
@ConfigurationProperties("simulator")
public record SimulatorProperties(
        Mode mode,
        long seed,
        Meters meters,
        Clock clock,
        Reporting reporting,
        Sink sink,
        Retry retry,
        Anomalies anomalies,
        Ledger ledger,
        String seedFile) {

    public enum Mode { RUN, SEED }

    public enum SinkType { HTTP, STDOUT }

    /** Highest meter number the fleet may use; numbers above it are reserved for UNKNOWN_METER. */
    public static final int MAX_FLEET_NUMBER = 8_999_999;

    public record Meters(int count, Map<Region, Double> regionWeights) {
        public Meters {
            require(count >= 1 && count <= MAX_FLEET_NUMBER, "meters.count must be 1..%d".formatted(MAX_FLEET_NUMBER));
            require(regionWeights != null && !regionWeights.isEmpty(), "meters.region-weights is required");
            regionWeights.values().forEach(w -> require(w >= 0, "meters.region-weights must be >= 0"));
            require(regionWeights.values().stream().mapToDouble(Double::doubleValue).sum() > 0,
                    "meters.region-weights must not all be 0");
            regionWeights = new EnumMap<>(regionWeights);
        }
    }

    /**
     * @param start       "now", an ISO instant, or an offset from now such as "-P1D"
     * @param speed       simulated seconds per real second
     * @param allowFuture when false, simulated time never runs ahead of the wall clock
     */
    public record Clock(String start, double speed, boolean allowFuture) {
        public Clock {
            require(speed > 0, "clock.speed must be > 0");
        }
    }

    /**
     * @param batchMin             fewest intervals in one message (picked uniformly per message)
     * @param batchMax             most intervals in one message
     * @param sendJitter           random delay after the interval boundary before a meter sends
     * @param maxBacklogIntervals  cap on intervals per message when a meter catches up a backlog
     */
    public record Reporting(int intervalMinutes, int batchMin, int batchMax, Duration sendJitter, int maxBacklogIntervals) {
        public Reporting {
            require(intervalMinutes > 0 && 1440 % intervalMinutes == 0, "reporting.interval-minutes must divide 1440");
            require(batchMin >= 1 && batchMax >= batchMin, "reporting.batch-min must be >= 1 and <= batch-max");
            require(!sendJitter.isNegative() && sendJitter.compareTo(Duration.ofMinutes(intervalMinutes)) < 0,
                    "reporting.send-jitter must be in [0, interval)");
            require(maxBacklogIntervals >= batchMax, "reporting.max-backlog-intervals must be >= batch-max");
        }

        public Duration interval() {
            return Duration.ofMinutes(intervalMinutes);
        }
    }

    public record Sink(SinkType type, URI url, String apiKey, int maxInFlight, int maxPending,
                       Duration connectTimeout, Duration requestTimeout) {
        public Sink {
            require(maxInFlight >= 1, "sink.max-in-flight must be >= 1");
            require(maxPending >= maxInFlight, "sink.max-pending must be >= max-in-flight");
        }
    }

    /** @param maxAttempts 0 = keep retrying until delivered (a real meter never drops a reading) */
    public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        public Retry {
            require(maxAttempts >= 0, "retry.max-attempts must be >= 0");
            require(initialBackoff.isPositive() && maxBackoff.compareTo(initialBackoff) >= 0,
                    "retry backoffs must be positive and initial <= max");
        }
    }

    /** @param maxDelay upper bound for LATE / DUPLICATE / RESENT_INTERVALS delays (simulated time) */
    public record Anomalies(Duration maxDelay, Map<Anomaly, Double> rates) {
        public Anomalies {
            require(maxDelay.isPositive(), "anomalies.max-delay must be positive");
            EnumMap<Anomaly, Double> copy = new EnumMap<>(Anomaly.class);
            if (rates != null) copy.putAll(rates);
            copy.values().forEach(p -> require(p >= 0 && p <= 1, "anomalies.rates must be in [0, 1]"));
            rates = copy;
        }
    }

    /** @param path JSONL file with the outcome of every message; blank = disabled */
    public record Ledger(String path) {}

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
