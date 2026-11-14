package ro.filegrid.simulator.send;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import tools.jackson.databind.node.ObjectNode;

import ro.filegrid.simulator.reading.MeterReading;
import ro.filegrid.simulator.reading.ReadingJson;

/**
 * Ground truth: one JSON line per message with its final outcome. Lets you
 * compare what the meters sent against what FileGrid stored (no loss, no duplicates).
 */
public interface Ledger extends AutoCloseable {

    void record(OutgoingMessage message, Outcome outcome, int httpStatus, int attempts, String traceId);

    @Override
    void close();

    static Ledger noop() {
        return new Ledger() {
            @Override public void record(OutgoingMessage m, Outcome o, int s, int a, String t) {}
            @Override public void close() {}
        };
    }

    static Ledger file(Path path) {
        return new FileLedger(path);
    }

    final class FileLedger implements Ledger {

        private final BufferedWriter out;

        FileLedger(Path path) {
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open ledger " + path, e);
            }
        }

        @Override
        public synchronized void record(OutgoingMessage m, Outcome outcome, int httpStatus, int attempts, String traceId) {
            MeterReading r = m.reading();
            ObjectNode line = ReadingJson.MAPPER.createObjectNode()
                    .put("readingId", m.readingId().toString())
                    .put("kind", m.kind().name())
                    .put("copyOf", m.copyOf() == null ? null : m.copyOf().toString())
                    .put("meterId", r.meterId())
                    .put("sequence", r.sequence())
                    .put("region", r.region().name())
                    .put("measuredAt", r.measuredAt().toString())
                    .put("outcome", outcome.name())
                    .put("httpStatus", httpStatus)
                    .put("attempts", attempts)
                    .put("traceId", traceId)
                    .put("completedAt", Instant.now().toString());
            line.putArray("intervals").addAll(r.values().stream()
                    .map(v -> ReadingJson.MAPPER.getNodeFactory().stringNode(v.at().toString())).toList());
            line.putArray("anomalies").addAll(m.anomalies().stream().sorted()
                    .map(a -> ReadingJson.MAPPER.getNodeFactory().stringNode(a.name())).toList());
            line.putArray("details").addAll(m.details().stream()
                    .map(d -> ReadingJson.MAPPER.getNodeFactory().stringNode(d)).toList());
            try {
                out.write(ReadingJson.MAPPER.writeValueAsString(line));
                out.newLine();
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public synchronized void close() {
            try {
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
