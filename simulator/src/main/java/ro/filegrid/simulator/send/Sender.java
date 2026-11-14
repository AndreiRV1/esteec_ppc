package ro.filegrid.simulator.send;

import java.io.IOException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import ro.filegrid.simulator.config.SimulatorProperties;

/**
 * Delivers messages like a real meter: each one is retried with exponential
 * backoff (same body, same readingId) until the server gives a final answer.
 * At most {@code maxInFlight} requests run at once; {@link #hasCapacity()}
 * lets the engine stop generating when {@code maxPending} messages are queued.
 */
public final class Sender {

    private static final Logger log = LoggerFactory.getLogger(Sender.class);

    private final Transport transport;
    private final Ledger ledger;
    private final SimulatorProperties.Retry retry;
    private final int maxPending;
    private final Semaphore permits;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final MeterRegistry metrics;

    public Sender(Transport transport, Ledger ledger, SimulatorProperties.Sink sink,
                  SimulatorProperties.Retry retry, MeterRegistry metrics) {
        this.transport = transport;
        this.ledger = ledger;
        this.retry = retry;
        this.maxPending = sink.maxPending();
        this.permits = new Semaphore(sink.maxInFlight());
        this.metrics = metrics;
        Gauge.builder("simulator.pending.messages", pending, AtomicInteger::get)
                .description("Messages generated but not yet finally delivered or rejected").register(metrics);
        Gauge.builder("simulator.in.flight.requests", inFlight, AtomicInteger::get).register(metrics);
    }

    public boolean hasCapacity() {
        return pending.get() < maxPending;
    }

    public int pending() {
        return pending.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public void submit(OutgoingMessage message) {
        pending.incrementAndGet();
        workers.execute(() -> {
            try {
                deliver(message);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    void deliver(OutgoingMessage message) {
        String traceId = randomHex(16);
        Duration backoff = retry.initialBackoff();
        int attempt = 0;
        int lastStatus = 0;
        try {
            while (true) {
                attempt++;
                Duration retryAfter = null;
                permits.acquire();
                inFlight.incrementAndGet();
                long start = System.nanoTime();
                String result;
                try {
                    Transport.Response response = transport.send(message, "00-" + traceId + "-" + randomHex(8) + "-01");
                    lastStatus = response.status();
                    result = (lastStatus / 100) + "xx";
                    retryAfter = response.retryAfter().orElse(null);
                } catch (IOException e) {
                    lastStatus = 0;
                    result = "io_error";
                    log.debug("attempt {} for {} failed: {}", attempt, message.readingId(), e.toString());
                } finally {
                    inFlight.decrementAndGet();
                    permits.release();
                }
                Timer.builder("simulator.request.duration").tag("result", result)
                        .publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram()
                        .register(metrics).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                counter("simulator.attempts", "result", result);

                Outcome outcome = classify(lastStatus);
                if (outcome != null) {
                    finish(message, outcome, lastStatus, attempt, traceId);
                    return;
                }
                if (retry.maxAttempts() > 0 && attempt >= retry.maxAttempts()) {
                    finish(message, Outcome.ABANDONED, lastStatus, attempt, traceId);
                    return;
                }
                Thread.sleep(sleepFor(backoff, retryAfter));
                backoff = min(backoff.multipliedBy(2), retry.maxBackoff());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(message, Outcome.ABANDONED, lastStatus, attempt, traceId);
        }
    }

    /** Final outcome for a status, or null when the request should be retried. */
    static Outcome classify(int status) {
        if (status >= 200 && status < 300) return Outcome.DELIVERED;
        if (status == 409) return Outcome.DUPLICATE;
        if (status == 0 || status == 408 || status == 425 || status == 429 || status >= 500) return null;
        return Outcome.REJECTED;
    }

    private void finish(OutgoingMessage message, Outcome outcome, int status, int attempts, String traceId) {
        counter("simulator.messages", "outcome", outcome.name().toLowerCase());
        if (outcome == Outcome.REJECTED && message.anomalies().isEmpty()) {
            log.warn("clean reading {} from {} rejected with HTTP {}", message.readingId(), message.reading().meterId(), status);
        }
        try {
            ledger.record(message, outcome, status, attempts, traceId);
        } catch (RuntimeException e) {
            log.error("ledger write failed for {}", message.readingId(), e);
        }
    }

    /** Equal jitter: half the backoff fixed, half random; Retry-After wins if longer. */
    private Duration sleepFor(Duration backoff, Duration retryAfter) {
        long half = backoff.toMillis() / 2;
        long ms = half + ThreadLocalRandom.current().nextLong(half + 1);
        if (retryAfter != null) ms = Math.max(ms, Math.min(retryAfter.toMillis(), retry.maxBackoff().toMillis()));
        return Duration.ofMillis(ms);
    }

    private void counter(String name, String tag, String value) {
        Counter.builder(name).tag(tag, value).register(metrics).increment();
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Stops retries; messages still pending are recorded as ABANDONED. */
    public void shutdown(Duration grace) {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("{} messages still pending at shutdown; abandoning them", pending.get());
                workers.shutdownNow();
                workers.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
