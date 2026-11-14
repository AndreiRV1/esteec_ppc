package ro.ppc.filegrid.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import ro.ppc.filegrid.common.Region;

/**
 * A physical storage backend. Implementations throw {@link StorageUnavailableException}
 * when the backend cannot be reached, so the caller can fail just that category.
 */
public interface ObjectStore {

    StorageBackendType type();

    /** Where a new file should be written; {@code null} for backends addressed by file id. */
    default String locationFor(StoragePolicy policy, UUID fileId, Instant now) {
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        return "%s/%s/%s/%s".formatted(
                policy.target(), policy.region().name().toLowerCase(Locale.ROOT), month, fileId);
    }

    /** Writes {@code size} bytes from {@code source}. Must not leave a partial object visible on failure. */
    void put(Address address, Path source, long size);

    InputStream open(Address address);

    // --- resumable uploads: parts are written in order, each at a known offset ---

    /** Starts a resumable upload. Returns a backend upload id, or {@code null} if the backend needs none. */
    String beginParts(Address address);

    /** Writes one part at {@code offset}. Writing the same part again replaces it. */
    void putPart(Address address, String uploadId, int partNumber, long offset, Path source, long size);

    /** Makes the uploaded object visible. Calling it again after success is a no-op. */
    void completeParts(Address address, String uploadId);

    void abortParts(Address address, String uploadId);

    void delete(Address address);

    record Address(UUID fileId, Region region, String location) {
    }
}
