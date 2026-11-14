package ro.ppc.filegrid.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
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
