package ro.ppc.filegrid.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** What API responses show about the physical file behind a record. */
public record StoredFileInfo(UUID fileId, String filename, String contentType, Long sizeBytes, String sha256,
                             String backend, String fallbackFrom) {

    /** Columns to select from {@code stored_file} aliased {@code f}. */
    public static final String COLUMNS = """
            f.id AS file_id, f.original_filename AS file_name, f.content_type AS file_content_type, \
            f.size_bytes AS file_size, f.sha256 AS file_sha256, f.backend AS file_backend, \
            f.fallback_from AS file_fallback_from""";

    public static StoredFileInfo map(ResultSet rs) throws SQLException {
        return new StoredFileInfo(rs.getObject("file_id", UUID.class), rs.getString("file_name"),
                rs.getString("file_content_type"), rs.getObject("file_size", Long.class),
                rs.getString("file_sha256"), rs.getString("file_backend"), rs.getString("file_fallback_from"));
    }
}
