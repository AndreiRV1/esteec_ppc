package ro.filegrid.simulator.send;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import ro.filegrid.simulator.anomaly.Anomaly;
import ro.filegrid.simulator.reading.MeterReading;

/**
 * A request body ready to send, plus what the simulator knows about it.
 *
 * @param reading    the reading as intended (after data anomalies, before MALFORMED)
 * @param copyOf     readingId of the original when this is a DUPLICATE / RACE_DUPLICATE / RESENT copy
 * @param anomalies  anomalies applied to this message
 * @param details    human-readable specifics, e.g. "malformed=TRUNCATED"
 */
public record OutgoingMessage(
        Kind kind,
        UUID readingId,
        UUID copyOf,
        MeterReading reading,
        byte[] body,
        Set<Anomaly> anomalies,
        List<String> details) {

    public enum Kind { ORIGINAL, DUPLICATE, RACE_DUPLICATE, RESENT }

    public OutgoingMessage {
        anomalies = Set.copyOf(anomalies);
        details = List.copyOf(details);
    }

    /** Byte-identical copy (same readingId) for DUPLICATE / RACE_DUPLICATE. */
    public OutgoingMessage copy(Kind kind) {
        return new OutgoingMessage(kind, readingId, readingId, reading, body, anomalies, details);
    }
}
