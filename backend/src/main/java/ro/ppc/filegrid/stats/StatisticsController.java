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

    public record DayMetrics(Category category, long normalFilesPerDay, long peakFilesPerDay, double normalVolumeMb,
                             double peakVolumeMb, Double normalP95Ms, Double peakP95Ms, double normalErrorRatePercent,
                             double peakErrorRatePercent, Long resumedOrRetried, boolean slaMet) {
    }

    public record Statistics(Instant generatedAt, List<StorageFit> storageFit, List<DayMetrics> normalVsPeak,
                             List<StatisticsRepository.RegionInsight> regionalInsights) {
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

    private final MeterRegistry meters;
    private final StatisticsRepository repository;

    public StatisticsController(MeterRegistry meters, StatisticsRepository repository) {
        this.meters = meters;
        this.repository = repository;
    }

    @Operation(summary = "Storage fit per category, normal vs peak day, grid insights per region")
    @GetMapping
    public Statistics statistics() {
        Map<String, Long> bytes = repository.storedBytes();
        List<StorageFit> fit = new ArrayList<>();
        for (Category category : Category.values()) {
            Text text = TEXT.get(category);
            List<Candidate> candidates = new ArrayList<>();
            for (Backend backend : Backend.values()) {
                Collection<Timer> timers = meters.find("filegrid.storage.operation")
                        .tag("category", category.name()).tag("backend", backend.name()).timers();
                Candidate c = candidate(backend, timers, bytes.get(category + "/" + backend));
                if (c != null || backend == text.recommended()) {
                    candidates.add(c != null ? c : new Candidate(backend, null, null, null, null, null, null, 0,
                            "Nemăsurat încă: nicio operație de la pornirea serverului."));
                }
            }
            fit.add(new StorageFit(category, text.size(), text.guarantee(), text.recommended(), text.why(), candidates));
        }
        Long duplicates = counter("filegrid.meter.raw", "status", "duplicate");
        List<DayMetrics> days = new ArrayList<>();
        Map<String, List<DayRow>> byCategory = repository.dailyVolumes(30);
        for (Category category : Category.values()) {
            days.add(day(category, byCategory.getOrDefault(category.name(), List.of()),
                    category == Category.METER_READING ? duplicates : null));
        }
        return new Statistics(Instant.now(), fit, days, repository.regionalInsights(Region.values()));
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

    /** Normal = median day by count, peak = busiest day, over the days that had any traffic. */
    private static DayMetrics day(Category category, List<DayRow> rows, Long retried) {
        if (rows.isEmpty()) return new DayMetrics(category, 0, 0, 0, 0, null, null, 0, 0, retried, true);
        List<DayRow> sorted = rows.stream().sorted((a, b) -> Long.compare(a.files(), b.files())).toList();
        DayRow normal = sorted.get(sorted.size() / 2), peak = sorted.getLast();
        return new DayMetrics(category, normal.files(), peak.files(), normal.megabytes(), peak.megabytes(), null, null,
                normal.errorRatePercent(), peak.errorRatePercent(), retried, peak.errorRatePercent() < 1.0);
    }
}
