package ro.ppc.filegrid.stats;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.stats.StatisticsRepository.DayRow;

/**
 * PDF §5 for the admin page: which storage fits each category (measured latencies
 * from this replica since it started), a normal vs peak day (from the database,
 * last 30 days) and what the data says about the grid per region.
 * Values that are not measured yet are {@code null}, never invented.
 */
@RestController
@RequestMapping("/api/v1/admin/statistics")
@Tag(name = "Statistics (admin)", description = "Storage fit, normal vs peak day, grid insights")
public class StatisticsController {

    public enum Category { METER_READING, FAULT_RECORD, CONNECTION_DOCUMENTS, DRONE_VIDEO }

    public enum Backend { DATABASE, S3, FTP }

    public record Candidate(Backend backend, Double p50Ms, Double p95Ms, Double p99Ms, Double throughputMbps,
                            Double errorRatePercent, Double memoryFootprintMb, long operations, String verdict) {
    }

    public record StorageFit(Category category, String typicalSize, String businessGuarantee,
                             Backend recommendedBackend, String whyRecommended, List<Candidate> candidates) {
    }

    /**
     * A typical period against the busiest one. {@code bucket} is DAY once there are 3+ days of traffic,
     * HOUR before that (a fresh demo stack has only today). Counts are per period.
     */
    public record DayMetrics(Category category, String bucket, Instant normalStart, Instant peakStart,
                             long normalFilesPerDay, long peakFilesPerDay, double normalVolumeMb,
                             double peakVolumeMb, Double normalP95Ms, Double peakP95Ms, double normalErrorRatePercent,
                             double peakErrorRatePercent, Long resumedOrRetried, boolean slaMet) {
    }

    /** @param storageReachable live state of each storage now (empty without Prometheus); the rest covers 24 h */
    public record Statistics(Instant generatedAt, List<StorageFit> storageFit, List<DayMetrics> normalVsPeak,
                             List<StatisticsRepository.RegionInsight> regionalInsights,
                             Map<Backend, Boolean> storageReachable) {
    }

    private record Text(String size, String guarantee, Backend recommended, String why) {
    }

    private static final Map<Category, Text> TEXT = new EnumMap<>(Map.of(
            Category.METER_READING, new Text("1–50 KB per mesaj, milioane pe zi",
                    "Fără pierderi și fără dubluri: stau la baza facturării.", Backend.DATABASE,
                    "Mesaje mici și foarte multe: PostgreSQL oferă unicitate (contor, mesaj) și tranzacții, "
                            + "deci un mesaj retrimis nu se numără de două ori."),
            Category.FAULT_RECORD, new Text("10 KB – 50 MB per înregistrare",
                    "Disponibile imediat după încărcare, pentru echipele de intervenție.", Backend.FTP,
                    "Fișiere medii citite de sistemele existente prin FTP; latența de scriere rămâne mică."),
            Category.CONNECTION_DOCUMENTS, new Text("1–10 MB per document",
                    "Date personale: criptate la stocare, integritate verificată la fiecare citire.", Backend.S3,
                    "Obiecte imuabile, criptate cu cheie per fișier; S3 păstrează arhivele ieftin și durabil."),
            Category.DRONE_VIDEO, new Text("100 MB – 10 GB per fișier",
                    "Încărcare reluabilă pe conexiuni instabile, fără să încarce memoria serverului.", Backend.S3,
                    "Multipart upload: fiecare parte se scrie direct în S3, iar reluarea pornește de la ultima parte.")));

    private static final String BUCKETS = "filegrid_storage_operation_seconds_bucket";
    private static final String COUNT = "filegrid_storage_operation_seconds_count";
    private static final String SUM = "filegrid_storage_operation_seconds_sum";

    private final MeterRegistry meters;
    private final StatisticsRepository repository;
    private final PrometheusQueries prometheus;

    public StatisticsController(MeterRegistry meters, StatisticsRepository repository, PrometheusQueries prometheus) {
        this.meters = meters;
        this.repository = repository;
        this.prometheus = prometheus;
    }

    @Operation(summary = "Storage fit per category, normal vs peak day, grid insights per region")
    @GetMapping
    public Statistics statistics() {
        Map<String, Long> bytes = repository.storedBytes();
        List<StorageFit> fit = new ArrayList<>();
        for (Category category : Category.values()) {
            Text text = TEXT.get(category);
            List<Candidate> candidates = new ArrayList<>();
            Map<Backend, Candidate> measured = prometheus.available()
                    ? fromPrometheus(category, bytes) : Map.of();
            for (Backend backend : Backend.values()) {
                Candidate c = measured.get(backend);
                if (c == null && !prometheus.available()) {
                    Collection<Timer> timers = meters.find("filegrid.storage.operation")
                            .tag("category", category.name()).tag("backend", backend.name()).timers();
                    c = candidate(backend, timers, bytes.get(category + "/" + backend));
                }
                if (c != null || backend == text.recommended()) {
                    candidates.add(c != null ? c : new Candidate(backend, null, null, null, null, null, null, 0,
                            "Nemăsurat încă: nicio operație în ultimele 24 de ore."));
                }
            }
            fit.add(new StorageFit(category, text.size(), text.guarantee(), text.recommended(), text.why(), candidates));
        }
        Long duplicates = counter("filegrid.meter.raw", "status", "duplicate");
        List<DayMetrics> days = new ArrayList<>();
        boolean byDay = repository.activeDays() >= 3;
        String unit = byDay ? "day" : "hour";
        Map<String, List<DayRow>> byCategory = repository.volumes(unit,
                byDay ? java.time.Duration.ofDays(30) : java.time.Duration.ofHours(48));
        for (Category category : Category.values()) {
            days.add(day(category, byDay, byCategory.getOrDefault(category.name(), List.of()),
                    category == Category.METER_READING ? duplicates : null));
        }
        return new Statistics(Instant.now(), fit, days, repository.regionalInsights(Region.values()), reachable());
    }

    /** Blackbox probes each storage directly, independent of traffic: the same signal as the StorageBackendDown alert. */
    private Map<Backend, Boolean> reachable() {
        Map<Backend, Boolean> result = new EnumMap<>(Backend.class);
        if (prometheus.available()) {
            prometheus.vector("probe_success{job=\"storage\"}", "backend", Instant.now()).forEach((name, up) -> {
                try {
                    result.put(Backend.valueOf(name), up >= 1);
                } catch (IllegalArgumentException ignored) {
                    // a probe for a storage FileGrid does not know: not shown
                }
            });
        }
        return result;
    }

    private Long counter(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? null : (long) c.count();
    }

    private static Candidate candidate(Backend backend, Collection<Timer> timers, Long storedBytes) {
        long count = 0, errors = 0;
        double writeSeconds = 0;
        TreeMap<Double, Double> buckets = new TreeMap<>(); // upper bound (ms) -> cumulative count
        for (Timer t : timers) {
            count += t.count();
            if ("error".equals(t.getId().getTag("outcome"))) errors += t.count();
            String op = t.getId().getTag("operation");
            if (op != null && !op.startsWith("read") && !op.startsWith("get")) writeSeconds += t.totalTime(TimeUnit.SECONDS);
            HistogramSnapshot snapshot = t.takeSnapshot();
            for (CountAtBucket b : snapshot.histogramCounts()) {
                buckets.merge(b.bucket(TimeUnit.MILLISECONDS), b.count(), Double::sum);
            }
        }
        if (count == 0) return null;
        Double throughput = storedBytes != null && writeSeconds > 0 ? storedBytes / 1e6 / writeSeconds : null;
        double errorRate = 100.0 * errors / count;
        Double p95 = percentile(buckets, count, 0.95);
        String verdict = "Măsurat pe %d operații; p95 %s ms, erori %.2f%%.".formatted(count,
                p95 == null ? "—" : "%.0f".formatted(p95), errorRate);
        return new Candidate(backend, percentile(buckets, count, 0.50), p95, percentile(buckets, count, 0.99),
                throughput, errorRate, null, count, verdict);
    }

    /** Linear interpolation inside the first histogram bucket that reaches the rank, like histogram_quantile. */
    static Double percentile(TreeMap<Double, Double> cumulative, long total, double q) {
        if (cumulative.isEmpty()) return null;
        double rank = q * total, lower = 0, below = 0;
        for (Map.Entry<Double, Double> e : cumulative.entrySet()) {
            if (e.getValue() >= rank) {
                double inBucket = e.getValue() - below;
                return inBucket <= 0 ? e.getKey() : lower + (e.getKey() - lower) * (rank - below) / inBucket;
            }
            lower = e.getKey();
            below = e.getValue();
        }
        return lower; // above the largest bucket
    }

    /** Normal = median period by count, peak = busiest one, over the periods that had any traffic. */
    private DayMetrics day(Category category, boolean byDay, List<DayRow> rows, Long retried) {
        String bucket = byDay ? "DAY" : "HOUR";
        if (rows.isEmpty()) {
            return new DayMetrics(category, bucket, null, null, 0, 0, 0, 0, null, null, 0, 0, retried, true);
        }
        List<DayRow> sorted = rows.stream().sorted((a, b) -> Long.compare(a.files(), b.files())).toList();
        DayRow normal = sorted.get(sorted.size() / 2), peak = sorted.getLast();
        return new DayMetrics(category, bucket, normal.start(), peak.start(), normal.files(), peak.files(),
                normal.megabytes(), peak.megabytes(), p95In(category, normal.start(), byDay),
                p95In(category, peak.start(), byDay), normal.errorRatePercent(), peak.errorRatePercent(), retried,
                peak.errorRatePercent() < 1.0);
    }

    /** p95 of the storage calls of one category during one day/hour, from Prometheus (null if unknown). */
    private Double p95In(Category category, Instant start, boolean byDay) {
        if (!prometheus.available() || start == null) {
            return null;
        }
        java.time.Duration length = byDay ? java.time.Duration.ofDays(1) : java.time.Duration.ofHours(1);
        Instant end = start.plus(length).isAfter(Instant.now()) ? Instant.now() : start.plus(length);
        long seconds = Math.max(60, java.time.Duration.between(start, end).toSeconds());
        Double p95 = prometheus.vector("histogram_quantile(0.95, sum by (le) (increase(%s{category=\"%s\"}[%ds])))"
                .formatted(BUCKETS, category, seconds), "", end).get("");
        return p95 == null ? null : p95 * 1000;
    }

    /** Last 24 h across every replica: latency percentiles, throughput and error rate per backend. */
    private Map<Backend, Candidate> fromPrometheus(Category category, Map<String, Long> bytes) {
        String filter = "category=\"%s\"".formatted(category);
        Instant now = Instant.now();
        Map<String, Double> ops = prometheus.vector("sum by (backend) (increase(%s{%s}[24h]))".formatted(COUNT, filter), "backend", now);
        Map<String, Double> errors = prometheus.vector(
                "sum by (backend) (increase(%s{%s,outcome=\"error\"}[24h]))".formatted(COUNT, filter), "backend", now);
        Map<String, Double> writeSeconds = prometheus.vector(
                "sum by (backend) (increase(%s{%s,operation=~\"put|part\"}[24h]))".formatted(SUM, filter), "backend", now);
        Map<String, Map<String, Double>> quantiles = new java.util.HashMap<>();
        for (String q : List.of("0.5", "0.95", "0.99")) {
            quantiles.put(q, prometheus.vector("histogram_quantile(%s, sum by (le, backend) (increase(%s{%s}[24h])))"
                    .formatted(q, BUCKETS, filter), "backend", now));
        }
        Map<Backend, Candidate> result = new EnumMap<>(Backend.class);
        ops.forEach((name, count) -> {
            Backend backend;
            try {
                backend = Backend.valueOf(name);
            } catch (IllegalArgumentException e) {
                return;
            }
            long n = Math.round(count);
            if (n == 0) return;
            double errorRate = 100.0 * errors.getOrDefault(name, 0.0) / count;
            Double seconds = writeSeconds.get(name);
            Long stored = bytes.get(category + "/" + name);
            Double throughput = stored != null && seconds != null && seconds > 0 ? stored / 1e6 / seconds : null;
            Double p95 = ms(quantiles.get("0.95").get(name));
            result.put(backend, new Candidate(backend, ms(quantiles.get("0.5").get(name)), p95,
                    ms(quantiles.get("0.99").get(name)), throughput, errorRate, null, n,
                    "Măsurat pe %d operații în 24 h (toate instanțele); p95 %s ms, erori %.2f%%.".formatted(n,
                            p95 == null ? "—" : "%.0f".formatted(p95), errorRate)));
        });
        return result;
    }

    private static Double ms(Double seconds) {
        return seconds == null ? null : seconds * 1000;
    }
}
