package ro.filegrid.simulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;

import ro.filegrid.simulator.TestProperties;
import ro.filegrid.simulator.anomaly.AnomalyInjector;
import ro.filegrid.simulator.config.SimulatorProperties;
import ro.filegrid.simulator.fleet.Fleet;
import ro.filegrid.simulator.fleet.FleetGenerator;
import ro.filegrid.simulator.reading.ReadingFactory;
import ro.filegrid.simulator.reading.ReadingJson;
import ro.filegrid.simulator.send.OutgoingMessage;
import ro.filegrid.simulator.send.Sender;
import ro.filegrid.simulator.send.Transport;

class SimulationEngineTest {

    private static final Instant START = Instant.parse("2026-11-14T00:07:00Z");
    private static final Duration Q = Duration.ofMinutes(15);

    private final ConcurrentLinkedQueue<OutgoingMessage> sent = new ConcurrentLinkedQueue<>();
    private Sender sender;

    private SimulationEngine engine(Map<String, Object> overrides) {
        Map<String, Object> all = new HashMap<>(Map.of(
                "spring.profiles.active", "clean",
                "simulator.meters.count", "40",
                "simulator.sink.type", "stdout",
                "simulator.sink.max-pending", "1000000"));
        all.putAll(overrides);
        SimulatorProperties props = TestProperties.load(all);
        Fleet fleet = FleetGenerator.generate(props.seed(), props.meters());
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        Transport recording = (m, tp) -> {
            sent.add(m);
            return Transport.Response.of(202);
        };
        sender = new Sender(recording, ro.filegrid.simulator.send.Ledger.noop(), props.sink(), props.retry(), metrics);
        SimulationEngine engine = new SimulationEngine(fleet, new SimClock(Clock.fixed(START, ZoneOffset.UTC), START, 1, false),
                new ReadingFactory(Q), new AnomalyInjector(props.anomalies().rates()), sender, props, metrics);
        engine.initSchedule(START);
        return engine;
    }

    /** Advances simulated time minute by minute, like the engine thread would. */
    private void runFor(SimulationEngine engine, Duration d) throws InterruptedException {
        for (Instant t = START; !t.isAfter(START.plus(d)); t = t.plus(Duration.ofMinutes(1))) engine.step(t);
        long deadline = System.currentTimeMillis() + 10_000;
        while (sender.pending() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(sender.pending()).isZero();
    }

    private static JsonNode json(OutgoingMessage m) {
        return ReadingJson.MAPPER.readTree(m.body());
    }

    @Test
    void cleanRunCoversEveryIntervalExactlyOnceInOrder() throws Exception {
        runFor(engine(Map.of()), Duration.ofHours(24));

        Map<String, List<JsonNode>> byMeter = new HashMap<>();
        for (OutgoingMessage m : sent) byMeter.computeIfAbsent(m.reading().meterId(), k -> new ArrayList<>()).add(json(m));
        assertThat(byMeter).hasSize(40);

        for (List<JsonNode> messages : byMeter.values()) {
            messages.sort((a, b) -> Long.compare(a.get("sequence").asLong(), b.get("sequence").asLong()));
            Instant expectedNext = null;
            long expectedSeq = messages.getFirst().get("sequence").asLong();
            for (JsonNode msg : messages) {
                assertThat(msg.get("sequence").asLong()).isEqualTo(expectedSeq++);
                JsonNode values = msg.get("values");
                assertThat(values.size()).isBetween(1, 8);
                for (JsonNode v : values) {
                    Instant at = Instant.parse(v.get("at").asString());
                    assertThat(at.getEpochSecond() % 900).isZero();
                    if (expectedNext != null) assertThat(at).isEqualTo(expectedNext);
                    expectedNext = at.plus(Q);
                }
                assertThat(Instant.parse(msg.get("measuredAt").asString())).isEqualTo(expectedNext);
            }
            // every meter has reported up to within one batch of the end
            assertThat(expectedNext).isAfter(START.plus(Duration.ofHours(24)).minus(Duration.ofHours(2)));
        }

        // ~40 meters * 96 intervals / 4.5 per message
        assertThat(sent).hasSizeBetween(700, 1_000);
        assertThat(sent).allMatch(m -> m.anomalies().isEmpty() && m.kind() == OutgoingMessage.Kind.ORIGINAL);
    }

    @Test
    void deliveryAnomaliesAddCopiesButNeverLoseOrChangeIntervals() throws Exception {
        runFor(engine(Map.of(
                "simulator.anomalies.rates.late", "0.3",
                "simulator.anomalies.rates.duplicate", "0.3",
                "simulator.anomalies.rates.race-duplicate", "0.2",
                "simulator.anomalies.rates.resent-intervals", "0.2",
                "simulator.anomalies.max-delay", "1h")), Duration.ofHours(12));

        Map<OutgoingMessage.Kind, Integer> kinds = new HashMap<>();
        sent.forEach(m -> kinds.merge(m.kind(), 1, Integer::sum));
        assertThat(kinds).containsKeys(OutgoingMessage.Kind.values());

        // Exact copies share the original's bytes; resends carry the original's intervals.
        Map<java.util.UUID, OutgoingMessage> originals = new HashMap<>();
        sent.stream().filter(m -> m.kind() == OutgoingMessage.Kind.ORIGINAL).forEach(m -> originals.put(m.readingId(), m));
        for (OutgoingMessage m : sent) {
            if (m.kind() == OutgoingMessage.Kind.ORIGINAL) continue;
            OutgoingMessage original = originals.get(m.copyOf());
            if (original == null) continue; // original still delayed past the end of the run
            if (m.kind() == OutgoingMessage.Kind.RESENT) {
                assertThat(m.readingId()).isNotEqualTo(original.readingId());
                assertThat(json(m).get("values")).isEqualTo(json(original).get("values"));
            } else {
                assertThat(m.readingId()).isEqualTo(original.readingId());
                assertThat(m.body()).isEqualTo(original.body());
            }
        }

        // Deduplicated by (meter, interval), originals are contiguous per meter: nothing was lost.
        Map<String, Set<Instant>> intervals = new HashMap<>();
        originals.values().forEach(m -> m.reading().values()
                .forEach(v -> intervals.computeIfAbsent(m.reading().meterId(), k -> new HashSet<>()).add(v.at())));
        long late = originals.values().stream().filter(m -> m.anomalies().contains(ro.filegrid.simulator.anomaly.Anomaly.LATE)).count();
        assertThat(late).isPositive();
        intervals.values().forEach(set -> {
            Instant min = set.stream().min(Instant::compareTo).orElseThrow();
            Instant max = set.stream().max(Instant::compareTo).orElseThrow();
            long expected = Duration.between(min, max).dividedBy(Q) + 1;
            // only messages still held back at the end of the run may be missing
            assertThat((long) set.size()).isGreaterThanOrEqualTo(expected - 8 * 4);
        });
    }

    @Test
    void meterThatFellBehindFlushesItsBacklogInCappedMessages() throws Exception {
        SimulationEngine engine = engine(Map.of(
                "simulator.meters.count", "1",
                "simulator.reporting.max-backlog-intervals", "16"));

        engine.step(START.plus(Duration.ofHours(10))); // nothing ran for 10 h (outage, pause)
        long deadline = System.currentTimeMillis() + 5_000;
        while (sender.pending() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);

        List<OutgoingMessage> messages = new ArrayList<>(sent);
        messages.sort((a, b) -> Long.compare(a.reading().sequence(), b.reading().sequence()));
        assertThat(messages).allMatch(m -> m.reading().values().size() <= 16);
        assertThat(messages.getLast().reading().measuredAt()).isEqualTo(Instant.parse("2026-11-14T10:00:00Z"));
        Instant next = messages.getFirst().reading().values().getFirst().at();
        for (OutgoingMessage m : messages) {
            for (var v : m.reading().values()) {
                assertThat(v.at()).isEqualTo(next);
                next = next.plus(Q);
            }
        }
    }
}
