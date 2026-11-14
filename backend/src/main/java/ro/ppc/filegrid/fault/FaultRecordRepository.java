package ro.ppc.filegrid.fault;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.fault.FaultRecords.Metadata;
import ro.ppc.filegrid.fault.FaultRecords.Severity;
import ro.ppc.filegrid.fault.FaultRecords.Summary;
import ro.ppc.filegrid.fault.FaultRecords.View;
import ro.ppc.filegrid.storage.StoredFileInfo;
import ro.ppc.filegrid.storage.StoredFileRepository;

@Repository
public class FaultRecordRepository {

    private static final String SELECT = "SELECT fr.*, " + StoredFileInfo.COLUMNS
            + " FROM fault_record fr JOIN stored_file f ON f.id = fr.file_id ";
    private static final String FILTER = """
            WHERE f.status = 'STORED'
              AND (CAST(:region AS text) IS NULL OR fr.region = :region)
              AND (CAST(:county AS text) IS NULL OR fr.county = :county)
              AND (CAST(:severity AS text) IS NULL OR fr.severity = :severity)
              AND (CAST(:from AS timestamptz) IS NULL OR fr.occurred_at >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR fr.occurred_at < :to)
            """;

    private final JdbcClient jdbc;

    public FaultRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, Metadata m, String county, UUID fileId, String reportedBy) {
        jdbc.sql("""
                        INSERT INTO fault_record (id, region, county, locality, substation_code, occurred_at,
                                                  restored_at, severity, cause, affected_customers, description,
                                                  file_id, reported_by)
                        VALUES (:id, :region, :county, :locality, :substation, :occurredAt, :restoredAt, :severity,
                                :cause, :affected, :description, :fileId, :reportedBy)
                        """)
                .param("id", id)
                .param("region", m.region().name())
                .param("county", county)
                .param("locality", m.locality())
                .param("substation", m.substationCode().strip())
                .param("occurredAt", utc(m.occurredAt()))
                .param("restoredAt", utc(m.restoredAt()))
                .param("severity", m.severity().name())
                .param("cause", m.cause())
                .param("affected", m.affectedCustomers())
                .param("description", m.description())
                .param("fileId", fileId)
                .param("reportedBy", reportedBy)
                .update();
    }

    public Optional<View> find(UUID id) {
        return jdbc.sql(SELECT + "WHERE fr.id = :id AND f.status = 'STORED'").param("id", id)
                .query(FaultRecordRepository::map).optional();
    }

    /** Newest first. Null filters match everything. */
    public List<View> search(Region region, String county, Severity severity, Instant from, Instant to,
                             int limit, long offset) {
        return filter(jdbc.sql(SELECT + FILTER + "ORDER BY fr.occurred_at DESC, fr.id LIMIT :limit OFFSET :offset"),
                region, county, severity, from, to)
                .param("limit", limit).param("offset", offset)
                .query(FaultRecordRepository::map).list();
    }

    public long count(Region region, String county, Severity severity, Instant from, Instant to) {
        return filter(jdbc.sql("SELECT count(*) FROM fault_record fr JOIN stored_file f ON f.id = fr.file_id " + FILTER),
                region, county, severity, from, to).query(Long.class).single();
    }

    /** Faults in a county that are not restored yet, newest first. */
    public List<Summary> activeIn(Region region, String county) {
        return jdbc.sql("""
                        SELECT fr.* FROM fault_record fr JOIN stored_file f ON f.id = fr.file_id
                        WHERE f.status = 'STORED' AND fr.region = :region AND fr.county = :county
                          AND fr.restored_at IS NULL
                        ORDER BY fr.occurred_at DESC LIMIT 50
                        """)
                .param("region", region.name()).param("county", county)
                .query((rs, n) -> new Summary(rs.getObject("id", UUID.class), rs.getString("county"),
                        rs.getString("locality"), rs.getString("substation_code"),
                        StoredFileRepository.instant(rs, "occurred_at"), StoredFileRepository.instant(rs, "restored_at"),
                        Severity.valueOf(rs.getString("severity")), rs.getString("cause"),
                        rs.getObject("affected_customers", Integer.class)))
                .list();
    }

    /** Returns false if the fault does not exist or the time is before it occurred. */
    public boolean markRestored(UUID id, Instant restoredAt) {
        return jdbc.sql("UPDATE fault_record SET restored_at = :at WHERE id = :id AND occurred_at <= :at")
                .param("id", id).param("at", utc(restoredAt)).update() == 1;
    }

    private static JdbcClient.StatementSpec filter(JdbcClient.StatementSpec spec, Region region, String county,
                                                   Severity severity, Instant from, Instant to) {
        return spec.param("region", region == null ? null : region.name())
                .param("county", county)
                .param("severity", severity == null ? null : severity.name())
                .param("from", utc(from))
                .param("to", utc(to));
    }

    private static View map(ResultSet rs, int row) throws SQLException {
        return new View(rs.getObject("id", UUID.class), Region.valueOf(rs.getString("region")),
                rs.getString("county"), rs.getString("locality"), rs.getString("substation_code"),
                StoredFileRepository.instant(rs, "occurred_at"), StoredFileRepository.instant(rs, "restored_at"),
                Severity.valueOf(rs.getString("severity")), rs.getString("cause"),
                rs.getObject("affected_customers", Integer.class), rs.getString("description"),
                rs.getString("reported_by"), StoredFileRepository.instant(rs, "created_at"), StoredFileInfo.map(rs));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
