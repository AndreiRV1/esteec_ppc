package ro.ppc.filegrid.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.crypto.EnvelopeEncryption;

@Repository
public class StoredFileRepository {

    private final JdbcClient jdbc;

    public StoredFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertUploading(UUID id, Region region, FileCategory category, StorageBackendType backend,
                                String location, String originalFilename, String contentType,
                                EnvelopeEncryption.DataKey key, String uploadedBy, String traceId) {
        jdbc.sql("""
                        INSERT INTO stored_file (id, region, category, backend, location, original_filename,
                                                 content_type, encryption, encryption_key_id, wrapped_dek,
                                                 status, uploaded_by, trace_id)
                        VALUES (:id, :region, :category, :backend, :location, :filename,
                                :contentType, :encryption, :keyId, :wrappedDek,
                                'UPLOADING', :uploadedBy, :traceId)
                        """)
                .param("id", id)
                .param("region", region.name())
                .param("category", category.name())
                .param("backend", backend.name())
                .param("location", location)
                .param("filename", originalFilename)
                .param("contentType", contentType)
                .param("encryption", key == null ? "NONE" : EnvelopeEncryption.ALGORITHM)
                .param("keyId", key == null ? null : key.keyId())
                .param("wrappedDek", key == null ? null : key.wrappedKey())
                .param("uploadedBy", uploadedBy)
                .param("traceId", traceId)
                .update();
    }

    public void markStored(UUID id, long size, String sha256, String storedSha256) {
        jdbc.sql("""
                        UPDATE stored_file
                        SET status = 'STORED', size_bytes = :size, bytes_received = :size,
                            sha256 = :sha256, stored_sha256 = :storedSha256, stored_at = now()
                        WHERE id = :id AND status = 'UPLOADING'
                        """)
                .param("id", id)
                .param("size", size)
                .param("sha256", sha256)
                .param("storedSha256", storedSha256)
                .update();
    }

    public void markStatus(UUID id, StoredFile.Status status) {
        jdbc.sql("UPDATE stored_file SET status = :status WHERE id = :id")
                .param("id", id)
                .param("status", status.name())
                .update();
    }

    public void markVerified(UUID id) {
        jdbc.sql("UPDATE stored_file SET last_verified_at = now() WHERE id = :id").param("id", id).update();
    }

    public Optional<StoredFile> find(UUID id) {
        return jdbc.sql("SELECT * FROM stored_file WHERE id = :id")
                .param("id", id)
                .query(StoredFileRepository::map)
                .optional();
    }

    private static StoredFile map(ResultSet rs, int row) throws SQLException {
        return new StoredFile(
                rs.getObject("id", UUID.class),
                Region.valueOf(rs.getString("region")),
                FileCategory.valueOf(rs.getString("category")),
                StorageBackendType.valueOf(rs.getString("backend")),
                rs.getString("location"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getObject("size_bytes", Long.class),
                rs.getString("sha256"),
                rs.getString("stored_sha256"),
                rs.getString("encryption"),
                rs.getString("encryption_key_id"),
                rs.getBytes("wrapped_dek"),
                StoredFile.Status.valueOf(rs.getString("status")),
                rs.getString("uploaded_by"),
                instant(rs, "created_at"),
                instant(rs, "stored_at"),
                instant(rs, "last_verified_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
