package ro.ppc.filegrid.config;

import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Tags every HTTP server metric with the data category of the route
 * ({@code http_server_requests_seconds{category=...}}), so dashboards and alerts can
 * show each category's latency and errors separately. Empty for other routes.
 */
@Component
public class CategoryObservationConvention extends DefaultServerRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and("category", category(context.getCarrier().getRequestURI()));
    }

    static String category(String path) {
        if (path == null) return "";
        if (path.startsWith("/api/v1/meter-readings")) return "METER_READING";
        if (path.startsWith("/api/v1/fault-records")) return "FAULT_RECORD";
        if (path.startsWith("/api/v1/drone-videos")) return "DRONE_VIDEO";
        if (path.startsWith("/api/v1/connection-requests") || path.startsWith("/api/v1/admin/connection-requests")) {
            return "CONNECTION_DOCUMENTS";
        }
        return "";
    }
}
