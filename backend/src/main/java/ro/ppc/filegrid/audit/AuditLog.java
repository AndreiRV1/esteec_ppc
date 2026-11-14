package ro.ppc.filegrid.audit;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.common.TraceIds;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only audit trail ({@code audit_log}; UPDATE/DELETE are blocked in the database).
 * Call it inside the business transaction so the entry and the change commit together.
 * Never put personal data in {@code details}.
 */
@Component
public class AuditLog {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TraceIds traceIds;

    public AuditLog(JdbcClient jdbc, ObjectMapper json, TraceIds traceIds) {
        this.jdbc = jdbc;
        this.json = json;
        this.traceIds = traceIds;
    }

    public void record(Region region, String actor, String action, String entityType, Object entityId,
                       Map<String, ?> details) {
        jdbc.sql("""
                        INSERT INTO audit_log (region, actor, action, entity_type, entity_id, trace_id, details)
                        VALUES (:region, :actor, :action, :entityType, :entityId, :traceId, CAST(:details AS jsonb))
                        """)
                .param("region", region.name())
                .param("actor", actor)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", String.valueOf(entityId))
                .param("traceId", traceIds.current())
                .param("details", json.writeValueAsString(details == null ? Map.of() : details))
                .update();
    }
}
