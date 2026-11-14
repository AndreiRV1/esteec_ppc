package ro.filegrid.simulator.reading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import ro.filegrid.simulator.config.SimulatorProperties;
import ro.filegrid.simulator.fleet.FleetGenerator;
import ro.filegrid.simulator.fleet.Meter;
import ro.filegrid.simulator.fleet.Region;

class ReadingFactoryTest {

    private static final Duration Q = Duration.ofMinutes(15);
    private final ReadingFactory factory = new ReadingFactory(Q);

    private static Meter meter() {
        Meter m = FleetGenerator.generate(7, new SimulatorProperties.Meters(1, Map.of(Region.BANAT, 1.0))).meters().getFirst();
        m.schedule(Instant.parse("2026-11-14T09:15:00Z"), Instant.parse("2026-11-14T10:00:00Z"), Instant.parse("2026-11-14T10:00:30Z"));
        return m;
    }

    @Test
    void valuesAreConsecutiveAndRegisterAccumulates() {
        Meter m = meter();
        long before = m.registerWh();

        List<MeterReading.Value> values = factory.generate(m, 3, -1, new SplittableRandom(1));

        assertThat(values).extracting(MeterReading.Value::at).containsExactly(
                Instant.parse("2026-11-14T09:15:00Z"), Instant.parse("2026-11-14T09:30:00Z"), Instant.parse("2026-11-14T09:45:00Z"));
        long register = before;
        for (MeterReading.Value v : values) {
            assertThat(v.energyWh()).isNotNegative();
            register += v.energyWh();
            assertThat(v.registerWh()).isEqualTo(register);
            assertThat(v.voltageDeciV()).isBetween(2_170, 2_440);
        }
        assertThat(m.registerWh()).isEqualTo(register);
    }

    @Test
    void spikeMultipliesOneIntervalAndKeepsRegisterConsistent() {
        List<MeterReading.Value> normal = factory.generate(meter(), 4, -1, new SplittableRandom(5));
        List<MeterReading.Value> spiked = factory.generate(meter(), 4, 2, new SplittableRandom(5));

        assertThat(spiked.get(2).energyWh()).isBetween(normal.get(2).energyWh() * 20, normal.get(2).energyWh() * 50);
        assertThat(spiked.get(3).registerWh() - spiked.get(2).registerWh()).isEqualTo(spiked.get(3).energyWh());
    }

    @Test
    void jsonMatchesTheAgreedShape() {
        MeterReading r = new MeterReading(UUID.fromString("5b1f6c1e-8a50-4f0e-9d0a-2f6f3f6f1a11"), "MTR-B-0004211", 18342,
                Region.BANAT, Instant.parse("2026-11-14T10:00:00Z"), 15, "2.4.1", List.of(
                new MeterReading.Value(Instant.parse("2026-11-14T09:15:00Z"), 412, 12_000_000, 2298),
                new MeterReading.Value(Instant.parse("2026-11-14T09:30:00Z"), 398, 12_000_398, 2304)));

        String json = new String(ReadingJson.toBytes(r), StandardCharsets.UTF_8);

        assertThat(json).isEqualTo("""
                {"readingId":"5b1f6c1e-8a50-4f0e-9d0a-2f6f3f6f1a11","meterId":"MTR-B-0004211","sequence":18342,\
                "region":"BANAT","measuredAt":"2026-11-14T10:00:00Z","intervalMinutes":15,"firmware":"2.4.1","values":[\
                {"at":"2026-11-14T09:15:00Z","activeEnergyKwh":0.412,"registerKwh":12000.000,"voltageV":229.8},\
                {"at":"2026-11-14T09:30:00Z","activeEnergyKwh":0.398,"registerKwh":12000.398,"voltageV":230.4}]}""");
        JsonNode parsed = ReadingJson.MAPPER.readTree(json);
        assertThat(parsed.get("values").get(0).get("activeEnergyKwh").decimalValue()).isEqualByComparingTo("0.412");
    }
}
