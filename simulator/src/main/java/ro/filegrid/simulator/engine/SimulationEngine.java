package ro.filegrid.simulator.engine;

import static ro.filegrid.simulator.anomaly.Anomaly.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import ro.filegrid.simulator.anomaly.Anomaly;
import ro.filegrid.simulator.anomaly.AnomalyInjector;
import ro.filegrid.simulator.config.SimulatorProperties;
import ro.filegrid.simulator.fleet.Fleet;
import ro.filegrid.simulator.fleet.Meter;
import ro.filegrid.simulator.reading.MeterReading;
import ro.filegrid.simulator.reading.ReadingFactory;
import ro.filegrid.simulator.send.OutgoingMessage;
import ro.filegrid.simulator.send.Sender;

/**
 * Drives the fleet in simulated time. Each meter reports when its next interval
 * boundary (+ jitter) is reached, with a random batch size. A single thread owns
 * all meter state; sending happens on the {@link Sender}'s virtual threads.
 */
public final class SimulationEngine implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);
    private static final long MAX_SLEEP_MS = 100;

    private final Fleet fleet;
    private final SimClock clock;
    private final ReadingFactory factory;
    private final AnomalyInjector anomalies;
    private final Sender sender;
    private final SimulatorProperties.Reporting reporting;
    private final Duration interval;
    private final Duration maxDelay;
    private final RandomGenerator rng;
    private final MeterRegistry metrics;

    private final PriorityQueue<Meter> schedule = new PriorityQueue<>(Comparator.comparing(Meter::dueAt));
    private final PriorityQueue<Delayed> delayed = new PriorityQueue<>();
    private final AtomicInteger delayedCount = new AtomicInteger();
    private long delayedOrder;

    private volatile boolean running;
    private volatile boolean paused;
    private Thread loop;

    public SimulationEngine(Fleet fleet, SimClock clock, ReadingFactory factory, AnomalyInjector anomalies,
                            Sender sender, SimulatorProperties props, MeterRegistry metrics) {
        this.fleet = fleet;
        this.clock = clock;
        this.factory = factory;
        this.anomalies = anomalies;
        this.sender = sender;
        this.reporting = props.reporting();
        this.interval = reporting.interval();
        this.maxDelay = props.anomalies().maxDelay();
        this.rng = new SplittableRandom(props.seed() ^ 0x5DEECE66DL);
        this.metrics = metrics;
        Gauge.builder("simulator.delayed.messages", delayedCount, AtomicInteger::get)
                .description("LATE / DUPLICATE / RESENT messages waiting for their release time").register(metrics);
    }

    // --- lifecycle ---------------------------------------------------------------

    @Override
    public void start() {
        Instant start = clock.now();
        initSchedule(start);
        log.info("Simulating {} meters (fingerprint {}) from {} at {}x: ~{} msg/s, {}-{} intervals of {} min per message",
                fleet.size(), fleet.fingerprint(), start, clock.speed(),
                "%.1f".formatted(estimatedMessagesPerSecond()), reporting.batchMin(), reporting.batchMax(),
                reporting.intervalMinutes());
        running = true;
        loop = Thread.ofPlatform().name("simulation-engine").daemon().start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        if (loop != null) {
            loop.interrupt();
            try {
                loop.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sender.shutdown(Duration.ofSeconds(10));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try {
                Instant now = clock.now();
                if (!paused) step(now);
                Thread.sleep(sleepMillis(now));
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.error("simulation step failed", e);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private long sleepMillis(Instant now) {
        if (paused || !sender.hasCapacity()) return MAX_SLEEP_MS;
        Instant next = null;
        if (!schedule.isEmpty()) next = schedule.peek().dueAt();
        if (!delayed.isEmpty() && (next == null || delayed.peek().releaseAt().isBefore(next))) next = delayed.peek().releaseAt();
        if (next == null) return MAX_SLEEP_MS;
        double simMillis = Duration.between(now, next).toMillis();
        return (long) Math.clamp(simMillis / clock.effectiveSpeed(), 1, MAX_SLEEP_MS);
    }

    // --- simulation (engine thread only) -----------------------------------------

    /** Spreads first reports over one batch so meters don't all fire together. */
    void initSchedule(Instant start) {
        Instant firstBoundary = align(start);
        for (Meter m : fleet.meters()) {
            int batch = nextBatch();
            Instant boundary = firstBoundary.plus(interval.multipliedBy(1 + rng.nextInt(batch)));
            m.schedule(boundary.minus(interval.multipliedBy(batch)), boundary, boundary.plus(jitter()));
            schedule.add(m);
        }
    }

    /** Releases delayed messages and emits every report due at {@code now}. Returns messages emitted. */
    int step(Instant now) {
        int emitted = 0;
        while (!delayed.isEmpty() && !delayed.peek().releaseAt().isAfter(now)) {
            sender.submit(delayed.poll().message());
            delayedCount.decrementAndGet();
        }
        while (!schedule.isEmpty() && !schedule.peek().dueAt().isAfter(now) && sender.hasCapacity()) {
            Meter m = schedule.poll();
            emit(m, now);
            schedule.add(m);
            emitted++;
        }
        return emitted;
    }

    private void emit(Meter m, Instant now) {
        // A meter that fell behind (backpressure, pause) reports everything up to now,
        // capped per message; the rest follows immediately in further messages.
        Instant latest = align(now);
        Instant end = m.nextBoundary().isAfter(latest) ? m.nextBoundary() : latest;
        Instant cap = m.coveredUntil().plus(interval.multipliedBy(reporting.maxBacklogIntervals()));
        if (end.isAfter(cap)) end = cap;
        int count = (int) (Duration.between(m.coveredUntil(), end).toSeconds() / interval.toSeconds());

        Set<Anomaly> rolled = anomalies.roll(rng);
        if (rolled.contains(SEQUENCE_RESET)) m.resetSequence();
        int spikeIndex = rolled.contains(CONSUMPTION_SPIKE) ? rng.nextInt(count) : -1;
        List<MeterReading.Value> values = factory.generate(m, count, spikeIndex, rng);
        MeterReading reading = new MeterReading(UUID.randomUUID(), m.id(), m.nextSequence(), m.region(), end,
                reporting.intervalMinutes(), m.firmware(), values);

        Instant next = end.plus(interval.multipliedBy(nextBatch()));
        m.schedule(end, next, end.isBefore(latest) ? now : next.plus(jitter()));

        OutgoingMessage msg = anomalies.build(reading, rolled, rng);
        count("simulator.messages.generated", "kind", "original");
        msg.anomalies().forEach(a -> count("simulator.anomalies", "type", a.name().toLowerCase()));
        dispatch(m, msg, now);
    }

    private void dispatch(Meter m, OutgoingMessage msg, Instant now) {
        List<OutgoingMessage> together = new ArrayList<>(2);
        together.add(msg);
        if (msg.anomalies().contains(RACE_DUPLICATE)) together.add(msg.copy(OutgoingMessage.Kind.RACE_DUPLICATE));
        if (msg.anomalies().contains(LATE)) {
            Instant release = now.plus(randomDelay(interval.compareTo(maxDelay) < 0 ? interval : Duration.ZERO));
            together.forEach(x -> delay(x, release));
        } else {
            together.forEach(sender::submit);
        }
        if (msg.anomalies().contains(DUPLICATE)) {
            delay(msg.copy(OutgoingMessage.Kind.DUPLICATE), now.plus(randomDelay(Duration.ZERO)));
        }
        if (msg.anomalies().contains(RESENT_INTERVALS)) {
            delay(anomalies.resend(msg, UUID.randomUUID(), m.nextSequence()), now.plus(randomDelay(Duration.ZERO)));
        }
        if (together.size() > 1) count("simulator.messages.generated", "kind", "race_duplicate");
    }

    private void delay(OutgoingMessage msg, Instant releaseAt) {
        if (msg.kind() != OutgoingMessage.Kind.ORIGINAL && msg.kind() != OutgoingMessage.Kind.RACE_DUPLICATE) {
            count("simulator.messages.generated", "kind", msg.kind().name().toLowerCase());
        }
        delayed.add(new Delayed(releaseAt, delayedOrder++, msg));
        delayedCount.incrementAndGet();
    }

    private Duration randomDelay(Duration min) {
        long minS = min.toSeconds();
        return Duration.ofSeconds(minS + rng.nextLong(Math.max(1, maxDelay.toSeconds() - minS) + 1));
    }

    private int nextBatch() {
        return reporting.batchMin() + rng.nextInt(reporting.batchMax() - reporting.batchMin() + 1);
    }

    private Duration jitter() {
        long ms = reporting.sendJitter().toMillis();
        return ms == 0 ? Duration.ZERO : Duration.ofMillis(rng.nextLong(ms));
    }

    private Instant align(Instant t) {
        long step = interval.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), step) * step);
    }

    private void count(String name, String tag, String value) {
        Counter.builder(name).tag(tag, value).register(metrics).increment();
    }

    // --- control (any thread) ----------------------------------------------------

    public void setPaused(boolean paused) {
        this.paused = paused;
        log.info(paused ? "Paused: meters keep measuring and will flush their backlog on resume" : "Resumed");
    }

    public boolean paused() {
        return paused;
    }

    public int delayedMessages() {
        return delayedCount.get();
    }

    public double estimatedMessagesPerSecond() {
        double avgBatch = (reporting.batchMin() + reporting.batchMax()) / 2.0;
        return fleet.size() * clock.effectiveSpeed() / (avgBatch * interval.toSeconds());
    }

    private record Delayed(Instant releaseAt, long order, OutgoingMessage message) implements Comparable<Delayed> {
        @Override
        public int compareTo(Delayed o) {
            int c = releaseAt.compareTo(o.releaseAt);
            return c != 0 ? c : Long.compare(order, o.order);
        }
    }
}
