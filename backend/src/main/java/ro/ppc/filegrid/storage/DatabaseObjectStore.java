package ro.ppc.filegrid.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** PostgreSQL {@code stored_file_blob}. Addressed by file id; limited to 100 MB by the policy table. */
@Component
public class DatabaseObjectStore implements ObjectStore {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;

    public DatabaseObjectStore(JdbcTemplate jdbcTemplate, JdbcClient jdbc) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
    }

    @Override
    public StorageBackendType type() {
        return StorageBackendType.DATABASE;
    }

    @Override
    public String locationFor(StoragePolicy policy, java.util.UUID fileId, java.time.Instant now) {
        return null;
    }

    @Override
    public void put(Address address, Path source, long size) {
        try (InputStream in = Files.newInputStream(source)) {
            jdbcTemplate.update(
                    "INSERT INTO stored_file_blob (file_id, region, content) VALUES (?, ?, ?)",
                    ps -> {
                        ps.setObject(1, address.fileId());
                        ps.setString(2, address.region().name());
                        ps.setBinaryStream(3, in, size);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (DataAccessException e) {
            throw new StorageUnavailableException(type(), "put", e);
        }
    }

    @Override
    public InputStream open(Address address) {
        try {
            byte[] content = jdbc.sql("SELECT content FROM stored_file_blob WHERE file_id = :id")
                    .param("id", address.fileId())
                    .query(byte[].class)
                    .optional()
                    .orElseThrow(() -> new MissingObjectException(address, null));
            return new ByteArrayInputStream(content);
        } catch (DataAccessException e) {
            throw new StorageUnavailableException(type(), "get", e);
        }
    }

    @Override
    public void delete(Address address) {
        try {
            jdbc.sql("DELETE FROM stored_file_blob WHERE file_id = :id").param("id", address.fileId()).update();
        } catch (DataAccessException e) {
            throw new StorageUnavailableException(type(), "delete", e);
        }
    }
}
