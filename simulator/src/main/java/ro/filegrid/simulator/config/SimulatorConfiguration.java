package ro.filegrid.simulator.config;

import java.nio.file.Path;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

import ro.filegrid.simulator.anomaly.AnomalyInjector;
import ro.filegrid.simulator.engine.SimClock;
import ro.filegrid.simulator.engine.SimulationEngine;
import ro.filegrid.simulator.fleet.Fleet;
import ro.filegrid.simulator.fleet.FleetGenerator;
import ro.filegrid.simulator.fleet.SeedWriter;
import ro.filegrid.simulator.reading.ReadingFactory;
import ro.filegrid.simulator.send.HttpTransport;
import ro.filegrid.simulator.send.Ledger;
import ro.filegrid.simulator.send.Sender;
import ro.filegrid.simulator.send.StdoutTransport;
import ro.filegrid.simulator.send.Transport;

@Configuration(proxyBeanMethods = false)
public class SimulatorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SimulatorConfiguration.class);

    @Bean
    Fleet fleet(SimulatorProperties props) {
        return FleetGenerator.generate(props.seed(), props.meters());
    }

    /** {@code simulator.mode=seed}: write the meter registry SQL and exit. */
    @Bean
    @ConditionalOnProperty(name = "simulator.mode", havingValue = "seed")
    ApplicationRunner seedWriter(Fleet fleet, SimulatorProperties props) {
        return args -> {
            Path file = Path.of(props.seedFile());
            SeedWriter.write(fleet, file);
            log.info("Wrote {} meters (seed {}, fingerprint {}) to {}", fleet.size(), fleet.seed(), fleet.fingerprint(),
                    file.toAbsolutePath());
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "simulator.mode", havingValue = "run", matchIfMissing = true)
    static class Run {

        @Bean
        SimClock simClock(SimulatorProperties props) {
            Clock wall = Clock.systemUTC();
            SimulatorProperties.Clock c = props.clock();
            return new SimClock(wall, SimClock.parseStart(c.start(), wall), c.speed(), c.allowFuture());
        }

        @Bean
        Transport transport(SimulatorProperties props) {
            SimulatorProperties.Sink sink = props.sink();
            return switch (sink.type()) {
                case STDOUT -> new StdoutTransport(System.out);
                case HTTP -> {
                    if (sink.url() == null) throw new IllegalArgumentException("simulator.sink.url is required");
                    if (sink.apiKey() == null || sink.apiKey().isBlank()) {
                        throw new IllegalArgumentException(
                                "simulator.sink.api-key is empty: set FILEGRID_METER_API_KEY (see .env.example)");
                    }
                    yield new HttpTransport(sink.url(), sink.apiKey(), sink.connectTimeout(), sink.requestTimeout());
                }
            };
        }

        @Bean(destroyMethod = "close")
        Ledger ledger(SimulatorProperties props) {
            String path = props.ledger().path();
            if (path == null || path.isBlank()) return Ledger.noop();
            log.info("Recording message outcomes in {}", Path.of(path).toAbsolutePath());
            return Ledger.file(Path.of(path));
        }

        @Bean
        Sender sender(Transport transport, Ledger ledger, SimulatorProperties props, MeterRegistry metrics) {
            return new Sender(transport, ledger, props.sink(), props.retry(), metrics);
        }

        @Bean
        AnomalyInjector anomalyInjector(SimulatorProperties props) {
            return new AnomalyInjector(props.anomalies().rates());
        }

        @Bean
        SimulationEngine simulationEngine(Fleet fleet, SimClock clock, AnomalyInjector anomalies, Sender sender,
                                          SimulatorProperties props, MeterRegistry metrics) {
            return new SimulationEngine(fleet, clock, new ReadingFactory(props.reporting().interval()), anomalies,
                    sender, props, metrics);
        }
    }
}
