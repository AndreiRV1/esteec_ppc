package ro.filegrid.simulator.anomaly;

import static ro.filegrid.simulator.anomaly.Anomaly.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import tools.jackson.databind.node.ObjectNode;

import ro.filegrid.simulator.config.SimulatorProperties;
import ro.filegrid.simulator.fleet.FleetGenerator;
import ro.filegrid.simulator.fleet.Region;
import ro.filegrid.simulator.reading.MeterReading;
import ro.filegrid.simulator.reading.ReadingJson;
import ro.filegrid.simulator.send.OutgoingMessage;

/**
 * Decides which anomalies hit a message and applies the ones that change its
 * content. Delivery anomalies (LATE, DUPLICATE, ...) are only marked here; the
 * engine acts on them. Rates can be changed at runtime.
 */
public final class AnomalyInjector {

    /** Applied by the engine (scheduling, sequence, generation), only recorded here. */
    private static final Set<Anomaly> ENGINE_LEVEL =
            EnumSet.of(DUPLICATE, RACE_DUPLICATE, RESENT_INTERVALS, LATE, SEQUENCE_RESET, CONSUMPTION_SPIKE);
    /** Content anomalies a RESENT copy inherits from its original. */
    private static final Set<Anomaly> CONTENT_LEVEL = EnumSet.of(FUTURE_TIMESTAMP, MISALIGNED_TIMESTAMP, GAP,
            INVALID_VALUE, UNKNOWN_METER, WRONG_REGION, VOLTAGE_OUT_OF_RANGE, CONSUMPTION_SPIKE);
    private static final String[] REQUIRED_FIELDS = {"readingId", "meterId", "region", "measuredAt", "values"};

    private volatile Map<Anomaly, Double> rates;

    public AnomalyInjector(Map<Anomaly, Double> rates) {
        this.rates = validated(new EnumMap<>(Anomaly.class), rates);
    }

    public Map<Anomaly, Double> rates() {
        return rates;
    }

    /** Merges new probabilities into the current ones. */
    public synchronized void updateRates(Map<Anomaly, Double> changes) {
        rates = validated(new EnumMap<>(rates), changes);
    }

    public Set<Anomaly> roll(RandomGenerator rng) {
        EnumSet<Anomaly> hit = EnumSet.noneOf(Anomaly.class);
        rates.forEach((a, p) -> {
            if (p > 0 && rng.nextDouble() < p) hit.add(a);
        });
        return hit;
    }

    /** Applies the rolled content anomalies and serialises the message. */
    public OutgoingMessage build(MeterReading reading, Set<Anomaly> rolled, RandomGenerator rng) {
        EnumSet<Anomaly> applied = EnumSet.noneOf(Anomaly.class);
        List<String> details = new ArrayList<>();
        for (Anomaly a : rolled) if (ENGINE_LEVEL.contains(a)) applied.add(a);

        List<MeterReading.Value> values = new ArrayList<>(reading.values());
        if (rolled.contains(GAP) && values.size() >= 2) {
            int missing = 1 + rng.nextInt(values.size() / 2);
            for (int i = 0; i < missing; i++) values.remove(rng.nextInt(values.size()));
            applied.add(GAP);
            details.add("gap=" + missing);
        }
        if (rolled.contains(VOLTAGE_OUT_OF_RANGE)) {
            int from = rng.nextInt(values.size());
            int len = 1 + rng.nextInt(Math.min(4, values.size() - from));
            boolean sag = rng.nextBoolean();
            for (int i = from; i < from + len; i++) {
                MeterReading.Value v = values.get(i);
                int dv = sag ? 1_800 + rng.nextInt(261) : 2_540 + rng.nextInt(161);
                values.set(i, new MeterReading.Value(v.at(), v.energyWh(), v.registerWh(), dv));
            }
            applied.add(VOLTAGE_OUT_OF_RANGE);
            details.add("voltage=" + (sag ? "SAG" : "SWELL") + "x" + len);
        }
        if (rolled.contains(INVALID_VALUE)) {
            int i = rng.nextInt(values.size());
            MeterReading.Value v = values.get(i);
            if (rng.nextBoolean()) {
                values.set(i, new MeterReading.Value(v.at(), -(1 + rng.nextInt(500)), v.registerWh(), v.voltageDeciV()));
                details.add("invalid=NEGATIVE_ENERGY");
            } else {
                long rollback = 1_000 + rng.nextInt(50_000);
                values.set(i, new MeterReading.Value(v.at(), v.energyWh(), Math.max(0, v.registerWh() - rollback), v.voltageDeciV()));
                details.add("invalid=REGISTER_ROLLBACK");
            }
            applied.add(INVALID_VALUE);
        }

        MeterReading r = reading.withValues(values);
        if (rolled.contains(MISALIGNED_TIMESTAMP)) {
            long s = (1 + rng.nextInt(120)) * (rng.nextBoolean() ? 1 : -1);
            r = r.shifted(Duration.ofSeconds(s));
            applied.add(MISALIGNED_TIMESTAMP);
            details.add("misaligned=%+ds".formatted(s));
        }
        if (rolled.contains(FUTURE_TIMESTAMP)) {
            Duration d = Duration.ofMinutes(60 + rng.nextInt(47 * 60 + 1));
            r = r.shifted(d);
            applied.add(FUTURE_TIMESTAMP);
            details.add("future=+" + d);
        }
        if (rolled.contains(UNKNOWN_METER)) {
            int number = SimulatorProperties.MAX_FLEET_NUMBER + 1 + rng.nextInt(1_000_000);
            r = r.withMeter(FleetGenerator.meterId(r.region(), number), r.region());
            applied.add(UNKNOWN_METER);
        }
        if (rolled.contains(WRONG_REGION)) {
            Region actual = r.region();
            Region[] others = Arrays.stream(Region.values()).filter(x -> x != actual).toArray(Region[]::new);
            r = r.withMeter(r.meterId(), others[rng.nextInt(others.length)]);
            applied.add(WRONG_REGION);
            details.add("region=" + r.region() + " (actual " + actual + ")");
        }

        ObjectNode tree = ReadingJson.toTree(r);
        byte[] body;
        if (rolled.contains(MALFORMED)) {
            body = malform(tree, rng, details);
            applied.add(MALFORMED);
        } else {
            body = ReadingJson.toBytes(tree);
        }
        return new OutgoingMessage(OutgoingMessage.Kind.ORIGINAL, r.readingId(), null, r, body, applied, details);
    }

    /** Same intervals as {@code original}, new readingId and sequence (RESENT_INTERVALS). */
    public OutgoingMessage resend(OutgoingMessage original, UUID readingId, long sequence) {
        MeterReading r = original.reading().withIdentity(readingId, sequence);
        EnumSet<Anomaly> anomalies = EnumSet.of(RESENT_INTERVALS);
        for (Anomaly a : original.anomalies()) if (CONTENT_LEVEL.contains(a)) anomalies.add(a);
        return new OutgoingMessage(OutgoingMessage.Kind.RESENT, readingId, original.readingId(), r,
                ReadingJson.toBytes(r), anomalies, original.details());
    }

    private static byte[] malform(ObjectNode tree, RandomGenerator rng, List<String> details) {
        switch (rng.nextInt(4)) {
            case 0 -> {
                byte[] full = ReadingJson.toBytes(tree);
                details.add("malformed=TRUNCATED");
                // Any strict prefix of a JSON object is invalid: the object is never closed.
                return Arrays.copyOf(full, 1 + rng.nextInt(full.length - 1));
            }
            case 1 -> {
                String field = REQUIRED_FIELDS[rng.nextInt(REQUIRED_FIELDS.length)];
                tree.remove(field);
                details.add("malformed=MISSING_" + field);
            }
            case 2 -> {
                if (rng.nextBoolean()) {
                    tree.put("sequence", "seq-" + tree.get("sequence").asString());
                } else {
                    // Locale-formatted decimal ("0,412") as a string instead of a number.
                    ObjectNode first = (ObjectNode) tree.get("values").get(0);
                    BigDecimal kwh = first.get("activeEnergyKwh").decimalValue();
                    first.put("activeEnergyKwh", kwh.toPlainString().replace('.', ','));
                }
                details.add("malformed=WRONG_TYPE");
            }
            default -> {
                tree.putArray("values");
                details.add("malformed=EMPTY_VALUES");
            }
        }
        return ReadingJson.toBytes(tree);
    }

    private static Map<Anomaly, Double> validated(EnumMap<Anomaly, Double> base, Map<Anomaly, Double> changes) {
        if (changes != null) {
            changes.forEach((a, p) -> {
                if (p == null || p < 0 || p > 1) throw new IllegalArgumentException("rate for " + a + " must be in [0, 1]");
                base.put(a, p);
            });
        }
        return Collections.unmodifiableMap(base);
    }
}
