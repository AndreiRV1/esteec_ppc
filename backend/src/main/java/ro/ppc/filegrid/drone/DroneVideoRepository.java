package ro.ppc.filegrid.drone;

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
import ro.ppc.filegrid.drone.DroneVideos.UploadRequest;
import ro.ppc.filegrid.drone.DroneVideos.View;
import ro.ppc.filegrid.storage.StoredFileInfo;
import ro.ppc.filegrid.storage.StoredFileRepository;

@Repository
public class DroneVideoRepository {

    private static final String SELECT = "SELECT dv.*, " + StoredFileInfo.COLUMNS
            + " FROM drone_video dv JOIN stored_file f ON f.id = dv.file_id ";
    /** A video exists for readers only once its upload is complete and verified. */
    private static final String FILTER = """
            WHERE f.status = 'STORED'
              AND (CAST(:region AS text) IS NULL OR dv.region = :region)
              AND (CAST(:lineCode AS text) IS NULL OR dv.line_code = :lineCode)
              AND (CAST(:from AS timestamptz) IS NULL OR dv.flight_started_at >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR dv.flight_started_at < :to)
            """;

    private final JdbcClient jdbc;

    public DroneVideoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UploadRequest r, String county, UUID fileId, String uploadedBy) {
        jdbc.sql("""
                        INSERT INTO drone_video (id, region, county, line_code, drone_id, flight_started_at,
                                                 duration_seconds, notes, file_id, uploaded_by)
                        VALUES (:id, :region, :county, :lineCode, :droneId, :flightStartedAt, :duration, :notes,
                                :fileId, :uploadedBy)
                        """)
                .param("id", id)
                .param("region", r.region().name())
                .param("county", county)
                .param("lineCode", r.lineCode().strip())
                .param("droneId", r.droneId())
                .param("flightStartedAt", utc(r.flightStartedAt()))
                .param("duration", r.durationSeconds())
                .param("notes", r.notes())
                .param("fileId", fileId)
                .param("uploadedBy", uploadedBy)
                .update();
    }

    public Optional<View> find(UUID id) {
        return jdbc.sql(SELECT + "WHERE dv.id = :id AND f.status = 'STORED'").param("id", id)
                .query(DroneVideoRepository::map).optional();
    }

    public Optional<View> findByFile(UUID fileId) {
        return jdbc.sql(SELECT + "WHERE dv.file_id = :id").param("id", fileId)
                .query(DroneVideoRepository::map).optional();
    }

    /** Newest flights first. Null filters match everything. */
    public List<View> search(Region region, String lineCode, Instant from, Instant to, int limit, long offset) {
        return filter(jdbc.sql(SELECT + FILTER
                + "ORDER BY dv.flight_started_at DESC, dv.id LIMIT :limit OFFSET :offset"), region, lineCode, from, to)
                .param("limit", limit).param("offset", offset)
                .query(DroneVideoRepository::map).list();
    }

    public long count(Region region, String lineCode, Instant from, Instant to) {
        return filter(jdbc.sql("SELECT count(*) FROM drone_video dv JOIN stored_file f ON f.id = dv.file_id " + FILTER),
                region, lineCode, from, to).query(Long.class).single();
    }

    private static JdbcClient.StatementSpec filter(JdbcClient.StatementSpec spec, Region region, String lineCode,
                                                   Instant from, Instant to) {
        return spec.param("region", region == null ? null : region.name())
                .param("lineCode", lineCode == null || lineCode.isBlank() ? null : lineCode.strip())
                .param("from", utc(from))
                .param("to", utc(to));
    }

    private static View map(ResultSet rs, int row) throws SQLException {
        return new View(rs.getObject("id", UUID.class), Region.valueOf(rs.getString("region")),
                rs.getString("county"), rs.getString("line_code"), rs.getString("drone_id"),
                StoredFileRepository.instant(rs, "flight_started_at"),
                rs.getObject("duration_seconds", Integer.class), rs.getString("notes"),
                rs.getString("uploaded_by"), StoredFileRepository.instant(rs, "created_at"), StoredFileInfo.map(rs));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
