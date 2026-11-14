package ro.filegrid.simulator.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ro.filegrid.simulator.anomaly.Anomaly.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import ro.filegrid.simulator.config.SimulatorProperties;
import ro.filegrid.simulator.fleet.Region;
import ro.filegrid.simulator.reading.MeterReading;
import ro.filegrid.simulator.reading.ReadingJson;
import ro.filegrid.simulator.send.OutgoingMessage;

class AnomalyInjectorTest {

    private static final Instant T0 = Instant.parse("2026-11-14T09:00:00Z");
    private final AnomalyInjector injector = new AnomalyInjector(Map.of());
    private int seed;

    private static MeterReading reading(int intervals) {
        List<MeterReading.Value> values = new ArrayList<>();
        long register = 5_000_000;
        for (int i = 0; i < intervals; i++) {
            register += 400;
            values.add(new MeterReading.Value(T0.plus(Duration.ofMinutes(15L * i)), 400, register, 2_300));
        }
        return new MeterReading(UUID.randomUUID(), "MTR-B-0004211", 10, Region.BANAT,
                T0.plus(Duration.ofMinutes(15L * intervals)), 15, "2.4.1", values);
    }

    private OutgoingMessage build(MeterReading r, Anomaly... anomalies) {
        Set<Anomaly> rolled = anomalies.length == 0 ? EnumSet.noneOf(Anomaly.class) : EnumSet.of(anomalies[0], anomalies);
        return injector.build(r, rolled, new SplittableRandom(seed++));
    }

    private static JsonNode json(OutgoingMessage m) {
        return ReadingJson.MAPPER.readTree(m.body());
    }

    @Test
    void cleanMessageIsUnchanged() {
        MeterReading r = reading(4);
        OutgoingMessage m = build(r);

        assertThat(m.anomalies()).isEmpty();
        assertThat(m.reading()).isEqualTo(r);
        assertThat(m.body()).isEqualTo(ReadingJson.toBytes(r));
        assertThat(m.kind()).isEqualTo(OutgoingMessage.Kind.ORIGINAL);
    }

    @RepeatedTest(20)
    void gapRemovesSomeIntervalsButKeepsAtLeastOne() {
        MeterReading r = reading(6);
        OutgoingMessage m = build(r, GAP);

        assertThat(m.anomalies()).containsExactly(GAP);
        assertThat(m.reading().values()).hasSizeBetween(3, 5).isSubsetOf(r.values());
    }

    @Test
    void gapIsSkippedForSingleIntervalMessages() {
        assertThat(build(reading(1), GAP).anomalies()).isEmpty();
    }

    @RepeatedTest(10)
    void timestampShifts() {
        OutgoingMessage future = build(reading(2), FUTURE_TIMESTAMP);
        Duration ahead = Duration.between(T0.plus(Duration.ofMinutes(30)), future.reading().measuredAt());
        assertThat(ahead).isBetween(Duration.ofHours(1), Duration.ofHours(48));

        OutgoingMessage off = build(reading(2), MISALIGNED_TIMESTAMP);
        long seconds = off.reading().values().getFirst().at().getEpochSecond() % 900;
        assertThat(seconds).isNotZero();
        assertThat(Math.min(seconds, 900 - seconds)).isLessThanOrEqualTo(120);
    }

    @RepeatedTest(10)
    void invalidValueIsNegativeEnergyOrRegisterRollback() {
        MeterReading r = reading(3);
        OutgoingMessage m = build(r, INVALID_VALUE);

        boolean negative = m.reading().values().stream().anyMatch(v -> v.energyWh() < 0);
        boolean rollback = false;
        for (int i = 0; i < 3; i++) {
            if (m.reading().values().get(i).registerWh() < r.values().get(i).registerWh()) rollback = true;
        }
        assertThat(negative ^ rollback).isTrue();
    }

    @RepeatedTest(10)
    void voltageOutOfRangeLeavesEn50160Band() {
        OutgoingMessage m = build(reading(4), VOLTAGE_OUT_OF_RANGE);
        assertThat(m.reading().values()).anyMatch(v -> v.voltageDeciV() < 2_070 || v.voltageDeciV() > 2_530);
    }

    @Test
    void unknownMeterAndWrongRegion() {
        OutgoingMessage unknown = build(reading(1), UNKNOWN_METER);
        int number = Integer.parseInt(unknown.reading().meterId().substring(6));
        assertThat(unknown.reading().meterId()).matches("MTR-B-\\d{7}");
        assertThat(number).isGreaterThan(SimulatorProperties.MAX_FLEET_NUMBER);

        OutgoingMessage wrong = build(reading(1), WRONG_REGION);
        assertThat(wrong.reading().meterId()).isEqualTo("MTR-B-0004211");
        assertThat(json(wrong).get("region").asString()).isNotEqualTo("BANAT");
    }

    @RepeatedTest(40)
    void malformedBodyIsNeverAValidReading() {
        OutgoingMessage m = build(reading(3), MALFORMED);
        assertThat(m.anomalies()).contains(MALFORMED);
        assertThat(m.details()).singleElement().asString().startsWith("malformed=");
        try {
            JsonNode tree = json(m);
            boolean missingField = !tree.has("readingId") || !tree.has("meterId") || !tree.has("region")
                    || !tree.has("measuredAt") || !tree.has("values");
            boolean wrongType = tree.get("sequence") != null && tree.get("sequence").isString()
                    || tree.path("values").path(0).path("activeEnergyKwh").isString();
            boolean empty = tree.path("values").isEmpty();
            assertThat(missingField || wrongType || empty).as(m.details().toString()).isTrue();
        } catch (JacksonException truncated) {
            assertThat(m.details()).containsExactly("malformed=TRUNCATED");
        }
    }

    @Test
    void engineLevelAnomaliesAreOnlyRecorded() {
        MeterReading r = reading(2);
        OutgoingMessage m = build(r, LATE, DUPLICATE, RACE_DUPLICATE, SEQUENCE_RESET);
        assertThat(m.anomalies()).containsExactlyInAnyOrder(LATE, DUPLICATE, RACE_DUPLICATE, SEQUENCE_RESET);
        assertThat(m.body()).isEqualTo(ReadingJson.toBytes(r));
    }

    @Test
    void copiesAndResends() {
        OutgoingMessage original = build(reading(3), VOLTAGE_OUT_OF_RANGE, DUPLICATE);

        OutgoingMessage dup = original.copy(OutgoingMessage.Kind.DUPLICATE);
        assertThat(dup.readingId()).isEqualTo(original.readingId());
        assertThat(dup.copyOf()).isEqualTo(original.readingId());
        assertThat(dup.body()).isEqualTo(original.body());

        UUID newId = UUID.randomUUID();
        OutgoingMessage resent = injector.resend(original, newId, 99);
        assertThat(resent.kind()).isEqualTo(OutgoingMessage.Kind.RESENT);
        assertThat(resent.copyOf()).isEqualTo(original.readingId());
        assertThat(resent.anomalies()).containsExactlyInAnyOrder(RESENT_INTERVALS, VOLTAGE_OUT_OF_RANGE);
        JsonNode tree = json(resent);
        assertThat(tree.get("readingId").asString()).isEqualTo(newId.toString());
        assertThat(tree.get("sequence").asLong()).isEqualTo(99);
        assertThat(tree.get("values")).isEqualTo(json(original).get("values"));
    }

    @Test
    void rollsRoughlyAtConfiguredRateAndRatesCanChangeAtRuntime() {
        AnomalyInjector inj = new AnomalyInjector(Map.of(LATE, 0.1));
        SplittableRandom rng = new SplittableRandom(3);
        long hits = 0;
        for (int i = 0; i < 20_000; i++) if (inj.roll(rng).contains(LATE)) hits++;
        assertThat(hits).isBetween(1_800L, 2_200L);

        inj.updateRates(Map.of(LATE, 0.0, GAP, 1.0));
        assertThat(inj.roll(rng)).containsExactly(GAP);
        assertThatThrownBy(() -> inj.updateRates(Map.of(GAP, 2.0))).isInstanceOf(IllegalArgumentException.class);
    }
}
