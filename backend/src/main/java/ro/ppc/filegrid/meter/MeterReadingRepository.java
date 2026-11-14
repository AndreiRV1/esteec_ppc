package ro.ppc.filegrid.meter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MeterReadingRepository {

    /** A raw message waiting in the processing queue. */
    public record Raw(long id, String region, String meterId, byte[] payload, Instant receivedAt) {
    }

    /** One row of {@code meter_reading}; {@code anomalyReason} null means VALID. */
    public record Reading(Instant intervalStart, int intervalMinutes, BigDecimal registerKwh,
                          BigDecimal consumptionKwh, BigDecimal voltageV, String anomalyReason) {
    }

    /** What is already known about a meter before a given interval. */
    public record Baseline(BigDecimal previousRegisterKwh, BigDecimal averageConsumptionKwh) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public MeterReadingRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** Stores a message as received. Returns false if this (meter, readingId) was already stored. */
    public boolean insertRaw(String region, String meterId, UUID messageId, Instant periodStart, Instant periodEnd,
                             byte[] payload, String sha256, String traceId) {
        return jdbc.sql("""
                        INSERT INTO meter_reading_raw
                            (region, meter_id, message_id, period_start, period_end, content_type, payload, sha256, trace_id)
                        VALUES (:region, :meterId, :messageId, :periodStart, :periodEnd, 'application/json', :payload,
                                :sha256, :traceId)
                        ON CONFLICT (meter_id, message_id) DO NOTHING
                        """)
                .param("region", region)
                .param("meterId", meterId)
                .param("messageId", messageId)
                .param("periodStart", utc(periodStart))
                .param("periodEnd", utc(periodEnd))
                .param("payload", payload)
                .param("sha256", sha256)
                .param("traceId", traceId)
                .update() == 1;
    }

    /** Oldest pending messages, locked for this transaction; other instances skip them. */
    public List<Raw> lockPending(int limit) {
        return jdbc.sql("""
                        SELECT id, region, meter_id, payload, received_at
                        FROM meter_reading_raw
                        WHERE processing_status = 'PENDING'
                        ORDER BY received_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Raw(rs.getLong("id"), rs.getString("region"), rs.getString("meter_id"),
                        rs.getBytes("payload"), rs.getObject("received_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Size of the processing queue and the age of its oldest message, in seconds (0 when empty). */
    public long[] backlog() {
        return jdbc.sql("""
                        SELECT count(*) AS pending,
                               coalesce(extract(epoch FROM now() - min(received_at)), 0)::bigint AS oldest_seconds
                        FROM meter_reading_raw WHERE processing_status = 'PENDING'
                        """)
                .query((rs, n) -> new long[] {rs.getLong("pending"), rs.getLong("oldest_seconds")})
                .single();
    }

    public Baseline baseline(String meterId, Instant before) {
        return jdbc.sql("""
                        SELECT (SELECT register_kwh FROM meter_reading
                                WHERE meter_id = :meterId AND interval_start < :before
                                  AND anomaly_reason IS DISTINCT FROM 'REGISTER_ROLLBACK'
                                ORDER BY interval_start DESC LIMIT 1) AS previous_register,
                               (SELECT CASE WHEN count(*) >= 8 THEN avg(consumption_kwh) END
                                FROM (SELECT consumption_kwh FROM meter_reading
                                      WHERE meter_id = :meterId AND interval_start < :before AND quality = 'VALID'
                                      ORDER BY interval_start DESC LIMIT 96) recent) AS average_consumption
                        """)
                .param("meterId", meterId)
                .param("before", utc(before))
                .query((rs, n) -> new Baseline(rs.getBigDecimal("previous_register"),
                        rs.getBigDecimal("average_consumption")))
                .single();
    }

    /** Appends readings; an interval that already exists for the meter is left untouched. */
    public void insertReadings(Raw raw, List<Reading> readings) {
        template.batchUpdate("""
                INSERT INTO meter_reading (region, meter_id, raw_id, interval_start, interval_minutes, register_kwh,
                                           consumption_kwh, voltage_v, quality, anomaly_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (meter_id, interval_start) DO NOTHING
                """, readings.stream().map(r -> new Object[] {
                raw.region(), raw.meterId(), raw.id(), utc(r.intervalStart()), r.intervalMinutes(), r.registerKwh(),
                r.consumptionKwh(), r.voltageV(), r.anomalyReason() == null ? "VALID" : "ANOMALY", r.anomalyReason()
        }).toList());
    }

    public void markProcessed(long rawId) {
        jdbc.sql("UPDATE meter_reading_raw SET processing_status = 'PROCESSED', processed_at = now() WHERE id = :id")
                .param("id", rawId).update();
    }

    public void markRejected(long rawId, String error) {
        jdbc.sql("""
                        UPDATE meter_reading_raw
                        SET processing_status = 'REJECTED', processed_at = now(), processing_error = :error
                        WHERE id = :id
                        """)
                .param("id", rawId).param("error", error).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
