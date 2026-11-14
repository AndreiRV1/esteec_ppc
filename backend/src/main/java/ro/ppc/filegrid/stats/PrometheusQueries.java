package ro.ppc.filegrid.stats;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Instant queries against Prometheus, which holds the metrics of every backend replica and keeps
 * them across restarts (15 days). Optional: without FILEGRID_PROMETHEUS_URL the statistics fall
 * back to this replica's own metrics.
 */
@Component
public class PrometheusQueries {

    private static final Logger log = LoggerFactory.getLogger(PrometheusQueries.class);

    private final RestClient http = RestClient.create();
    private final String url;

    public PrometheusQueries(@Value("${filegrid.prometheus.url:}") String url) {
        this.url = url;
    }

    public boolean available() {
        return !url.isBlank();
    }

    /** Result of an instant query as label value -> number; label "" for a query without labels. */
    @SuppressWarnings("unchecked")
    public Map<String, Double> vector(String query, String label, Instant at) {
        Map<String, Double> result = new HashMap<>();
        if (!available()) {
            return result;
        }
        try {
            // PromQL has braces: pass it as a template variable so it is fully percent-encoded.
            var uri = UriComponentsBuilder.fromUriString(url + "/api/v1/query")
                    .queryParam("query", "{query}").queryParam("time", "{time}")
                    .encode().buildAndExpand(query, at.getEpochSecond()).toUri();
            Map<String, Object> body = http.get().uri(uri).retrieve().body(Map.class);
            Map<String, Object> data = body == null ? Map.of() : (Map<String, Object>) body.get("data");
            for (Map<String, Object> sample : (List<Map<String, Object>>) data.getOrDefault("result", List.of())) {
                Map<String, String> metric = (Map<String, String>) sample.get("metric");
                double value = Double.parseDouble((String) ((List<Object>) sample.get("value")).get(1));
                if (!Double.isNaN(value)) {
                    result.put(label.isEmpty() ? "" : metric.getOrDefault(label, ""), value);
                }
            }
        } catch (RuntimeException e) { // statistics degrade to "not measured", never fail
            log.warn("Prometheus query failed ({}): {}", query, e.getMessage());
        }
        return result;
    }
}
