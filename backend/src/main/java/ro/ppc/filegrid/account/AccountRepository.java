package ro.ppc.filegrid.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ro.ppc.filegrid.account.Accounts.Account;
import ro.ppc.filegrid.account.Accounts.Bucket;
import ro.ppc.filegrid.account.Accounts.ConsumptionSummary;
import ro.ppc.filegrid.account.Accounts.Point;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.storage.StoredFileRepository;

/** Reads for the customer portal. Days and months follow local time (Europe/Bucharest), like the bill. */
@Repository
public class AccountRepository {

    private static final String ZONE = "Europe/Bucharest";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> find(String username) {
        return jdbc.sql("""
                        SELECT a.username, a.region, a.meter_id, m.county, m.locality
                        FROM customer_account a JOIN meter m ON m.id = a.meter_id
                        WHERE a.username = :username
                        """)
                .param("username", username)
                .query((rs, n) -> new Account(rs.getString("username"), Region.valueOf(rs.getString("region")),
                        rs.getString("county"), rs.getString("locality"), rs.getString("meter_id")))
                .optional();
    }

    /**
     * Links a client to a meter once one of their racordare requests is accepted (the first one):
     * a free meter of the installed fleet in that region, same county and locality first, so it
     * already reports; otherwise a new meter registered at the requested place. No-op otherwise.
     */
    @Transactional
    public Optional<Account> provisionFromAcceptedRequest(String username, String subject) {
        record Request(String clientId, String region, String county, String locality) {
        }
        Optional<Request> request = jdbc.sql("""
                        SELECT client_id, region, county, locality FROM client
                        WHERE submitted_by = :subject AND status = 'ACCEPTED'
                        ORDER BY reviewed_at LIMIT 1
                        """)
                .param("subject", subject)
                .query((rs, n) -> new Request(rs.getString("client_id"), rs.getString("region"),
                        rs.getString("county"), rs.getString("locality")))
                .optional();
        if (request.isEmpty()) return Optional.empty();
        Request r = request.get();
        String meterId = jdbc.sql("""
                        SELECT id FROM meter m
                        WHERE m.region = :region AND m.active
                          AND NOT EXISTS (SELECT 1 FROM customer_account a WHERE a.meter_id = m.id)
                        ORDER BY (m.county = :county) DESC, (m.locality = :locality) DESC, m.id
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                        """)
                .param("region", r.region()).param("county", r.county()).param("locality", r.locality())
                .query(String.class).optional()
                .orElseGet(() -> {
                    // Outside the simulated fleet's numbers (which stay below 9,000,000).
                    String id = "MTR-%s-%07d".formatted(r.region().charAt(0),
                            9_000_000 + Math.floorMod(r.clientId().hashCode(), 1_000_000));
                    jdbc.sql("""
                                    INSERT INTO meter (id, region, county, locality) VALUES (:id, :region, :county, :locality)
                                    ON CONFLICT (id) DO NOTHING
                                    """)
                            .param("id", id).param("region", r.region()).param("county", r.county())
                            .param("locality", r.locality()).update();
                    return id;
                });
        jdbc.sql("""
                        INSERT INTO customer_account (username, region, meter_id) VALUES (:username, :region, :meterId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("username", username).param("region", r.region()).param("meterId", meterId).update();
        return find(username);
    }

    /** Energy per bucket; negative (invalid) readings are left out of the sum but counted as anomalies. */
    public List<Point> consumption(String meterId, Instant from, Instant to, Bucket bucket) {
        return jdbc.sql("""
                        SELECT date_trunc(:unit, interval_start, :zone) AS bucket_start,
                               coalesce(sum(consumption_kwh) FILTER (WHERE consumption_kwh >= 0), 0) AS kwh,
                               count(*) FILTER (WHERE quality = 'ANOMALY') AS anomalies
                        FROM meter_reading
                        WHERE meter_id = :meterId AND interval_start >= :from AND interval_start < :to
                        GROUP BY 1
                        ORDER BY 1
                        """)
                .param("unit", bucket == Bucket.HOUR ? "hour" : "day")
                .param("zone", ZONE)
                .param("meterId", meterId)
                .param("from", from.atOffset(ZoneOffset.UTC))
                .param("to", to.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new Point(StoredFileRepository.instant(rs, "bucket_start"),
                        rs.getBigDecimal("kwh"), rs.getInt("anomalies")))
                .list();
    }

    public ConsumptionSummary summary(String meterId) {
        return jdbc.sql("""
                        WITH local AS (SELECT date_trunc('month', now() AT TIME ZONE :zone) AS month_start),
                             bounds AS (SELECT month_start AT TIME ZONE :zone AS this_month,
                                               (month_start - interval '1 month') AT TIME ZONE :zone AS previous_month
                                        FROM local)
                        SELECT coalesce(sum(r.consumption_kwh) FILTER (WHERE r.interval_start >= b.this_month), 0)
                                   AS month_to_date,
                               coalesce(sum(r.consumption_kwh) FILTER (WHERE r.interval_start < b.this_month), 0)
                                   AS previous_month,
                               (SELECT max(interval_start) FROM meter_reading WHERE meter_id = :meterId)
                                   AS last_reading_at
                        FROM bounds b
                        LEFT JOIN meter_reading r ON r.meter_id = :meterId AND r.interval_start >= b.previous_month
                                                 AND r.consumption_kwh >= 0
                        """)
                .param("zone", ZONE)
                .param("meterId", meterId)
                .query((rs, n) -> new ConsumptionSummary(rs.getBigDecimal("month_to_date"),
                        rs.getBigDecimal("previous_month"), StoredFileRepository.instant(rs, "last_reading_at")))
                .single();
    }
}
