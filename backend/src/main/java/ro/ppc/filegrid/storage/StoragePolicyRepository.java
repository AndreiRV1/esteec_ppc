package ro.ppc.filegrid.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ro.ppc.filegrid.common.Region;

/** Read on every upload, so a policy change in the database applies to the next file. */
@Repository
public class StoragePolicyRepository {

    private final JdbcClient jdbc;

    public StoragePolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoragePolicy> find(FileCategory category, Region region) {
        return jdbc.sql("""
                        SELECT category, region, backend, target, encrypt_at_rest, max_size_bytes
                        FROM storage_policy
                        WHERE category = :category AND region = :region
                        """)
                .param("category", category.name())
                .param("region", region.name())
                .query((rs, n) -> new StoragePolicy(
                        FileCategory.valueOf(rs.getString("category")),
                        Region.valueOf(rs.getString("region")),
                        StorageBackendType.valueOf(rs.getString("backend")),
                        rs.getString("target"),
                        rs.getBoolean("encrypt_at_rest"),
                        rs.getLong("max_size_bytes")))
                .optional();
    }

    public List<StoragePolicyAdminController.PolicyView> list() {
        return jdbc.sql("SELECT * FROM storage_policy ORDER BY category, region")
                .query((rs, n) -> new StoragePolicyAdminController.PolicyView(
                        FileCategory.valueOf(rs.getString("category")),
                        Region.valueOf(rs.getString("region")),
                        StorageBackendType.valueOf(rs.getString("backend")),
                        rs.getString("target"),
                        rs.getBoolean("encrypt_at_rest"),
                        rs.getLong("max_size_bytes"),
                        StoredFileRepository.instant(rs, "updated_at"),
                        rs.getString("updated_by")))
                .list();
    }

    public void update(FileCategory category, Region region, StorageBackendType backend, String target,
                       boolean encryptAtRest, long maxSizeBytes, String updatedBy) {
        jdbc.sql("""
                        UPDATE storage_policy
                        SET backend = :backend, target = :target, encrypt_at_rest = :encrypt,
                            max_size_bytes = :maxSize, updated_by = :updatedBy
                        WHERE category = :category AND region = :region
                        """)
                .param("backend", backend.name())
                .param("target", target)
                .param("encrypt", encryptAtRest)
                .param("maxSize", maxSizeBytes)
                .param("updatedBy", updatedBy)
                .param("category", category.name())
                .param("region", region.name())
                .update();
    }
}
