package ro.ppc.filegrid.storage;

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
}
