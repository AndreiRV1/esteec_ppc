package ro.ppc.filegrid.meter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.common.TraceIds;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Entry point for smart meters. The message is stored exactly as received in
 * {@code meter_reading_raw}; validation of the values happens later, in
 * {@link MeterReadingProcessor}. A retry with the same {@code readingId} is a no-op,
 * so a reading is never stored twice.
 */
@RestController
@RequestMapping("/api/v1/meter-readings")
public class MeterReadingController {

    private static final int MAX_BYTES = 1_048_576; // meter_reading_raw_max_size

    private final MeterReadingRepository repository;
    private final ObjectMapper json;
    private final TraceIds traceIds;
    private final byte[] apiKey;
    private final MeterRegistry metrics;

    public MeterReadingController(MeterReadingRepository repository, ObjectMapper json, TraceIds traceIds,
                                  MeterProperties properties, MeterRegistry metrics) {
        this.metrics = metrics;
        this.repository = repository;
        this.json = json;
        this.traceIds = traceIds;
        this.apiKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> ingest(
            @RequestHeader(value = "X-API-Key", required = false) String key, @RequestBody byte[] body) {
        if (key == null || !MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_api_key", "Missing or invalid X-API-Key");
        }
        if (body.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.valueOf(413), "reading_too_large", "Reading exceeds 1 MB");
        }

        // Only what the raw table needs is read here: who sent it and which period it covers.
        UUID readingId;
        String meterId;
        Region region;
        Instant periodStart = null;
        Instant periodEnd = null;
        try {
            JsonNode root = json.readTree(body);
            readingId = UUID.fromString(root.required("readingId").asString());
            meterId = root.required("meterId").asString();
            region = Region.valueOf(root.required("region").asString());
            Duration interval = Duration.ofMinutes(root.path("intervalMinutes").asInt(15));
            for (JsonNode value : root.required("values")) {
                Instant at = Instant.parse(value.required("at").asString());
                if (periodStart == null || at.isBefore(periodStart)) {
                    periodStart = at;
                }
                if (periodEnd == null || at.plus(interval).isAfter(periodEnd)) {
                    periodEnd = at.plus(interval);
                }
            }
            if (periodStart == null || !periodEnd.isAfter(periodStart)) {
                throw new IllegalArgumentException("values is empty");
            }
        } catch (RuntimeException e) {
            throw ApiException.badRequest("malformed_reading", "Malformed reading: " + e.getMessage());
        }

        boolean stored;
        Instant start = periodStart, end = periodEnd; // effectively final, for the span below
        Timer.Sample sample = Timer.start(metrics);
        try {
            stored = traceIds.inSpan("storage put", java.util.Map.of("filegrid.category", "METER_READING",
                            "filegrid.backend", "DATABASE", "filegrid.location", "postgresql:meter_reading_raw",
                            "filegrid.meter_id", meterId),
                    () -> repository.insertRaw(region.name(), meterId, readingId, start, end, body,
                            sha256(body), traceIds.current()));
            record(sample, "success");
        } catch (DataIntegrityViolationException e) {
            record(sample, "success"); // the database answered; the reading itself is invalid
            throw new ApiException(HttpStatus.valueOf(422), "unknown_meter",
                    "Meter " + meterId + " is not registered in region " + region);
        } catch (RuntimeException e) {
            record(sample, "error");
            throw e;
        }
        if (!stored) metrics.counter("filegrid.meter.raw", "status", "duplicate").increment();
        return stored
                ? ResponseEntity.accepted().body(Map.of("status", "ACCEPTED"))
                : ResponseEntity.ok(Map.of("status", "DUPLICATE"));
    }

    /** Same metric as the file categories, so dashboards compare all four per storage. */
    private void record(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("filegrid.storage.operation")
                .description("Storage backend calls, per category and backend")
                .tag("category", "METER_READING")
                .tag("backend", "DATABASE")
                .tag("operation", "put")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(metrics));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
