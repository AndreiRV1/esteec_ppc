package ro.ppc.filegrid.storage;

import java.time.Instant;
import java.util.UUID;
import ro.ppc.filegrid.common.Region;

/** A row of {@code stored_file}. {@code sha256} covers the bytes the user sent, {@code storedSha256} the bytes written. */
public record StoredFile(
        UUID id,
        Region region,
        FileCategory category,
        StorageBackendType backend,
        String location,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        String sha256,
        String storedSha256,
        String encryption,
        String encryptionKeyId,
        byte[] wrappedDek,
        Status status,
        String uploadId,
        long bytesReceived,
        String uploadedBy,
        Instant createdAt,
        Instant storedAt,
        Instant lastVerifiedAt,
        // The storage the policy asked for, when that one was down and the file went elsewhere; else null.
        StorageBackendType fallbackFrom) {

    public enum Status {
        UPLOADING,
        STORED,
        FAILED,
        CORRUPTED,
        DELETED
    }

    public boolean encrypted() {
        return !"NONE".equals(encryption);
    }

    public ObjectStore.Address address() {
        return new ObjectStore.Address(id, region, location);
    }
}
