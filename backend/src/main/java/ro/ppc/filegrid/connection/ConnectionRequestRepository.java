package ro.ppc.filegrid.connection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.ClientRow;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.DocumentRow;
import ro.ppc.filegrid.connection.ConnectionTypes.ApplicantType;
import ro.ppc.filegrid.connection.ConnectionTypes.DocumentType;
import ro.ppc.filegrid.connection.ConnectionTypes.RequestType;
import ro.ppc.filegrid.connection.ConnectionTypes.Status;

@Repository
public class ConnectionRequestRepository {

    private final JdbcClient jdbc;

    public ConnectionRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ClientRow row) {
        jdbc.sql("""
                        INSERT INTO client (client_id, region, applicant_type, request_type, county, locality,
                                            requested_power_kw, identity_hmac, archive_file_id, status,
                                            submitted_by)
                        VALUES (:clientId, :region, :applicantType, :requestType, :county, :locality,
                                :power, :identityHmac, :archiveFileId, 'PENDING', :submittedBy)
                        """)
                .param("clientId", row.clientId())
                .param("region", row.region().name())
                .param("applicantType", row.applicantType().name())
                .param("requestType", row.requestType().name())
                .param("county", row.county())
                .param("locality", row.locality())
                .param("power", row.requestedPowerKw())
                .param("identityHmac", row.identityHmac())
                .param("archiveFileId", row.archiveFileId())
                .param("submittedBy", row.submittedBy())
                .update();
    }

    public void insertDocument(UUID clientId, Region region, DocumentRow doc) {
        jdbc.sql("""
                        INSERT INTO client_document (id, client_id, region, document_type, entry_name, content_type,
                                                     size_bytes, sha256, issuer, issued_on, valid_until)
                        VALUES (:id, :clientId, :region, :type, :entryName, :contentType,
                                :size, :sha256, :issuer, :issuedOn, :validUntil)
                        """)
                .param("id", doc.id())
                .param("clientId", clientId)
                .param("region", region.name())
                .param("type", doc.documentType().name())
                .param("entryName", doc.entryName())
                .param("contentType", doc.contentType())
                .param("size", doc.sizeBytes())
                .param("sha256", doc.sha256())
                .param("issuer", doc.issuer())
                .param("issuedOn", doc.issuedOn())
                .param("validUntil", doc.validUntil())
                .update();
    }

    public Optional<ClientRow> find(UUID clientId) {
        return jdbc.sql("SELECT * FROM client WHERE client_id = :id")
                .param("id", clientId)
                .query(ConnectionRequestRepository::mapClient)
                .optional();
    }

    public List<ClientRow> findBySubmitter(String submittedBy) {
        return jdbc.sql("SELECT * FROM client WHERE submitted_by = :sub ORDER BY created_at DESC LIMIT 100")
                .param("sub", submittedBy)
                .query(ConnectionRequestRepository::mapClient)
                .list();
    }

    public List<ClientRow> findByIdentity(String identityHmac, UUID excluding) {
        return jdbc.sql("""
                        SELECT * FROM client
                        WHERE identity_hmac = :hmac AND client_id <> :excluding
                        ORDER BY created_at DESC LIMIT 50
                        """)
                .param("hmac", identityHmac)
                .param("excluding", excluding)
                .query(ConnectionRequestRepository::mapClient)
                .list();
    }

    /** Admin queue, oldest first so requests are handled in arrival order. Null filters match everything. */
    public List<ClientRow> search(Status status, Region region, int limit, long offset) {
        return jdbc.sql("""
                        SELECT * FROM client
                        WHERE (CAST(:status AS text) IS NULL OR status = :status)
                          AND (CAST(:region AS text) IS NULL OR region = :region)
                        ORDER BY created_at, client_id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("status", status == null ? null : status.name())
                .param("region", region == null ? null : region.name())
                .param("limit", limit)
                .param("offset", offset)
                .query(ConnectionRequestRepository::mapClient)
                .list();
    }

    public long count(Status status, Region region) {
        return jdbc.sql("""
                        SELECT count(*) FROM client
                        WHERE (CAST(:status AS text) IS NULL OR status = :status)
                          AND (CAST(:region AS text) IS NULL OR region = :region)
                        """)
                .param("status", status == null ? null : status.name())
                .param("region", region == null ? null : region.name())
                .query(Long.class)
                .single();
    }

    /**
     * PENDING -> ACCEPTED/REJECTED, guarded by the current status so two admins
     * deciding at the same time cannot both win. Returns false if it was not PENDING.
     */
    public boolean decide(UUID clientId, Status status, String reason, String reviewer) {
        return jdbc.sql("""
                        UPDATE client
                        SET status = :status, status_reason = :reason, reviewed_by = :reviewer, reviewed_at = now()
                        WHERE client_id = :id AND status = 'PENDING'
                        """)
                .param("id", clientId)
                .param("status", status.name())
                .param("reason", reason)
                .param("reviewer", reviewer)
                .update() == 1;
    }

    public List<DocumentRow> documents(UUID clientId) {
        return jdbc.sql("SELECT * FROM client_document WHERE client_id = :id ORDER BY entry_name")
                .param("id", clientId)
                .query((rs, n) -> new DocumentRow(
                        rs.getObject("id", UUID.class),
                        DocumentType.valueOf(rs.getString("document_type")),
                        rs.getString("entry_name"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("sha256"),
                        rs.getString("issuer"),
                        rs.getObject("issued_on", LocalDate.class),
                        rs.getObject("valid_until", LocalDate.class)))
                .list();
    }

    private static ClientRow mapClient(ResultSet rs, int row) throws SQLException {
        return new ClientRow(
                rs.getObject("client_id", UUID.class),
                Region.valueOf(rs.getString("region")),
                ApplicantType.valueOf(rs.getString("applicant_type")),
                RequestType.valueOf(rs.getString("request_type")),
                rs.getString("county"),
                rs.getString("locality"),
                rs.getBigDecimal("requested_power_kw"),
                rs.getString("identity_hmac"),
                rs.getObject("archive_file_id", UUID.class),
                Status.valueOf(rs.getString("status")),
                rs.getString("status_reason"),
                rs.getString("reviewed_by"),
                instant(rs, "reviewed_at"),
                rs.getString("submitted_by"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
