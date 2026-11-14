package ro.ppc.filegrid.meter;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ro.ppc.filegrid.meter.MeterReadingRepository.Baseline;
import ro.ppc.filegrid.meter.MeterReadingRepository.Raw;
import ro.ppc.filegrid.meter.MeterReadingRepository.Reading;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Periodically turns pending raw messages into {@code meter_reading} rows, one per
 * interval, flagging suspicious values as ANOMALY. Messages whose timestamps cannot
 * be trusted are marked REJECTED (the raw row is kept). Safe to run on several
 * instances: each batch locks its rows with SKIP LOCKED.
 */
@Component
public class MeterReadingProcessor {

    private static final Logger log = LoggerFactory.getLogger(MeterReadingProcessor.class);

    private static final BigDecimal MIN_VOLTAGE = new BigDecimal("207"); // EN 50160: 230 V +-10 %
    private static final BigDecimal MAX_VOLTAGE = new BigDecimal("253");
    private static final BigDecimal SPIKE_FACTOR = BigDecimal.TEN;
    private static final BigDecimal MAX_KWH = new BigDecimal("100000000"); // fits numeric(12,3)
    private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);

    private final MeterReadingRepository repository;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final MeterProperties properties;
    private final MeterRegistry metrics;

    public MeterReadingProcessor(MeterReadingRepository repository, ObjectMapper json, TransactionTemplate tx,
                                 MeterProperties properties, MeterRegistry metrics) {
        this.repository = repository;
        this.json = json;
        this.tx = tx;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(initialDelayString = "${filegrid.meters.process-interval}",
            fixedDelayString = "${filegrid.meters.process-interval}")
    void run() {
        int total = 0;
        int batch;
        do {
            batch = processBatch();
            total += batch;
        } while (batch == properties.batchSize());
        if (total > 0) {
            log.info("Processed {} raw meter messages", total);
        }
    }

    /** Processes one batch in one transaction. Returns the number of raw messages handled. */
    public int processBatch() {
        return tx.execute(status -> {
            List<Raw> batch = repository.lockPending(properties.batchSize());
            batch.forEach(this::process);
            return batch.size();
        });
    }

    private void process(Raw raw) {
        List<Reading> readings;
        try {
            readings = evaluate(raw);
        } catch (RuntimeException e) {
            repository.markRejected(raw.id(), e.getMessage() == null ? e.toString() : e.getMessage());
            metrics.counter("filegrid.meter.raw", "status", "rejected").increment();
            return;
        }
        repository.insertReadings(raw, readings);
        repository.markProcessed(raw.id());
        metrics.counter("filegrid.meter.raw", "status", "processed").increment();
        long anomalies = readings.stream().filter(r -> r.anomalyReason() != null).count();
        metrics.counter("filegrid.meter.readings", "quality", "anomaly").increment(anomalies);
        metrics.counter("filegrid.meter.readings", "quality", "valid").increment(readings.size() - anomalies);
    }

    /** Parses the payload and checks every value. Throws if the whole message must be rejected. */
    private List<Reading> evaluate(Raw raw) {
        JsonNode root = json.readTree(raw.payload());
        int intervalMinutes = root.path("intervalMinutes").asInt(15);
        require(intervalMinutes > 0, "intervalMinutes must be positive");
        long intervalSeconds = intervalMinutes * 60L;

        record Value(Instant at, BigDecimal energy, BigDecimal register, BigDecimal voltage) {
        }
        List<Value> values = new ArrayList<>();
        for (JsonNode v : root.required("values")) {
            Instant at = Instant.parse(v.required("at").asString());
            require(at.getEpochSecond() % intervalSeconds == 0 && at.getNano() == 0, "timestamp " + at + " is not aligned");
            require(!at.isAfter(raw.receivedAt().plus(CLOCK_TOLERANCE)), "timestamp " + at + " is in the future");
            BigDecimal energy = number(v, "activeEnergyKwh");
            BigDecimal register = number(v, "registerKwh");
            require(register.signum() >= 0 && register.compareTo(MAX_KWH) < 0 && energy.abs().compareTo(MAX_KWH) < 0,
                    "value out of range at " + at);
            values.add(new Value(at, energy, register, v.hasNonNull("voltageV") ? number(v, "voltageV") : null));
        }
        require(!values.isEmpty(), "values is empty");
        values.sort(Comparator.comparing(Value::at));

        Baseline baseline = repository.baseline(raw.meterId(), values.getFirst().at());
        BigDecimal previousRegister = baseline.previousRegisterKwh();
        BigDecimal spikeAbove = baseline.averageConsumptionKwh() == null
                ? null : baseline.averageConsumptionKwh().multiply(SPIKE_FACTOR);

        List<Reading> readings = new ArrayList<>(values.size());
        for (Value v : values) {
            String reason = null;
            if (v.energy().signum() < 0) {
                reason = "NEGATIVE_ENERGY";
            } else if (previousRegister != null && v.register().compareTo(previousRegister) < 0) {
                reason = "REGISTER_ROLLBACK";
            } else if (v.voltage() != null
                    && (v.voltage().compareTo(MIN_VOLTAGE) < 0 || v.voltage().compareTo(MAX_VOLTAGE) > 0)) {
                reason = "VOLTAGE_OUT_OF_RANGE";
            } else if (spikeAbove != null && spikeAbove.signum() > 0 && v.energy().compareTo(spikeAbove) > 0) {
                reason = "CONSUMPTION_SPIKE";
            }
            if (!"REGISTER_ROLLBACK".equals(reason)) {
                previousRegister = v.register();
            }
            readings.add(new Reading(v.at(), intervalMinutes, v.register(), v.energy(), v.voltage(), reason));
        }
        return readings;
    }

    private static BigDecimal number(JsonNode value, String field) {
        JsonNode n = value.required(field);
        require(n.isNumber(), field + " is not a number");
        return n.decimalValue();
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
