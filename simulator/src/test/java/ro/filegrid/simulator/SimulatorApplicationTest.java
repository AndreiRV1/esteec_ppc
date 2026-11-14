package ro.filegrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import tools.jackson.databind.JsonNode;

import ro.filegrid.simulator.reading.ReadingJson;

/** Boots the real application in both modes. */
class SimulatorApplicationTest {

    @TempDir Path tmp;

    @Test
    void seedModeWritesTheSqlFileAndStartsNoServer() throws Exception {
        Path file = tmp.resolve("seed/meters.sql");
        try (ConfigurableApplicationContext ctx = SpringApplication.run(SimulatorApplication.class,
                "--spring.profiles.active=seed", "--simulator.meters.count=250", "--simulator.seed-file=" + file)) {
            assertThat(ctx.getEnvironment().getProperty("local.server.port")).isNull();
        }
        assertThat(Files.readAllLines(file)).filteredOn(l -> l.startsWith("  ('MTR-")).hasSize(250);
    }

    @Test
    void runModeServesControlApiAndMetrics() throws Exception {
        Path ledger = tmp.resolve("ledger.jsonl");
        try (ConfigurableApplicationContext ctx = SpringApplication.run(SimulatorApplication.class,
                "--server.port=0", "--simulator.sink.type=stdout", "--simulator.meters.count=20",
                "--simulator.clock.start=-P1D", "--simulator.clock.speed=2000", "--simulator.ledger.path=" + ledger)) {
            String base = "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
            HttpClient http = HttpClient.newHttpClient();

            JsonNode state = ReadingJson.MAPPER.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/api/control")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertThat(state.get("meters").asInt()).isEqualTo(20);
            assertThat(state.get("speed").asDouble()).isEqualTo(2000);

            // 2000x: simulated time advances ~33 min per second, so reports start within seconds
            long deadline = System.currentTimeMillis() + 20_000;
            while ((!Files.exists(ledger) || Files.size(ledger) == 0) && System.currentTimeMillis() < deadline) Thread.sleep(50);

            HttpResponse<String> changed = http.send(HttpRequest.newBuilder(URI.create(base + "/api/control"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"paused\": true, \"speed\": 5}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(ReadingJson.MAPPER.readTree(changed.body()).get("paused").asBoolean()).isTrue();

            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/api/control/anomalies"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"LATE\": 3}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(bad.statusCode()).isEqualTo(400);

            String prometheus = http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertThat(prometheus).contains("simulator_messages_generated_total", "simulator_pending_messages");
        }
        assertThat(Files.readAllLines(ledger)).isNotEmpty().allMatch(l -> l.contains("\"outcome\":\"DELIVERED\""));
    }
}
