package ro.filegrid.simulator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ro.filegrid.simulator.TestProperties;
import ro.filegrid.simulator.anomaly.Anomaly;
import ro.filegrid.simulator.fleet.Region;

class SimulatorPropertiesTest {

    @Test
    void bindsDefaultsFromApplicationYml() {
        SimulatorProperties p = TestProperties.defaults();

        assertThat(p.mode()).isEqualTo(SimulatorProperties.Mode.RUN);
        assertThat(p.meters().count()).isEqualTo(10_000);
        assertThat(p.meters().regionWeights()).containsOnlyKeys(Region.values());
        assertThat(p.reporting().interval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.reporting().sendJitter()).isEqualTo(Duration.ofMinutes(2));
        assertThat(p.sink().type()).isEqualTo(SimulatorProperties.SinkType.HTTP);
        assertThat(p.sink().url()).hasToString("http://localhost:8080/api/v1/meter-readings");
        assertThat(p.retry().initialBackoff()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void everyAnomalyHasAKebabCaseRateInApplicationYml() {
        Map<Anomaly, Double> rates = TestProperties.defaults().anomalies().rates();
        assertThat(rates).containsOnlyKeys(Anomaly.values());
        assertThat(rates.get(Anomaly.RACE_DUPLICATE)).isEqualTo(0.005);
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> TestProperties.load(Map.of("simulator.reporting.batch-min", "9")))
                .rootCause().hasMessageContaining("batch-min");
        assertThatThrownBy(() -> TestProperties.load(Map.of("simulator.anomalies.rates.late", "1.5")))
                .rootCause().hasMessageContaining("[0, 1]");
        assertThatThrownBy(() -> TestProperties.load(Map.of("simulator.reporting.interval-minutes", "7")))
                .rootCause().hasMessageContaining("divide 1440");
    }
}
