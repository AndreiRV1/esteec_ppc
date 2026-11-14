package ro.filegrid.simulator.send;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;

import ro.filegrid.simulator.config.SimulatorProperties;
import ro.filegrid.simulator.fleet.Region;
import ro.filegrid.simulator.reading.MeterReading;
import ro.filegrid.simulator.reading.ReadingJson;

class SenderTest {

    record Received(String apiKey, String idempotencyKey, String traceparent, String body) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Queue<Integer> statuses = new ArrayDeque<>();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    @TempDir Path tmp;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/meter-readings", ex -> {
            received.add(new Received(ex.getRequestHeaders().getFirst("X-API-Key"),
                    ex.getRequestHeaders().getFirst("Idempotency-Key"), ex.getRequestHeaders().getFirst("traceparent"),
                    new String(ex.getRequestBody().readAllBytes())));
            Integer status;
            synchronized (statuses) {
                status = statuses.poll();
            }
            ex.sendResponseHeaders(status == null ? 202 : status, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Sender sender(Ledger ledger, int maxAttempts) {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/meter-readings");
        var sink = new SimulatorProperties.Sink(SimulatorProperties.SinkType.HTTP, url, "secret-key", 4, 100,
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        var retry = new SimulatorProperties.Retry(maxAttempts, Duration.ofMillis(10), Duration.ofMillis(40));
        return new Sender(new HttpTransport(url, "secret-key", sink.connectTimeout(), sink.requestTimeout()),
                ledger, sink, retry, metrics);
    }

    private static OutgoingMessage message() {
        MeterReading r = new MeterReading(UUID.randomUUID(), "MTR-M-0000001", 1, Region.MUNTENIA,
                Instant.parse("2026-11-14T10:00:00Z"), 15, "2.4.1",
                List.of(new MeterReading.Value(Instant.parse("2026-11-14T09:45:00Z"), 300, 1_000_300, 2_301)));
        return new OutgoingMessage(OutgoingMessage.Kind.ORIGINAL, r.readingId(), null, r, ReadingJson.toBytes(r),
                Set.of(), List.of());
    }

    @Test
    void retriesServerErrorsWithTheSameReadingIdAndBody() throws Exception {
        statuses.addAll(List.of(503, 500, 429, 201));
        Path ledgerFile = tmp.resolve("ledger.jsonl");
        Ledger ledger = Ledger.file(ledgerFile);
        OutgoingMessage msg = message();

        sender(ledger, 0).deliver(msg);
        ledger.close();

        assertThat(received).hasSize(4).allSatisfy(r -> {
            assertThat(r.apiKey()).isEqualTo("secret-key");
            assertThat(r.idempotencyKey()).isEqualTo(msg.readingId().toString());
            assertThat(r.body()).isEqualTo(new String(msg.body()));
            assertThat(r.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        });
        // one trace per message, a new span per attempt
        assertThat(received).extracting(r -> r.traceparent().substring(3, 35)).containsOnly(received.getFirst().traceparent().substring(3, 35));
        assertThat(received).extracting(r -> r.traceparent().substring(36, 52)).doesNotHaveDuplicates();

        JsonNode line = ReadingJson.MAPPER.readTree(Files.readString(ledgerFile));
        assertThat(line.get("outcome").asString()).isEqualTo("DELIVERED");
        assertThat(line.get("attempts").asInt()).isEqualTo(4);
        assertThat(line.get("httpStatus").asInt()).isEqualTo(201);
        assertThat(line.get("intervals").get(0).asString()).isEqualTo("2026-11-14T09:45:00Z");
        assertThat(metrics.get("simulator.attempts").tag("result", "5xx").counter().count()).isEqualTo(2);
        assertThat(metrics.get("simulator.messages").tag("outcome", "delivered").counter().count()).isEqualTo(1);
    }

    @Test
    void clientErrorsAreFinal() {
        statuses.add(400);
        sender(Ledger.noop(), 0).deliver(message());
        assertThat(received).hasSize(1);
        assertThat(metrics.get("simulator.messages").tag("outcome", "rejected").counter().count()).isEqualTo(1);
    }

    @Test
    void conflictCountsAsDuplicateAcknowledged() {
        statuses.add(409);
        sender(Ledger.noop(), 0).deliver(message());
        assertThat(received).hasSize(1);
        assertThat(metrics.get("simulator.messages").tag("outcome", "duplicate").counter().count()).isEqualTo(1);
    }

    @Test
    void networkErrorsAreRetriedUntilMaxAttempts() {
        server.stop(0); // connection refused
        sender(Ledger.noop(), 3).deliver(message());
        assertThat(metrics.get("simulator.attempts").tag("result", "io_error").counter().count()).isEqualTo(3);
        assertThat(metrics.get("simulator.messages").tag("outcome", "abandoned").counter().count()).isEqualTo(1);
    }

    @Test
    void classification() {
        assertThat(Sender.classify(200)).isEqualTo(Outcome.DELIVERED);
        assertThat(Sender.classify(202)).isEqualTo(Outcome.DELIVERED);
        assertThat(Sender.classify(409)).isEqualTo(Outcome.DUPLICATE);
        assertThat(Sender.classify(401)).isEqualTo(Outcome.REJECTED);
        assertThat(Sender.classify(422)).isEqualTo(Outcome.REJECTED);
        assertThat(Sender.classify(0)).isNull();
        assertThat(Sender.classify(408)).isNull();
        assertThat(Sender.classify(429)).isNull();
        assertThat(Sender.classify(503)).isNull();
    }
}
