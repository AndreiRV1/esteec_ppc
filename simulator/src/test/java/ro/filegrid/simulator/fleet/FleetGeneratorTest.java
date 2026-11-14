package ro.filegrid.simulator.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ro.filegrid.simulator.config.SimulatorProperties;

class FleetGeneratorTest {

    private static final SimulatorProperties.Meters CONFIG = new SimulatorProperties.Meters(5_000,
            Map.of(Region.MUNTENIA, 0.5, Region.BANAT, 0.3, Region.DOBROGEA, 0.2));

    @Test
    void sameSeedGivesSameFleet() {
        Fleet a = FleetGenerator.generate(42, CONFIG);
        Fleet b = FleetGenerator.generate(42, CONFIG);
        Fleet c = FleetGenerator.generate(43, CONFIG);

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).isNotEqualTo(c.fingerprint());
        assertThat(a.meters().get(17).id()).isEqualTo(b.meters().get(17).id());
        assertThat(a.meters().get(17).registerWh()).isEqualTo(b.meters().get(17).registerWh());
    }

    @Test
    void idsAreUniqueWellFormedAndMatchTheirRegion() {
        Fleet fleet = FleetGenerator.generate(42, CONFIG);

        assertThat(fleet.meters()).extracting(Meter::id).doesNotHaveDuplicates()
                .allMatch(id -> id.matches("MTR-[MBD]-\\d{7}"));
        assertThat(fleet.meters()).allSatisfy(m -> {
            assertThat(m.id().charAt(4)).isEqualTo(m.region().letter());
            assertThat(Integer.parseInt(m.id().substring(6))).isBetween(1, SimulatorProperties.MAX_FLEET_NUMBER);
            assertThat(m.region().localities()).contains(m.locality());
            assertThat(m.latitude()).isCloseTo(m.locality().latitude(), org.assertj.core.data.Offset.offset(0.031));
        });
    }

    @Test
    void regionWeightsAreRespected() {
        Map<Region, Long> perRegion = FleetGenerator.generate(42, CONFIG).meters().stream()
                .collect(Collectors.groupingBy(Meter::region, Collectors.counting()));

        assertThat(perRegion.get(Region.MUNTENIA)).isBetween(2_300L, 2_700L);
        assertThat(perRegion.get(Region.DOBROGEA)).isBetween(850L, 1_150L);
    }

    @Test
    void zeroWeightRegionGetsNoMeters() {
        var onlyBanat = new SimulatorProperties.Meters(500, Map.of(Region.BANAT, 1.0, Region.MUNTENIA, 0.0));
        assertThat(FleetGenerator.generate(1, onlyBanat).meters()).allMatch(m -> m.region() == Region.BANAT);
    }

    @Test
    void seedFileInsertsEveryMeterIdempotently() throws Exception {
        Fleet fleet = FleetGenerator.generate(42, new SimulatorProperties.Meters(2_500, CONFIG.regionWeights()));
        StringWriter sql = new StringWriter();
        SeedWriter.write(fleet, sql);
        String text = sql.toString();

        assertThat(text).contains("fingerprint=" + fleet.fingerprint()).startsWith("-- Generated");
        assertThat(text.lines().filter(l -> l.startsWith("  ('MTR-"))).hasSize(2_500);
        assertThat(text.split("INSERT INTO filegrid.meter", -1)).hasSize(4); // 3 chunks of <= 1000 rows
        assertThat(text.split("ON CONFLICT \\(id\\) DO NOTHING;", -1)).hasSize(4);
        assertThat(text).contains("BEGIN;").endsWith("COMMIT;\n");
        Meter first = fleet.meters().getFirst();
        assertThat(text).contains("('%s', '%s', '%s', '%s', ".formatted(first.id(), first.region(),
                first.locality().county(), first.locality().name()));
    }
}
