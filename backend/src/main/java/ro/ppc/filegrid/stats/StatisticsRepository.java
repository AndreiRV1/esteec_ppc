package ro.ppc.filegrid.stats;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ro.ppc.filegrid.common.Region;

/** Aggregates for the admin statistics page. Days follow local time (Europe/Bucharest). */
@Repository
public class StatisticsRepository {

    private static final String ZONE = "Europe/Bucharest";

    private final JdbcClient jdbc;

    public StatisticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One period (day or hour) with traffic: files, MB stored, % failed. */
    public record DayRow(String category, java.time.Instant start, long files, double megabytes,
                         double errorRatePercent) {
    }

    public record RegionInsight(Region region, long activeMeters, long readings24h, long anomalies24h,
                                long duplicatesFiltered24h, Double avgVoltageV, String peakLoadWindow, long faults30d,
                                long activeFaults, Long avgRestoreMinutes, long affectedCustomers30d,
                                String hotspotZone, String hotspotSubstation, String dominantFaultCause,
                                long droneVideosStored, String recommendation) {
    }

    /** Bytes stored per "CATEGORY/BACKEND", for throughput. */
    public Map<String, Long> storedBytes() {
        return jdbc.sql("""
                        SELECT category || '/' || backend AS k, coalesce(sum(size_bytes), 0) AS b
                        FROM stored_file WHERE status = 'STORED' GROUP BY 1
                        UNION ALL
                        SELECT 'METER_READING/DATABASE', coalesce(sum(size_bytes), 0) FROM meter_reading_raw
                        """)
                .query((rs, n) -> Map.entry(rs.getString("k"), rs.getLong("b"))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Local days with any traffic in the last 30 days (decides whether days or hours are compared). */
    public int activeDays() {
        return jdbc.sql("""
                        SELECT count(DISTINCT date_trunc('day', t, :zone)) FROM (
                            SELECT created_at AS t FROM stored_file WHERE created_at >= now() - interval '30 days'
                            UNION ALL
                            SELECT received_at FROM meter_reading_raw WHERE received_at >= now() - interval '30 days') x
                        """)
                .param("zone", ZONE).query(Integer.class).single();
    }

    /**
     * Per category and period with traffic ({@code unit} = day or hour, local time) over the last
     * {@code window}: files, MB stored, % failed. Meter readings count raw messages.
     */
    public Map<String, List<DayRow>> volumes(String unit, java.time.Duration window) {
        return jdbc.sql("""
                        SELECT category, date_trunc(:unit, created_at, :zone) AS start, count(*) AS files,
                               coalesce(sum(size_bytes) FILTER (WHERE ok), 0) / 1e6 AS mb,
                               100.0 * count(*) FILTER (WHERE NOT ok) / count(*) AS error_rate
                        FROM (SELECT category, created_at, size_bytes, status = 'STORED' AS ok
                              FROM stored_file WHERE status IN ('STORED', 'FAILED', 'CORRUPTED')
                              UNION ALL
                              SELECT 'METER_READING', received_at, size_bytes, processing_status <> 'REJECTED'
                              FROM meter_reading_raw) t
                        WHERE created_at >= now() - make_interval(secs => :seconds)
                        GROUP BY category, 2
                        """)
                .param("unit", unit)
                .param("zone", ZONE)
                .param("seconds", window.toSeconds())
                .query((rs, n) -> new DayRow(rs.getString("category"),
                        ro.ppc.filegrid.storage.StoredFileRepository.instant(rs, "start"), rs.getLong("files"),
                        rs.getDouble("mb"), rs.getDouble("error_rate")))
                .list().stream().collect(Collectors.groupingBy(DayRow::category));
    }

    public List<RegionInsight> regionalInsights(Region[] regions) {
        Map<String, Map<String, Object>> meters = byRegion("""
                SELECT region, count(*) FILTER (WHERE active) AS active_meters FROM meter GROUP BY region""");
        Map<String, Map<String, Object>> readings = byRegion("""
                SELECT region, count(*) AS readings, count(*) FILTER (WHERE quality = 'ANOMALY') AS anomalies,
                       round(avg(voltage_v), 1) AS voltage
                FROM meter_reading WHERE interval_start >= now() - interval '24 hours' GROUP BY region""");
        Map<String, Map<String, Object>> rejected = byRegion("""
                SELECT region, count(*) AS rejected FROM meter_reading_raw
                WHERE received_at >= now() - interval '24 hours' AND processing_status = 'REJECTED' GROUP BY region""");
        Map<String, Map<String, Object>> peak = byRegion("""
                SELECT DISTINCT ON (region) region, hour FROM (
                    SELECT region, extract(hour FROM interval_start AT TIME ZONE '%s')::int AS hour,
                           avg(consumption_kwh) AS kwh
                    FROM meter_reading WHERE interval_start >= now() - interval '7 days' AND consumption_kwh >= 0
                    GROUP BY 1, 2) h
                ORDER BY region, kwh DESC""".formatted(ZONE));
        Map<String, Map<String, Object>> faults = byRegion("""
                SELECT region,
                       count(*) FILTER (WHERE occurred_at >= now() - interval '30 days') AS faults30d,
                       count(*) FILTER (WHERE restored_at IS NULL) AS active,
                       round(avg(extract(epoch FROM restored_at - occurred_at) / 60)
                             FILTER (WHERE occurred_at >= now() - interval '30 days'))::bigint AS restore_min,
                       coalesce(sum(affected_customers) FILTER (WHERE occurred_at >= now() - interval '30 days'), 0)
                           AS affected
                FROM fault_record GROUP BY region""");
        Map<String, Map<String, Object>> zone = top("county || coalesce(' / ' || locality, '')");
        Map<String, Map<String, Object>> substation = top("substation_code");
        Map<String, Map<String, Object>> cause = top("cause");
        Map<String, Map<String, Object>> videos = byRegion("""
                SELECT region, count(*) AS videos FROM drone_video GROUP BY region""");

        return java.util.Arrays.stream(regions).map(r -> {
            String k = r.name();
            long readings24h = num(readings, k, "readings");
            long anomalies = num(readings, k, "anomalies");
            long active = num(faults, k, "active");
            Object voltage = value(readings, k, "voltage");
            Object hour = value(peak, k, "hour");
            Object restore = value(faults, k, "restore_min");
            return new RegionInsight(r, num(meters, k, "active_meters"), readings24h, anomalies,
                    num(rejected, k, "rejected"), voltage == null ? null : ((Number) voltage).doubleValue(),
                    hour == null ? "—" : "%02d:00–%02d:00".formatted(((Number) hour).intValue(), (((Number) hour).intValue() + 1) % 24),
                    num(faults, k, "faults30d"), active, restore == null ? null : ((Number) restore).longValue(),
                    num(faults, k, "affected"), text(zone, k), text(substation, k), text(cause, k),
                    num(videos, k, "videos"), recommendation(readings24h, anomalies, active, text(substation, k)));
        }).toList();
    }

    private static String recommendation(long readings, long anomalies, long activeFaults, String substation) {
        if (activeFaults > 0) return "Avarii nerezolvate: prioritizează echipele de intervenție"
                + ("—".equals(substation) ? "." : ", începând cu stația " + substation + ".");
        if (readings > 0 && anomalies * 100.0 / readings > 1) return "Peste 1% citiri anormale: verifică contoarele "
                + "și tensiunea din zonă.";
        if (readings == 0) return "Nicio citire în ultimele 24 h: verifică transmisia contoarelor.";
        return "Fără probleme semnificative în ultimele 24 h.";
    }

    /** Most frequent value of an expression per region over the last 30 days. */
    private Map<String, Map<String, Object>> top(String expression) {
        return byRegion("""
                SELECT DISTINCT ON (region) region, %s AS v FROM fault_record
                WHERE occurred_at >= now() - interval '30 days' AND %s IS NOT NULL
                GROUP BY region, v ORDER BY region, count(*) DESC""".formatted(expression, expression));
    }

    private Map<String, Map<String, Object>> byRegion(String sql) {
        return new HashMap<>(jdbc.sql(sql).query().listOfRows().stream()
                .collect(Collectors.toMap(row -> (String) row.get("region"), Function.identity())));
    }

    private static Object value(Map<String, Map<String, Object>> rows, String region, String column) {
        Map<String, Object> row = rows.get(region);
        return row == null ? null : row.get(column);
    }

    private static long num(Map<String, Map<String, Object>> rows, String region, String column) {
        Object v = value(rows, region, column);
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static String text(Map<String, Map<String, Object>> rows, String region) {
        Object v = value(rows, region, "v");
        return v == null ? "—" : v.toString();
    }
}
