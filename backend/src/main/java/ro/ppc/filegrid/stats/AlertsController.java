package ro.ppc.filegrid.stats;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ro.ppc.filegrid.common.ApiException;

/**
 * Alerts that are firing now, as Alertmanager sees them: which business requirement of the spec
 * (table 2.1) is at risk and why. The same alerts are e-mailed to the on-call team.
 */
@RestController
@RequestMapping("/api/v1/admin/alerts")
@Tag(name = "Alerts (admin)", description = "Business requirements at risk, right now")
public class AlertsController {

    public record Alert(String name, String severity, String requirement, String summary, String description,
                        String category, String backend, Instant startsAt) {
    }

    private final RestClient http = RestClient.create();
    private final String alertmanagerUrl;

    public AlertsController(@Value("${filegrid.alertmanager.url:}") String alertmanagerUrl) {
        this.alertmanagerUrl = alertmanagerUrl;
    }

    @Operation(summary = "Firing alerts, most severe first")
    @GetMapping
    public List<Alert> firing() {
        if (alertmanagerUrl.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "alerts_unavailable",
                    "Alertmanager is not configured (FILEGRID_ALERTMANAGER_URL)");
        }
        List<Map<String, Object>> raw;
        try {
            raw = http.get()
                    .uri(alertmanagerUrl + "/api/v2/alerts?active=true&silenced=false&inhibited=false")
                    .retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() {
                    });
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "alerts_unavailable",
                    "Alertmanager did not answer; alerts are still e-mailed by Alertmanager itself");
        }
        return raw == null ? List.of() : raw.stream().map(AlertsController::toAlert)
                .sorted(Comparator.comparing((Alert a) -> !"critical".equals(a.severity()))
                        .thenComparing(Alert::startsAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Alert toAlert(Map<String, Object> a) {
        Map<String, String> labels = (Map<String, String>) a.getOrDefault("labels", Map.of());
        Map<String, String> notes = (Map<String, String>) a.getOrDefault("annotations", Map.of());
        Object starts = a.get("startsAt");
        return new Alert(labels.get("alertname"), labels.get("severity"), labels.get("requirement"),
                notes.get("summary"), notes.get("description"), labels.get("category"), labels.get("backend"),
                starts == null ? null : Instant.parse(starts.toString()));
    }
}
