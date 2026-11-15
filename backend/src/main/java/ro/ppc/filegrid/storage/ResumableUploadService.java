package ro.ppc.filegrid.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.common.TraceIds;
import ro.ppc.filegrid.common.UuidV7;

/**
 * Large files sent in parts over unstable links. Parts arrive in order, each is
 * spooled to disk and written straight to the backend, so memory use does not
 * depend on the file size, and an interrupted upload continues with the next part.
 * No state is kept in this process: progress lives in {@code stored_file}
 * ({@code bytes_received}, {@code upload_id}), so any instance can take the next part.
 *
 * <p>When the upload is completed the stored object is read back and its SHA-256
 * compared with the one the client announced; only then does the file become STORED.
 */
@Service
public class ResumableUploadService {

    private static final Logger log = LoggerFactory.getLogger(ResumableUploadService.class);
    private static final HexFormat HEX = HexFormat.of();

    private final StoragePolicyRepository policies;
    private final StoredFileRepository files;
    private final StoredFileService storedFiles;
    private final TempFiles tempFiles;
    private final TransactionTemplate tx;
    private final TraceIds traceIds;
    private final long partSize;

    public ResumableUploadService(StoragePolicyRepository policies, StoredFileRepository files,
                                  StoredFileService storedFiles, TempFiles tempFiles, TransactionTemplate tx,
                                  TraceIds traceIds,
                                  @Value("${filegrid.uploads.part-size:8MB}") DataSize partSize) {
        this.policies = policies;
        this.files = files;
        this.storedFiles = storedFiles;
        this.tempFiles = tempFiles;
        this.tx = tx;
        this.traceIds = traceIds;
        this.partSize = partSize.toBytes();
    }

    public record Progress(UUID uploadId, StoredFile.Status status, long sizeBytes, long bytesReceived,
                           long partSizeBytes, List<Integer> receivedParts) {
    }

    public long partSizeBytes() {
        return partSize;
    }

    /**
     * Registers the upload and its declared size and checksum. {@code inSameTransaction}
     * runs with the new (UPLOADING) file, for the category's own metadata row.
     */
    public StoredFile begin(StoredFileService.NewFile spec, long declaredSize, String declaredSha256,
                            Consumer<StoredFile> inSameTransaction) {
        StoragePolicy policy = policies.find(spec.category(), spec.region())
                .orElseThrow(() -> new IllegalStateException(
                        "No storage policy for " + spec.category() + "/" + spec.region()));
        if (policy.encryptAtRest()) {
            throw ApiException.conflict("encryption_not_supported",
                    "Resumable uploads cannot be encrypted at rest; change the storage policy of " + spec.category());
        }
        if (declaredSize > policy.maxSizeBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                    "File exceeds the " + policy.maxSizeBytes() + " byte limit of its storage policy");
        }
        UUID id = UuidV7.generate();
        StoredFileService.Target target = storedFiles.target(policy, id);

        tx.executeWithoutResult(status -> {
            files.insertUploading(id, spec.region(), spec.category(), policy.backend(), target.address().location(),
                    spec.originalFilename(), spec.contentType(), null, spec.uploadedBy(), traceIds.current());
            files.beginUpload(id, null, declaredSize, declaredSha256);
            inSameTransaction.accept(files.find(id).orElseThrow());
        });
        try {
            String backendUploadId;
            try {
                backendUploadId = target.store().beginParts(target.address());
            } catch (StorageUnavailableException down) {
                // No part is stored yet, so the whole upload can still move to the stand-in storage (FTP <-> S3).
                StoredFileService.Target fallback = storedFiles.fallbackFor(target).orElseThrow(() -> down);
                files.relocate(id, fallback.policy().backend(), fallback.address().location(), policy.backend());
                backendUploadId = fallback.store().beginParts(fallback.address());
            }
            if (backendUploadId != null) {
                files.beginUpload(id, backendUploadId, declaredSize, declaredSha256);
            }
        } catch (RuntimeException e) {
            StoredFileService.bestEffort(() -> files.markStatus(id, StoredFile.Status.FAILED), "mark " + id + " FAILED");
            throw e;
        }
        return files.find(id).orElseThrow();
    }

    public StoredFile require(UUID uploadId) {
        return files.find(uploadId).orElseThrow(() -> ApiException.notFound("Upload"));
    }

    public Progress progress(StoredFile file) {
        long total = file.sizeBytes() == null ? 0 : file.sizeBytes();
        int received = file.status() == StoredFile.Status.STORED ? totalParts(total) : receivedParts(file, total);
        return new Progress(file.id(), file.status(), total, file.bytesReceived(), partSize,
                IntStream.rangeClosed(1, received).boxed().toList());
    }

    /**
     * Accepts part {@code partNumber} (1-based). A part the server already has is ignored,
     * a part ahead of the expected one is refused, so a retry can never corrupt the file.
     */
    public Progress putPart(UUID uploadId, int partNumber, InputStream body, long contentLength, String partSha256) {
        StoredFile file = require(uploadId);
        long total = file.sizeBytes();
        int totalParts = totalParts(total);
        if (partNumber < 1 || partNumber > totalParts) {
            throw ApiException.badRequest("invalid_part_number", "Part number must be between 1 and " + totalParts);
        }
        if (file.status() == StoredFile.Status.STORED || partNumber <= receivedParts(file, total)) {
            drain(body);
            return progress(file);
        }
        requireUploading(file);
        int expected = receivedParts(file, total) + 1;
        if (partNumber != expected) {
            throw ApiException.conflict("part_out_of_order", "Expected part " + expected + ", got " + partNumber);
        }
        long offset = (long) (partNumber - 1) * partSize;
        long expectedSize = partNumber < totalParts ? partSize : total - offset;
        if (contentLength != expectedSize) {
            throw ApiException.badRequest("invalid_part_size",
                    "Part " + partNumber + " must be exactly " + expectedSize + " bytes");
        }

        Path spool = tempFiles.create("part-");
        try {
            String sha = spool(body, spool, expectedSize);
            if (partSha256 != null && !partSha256.isBlank() && !sha.equalsIgnoreCase(partSha256.strip())) {
                throw ApiException.badRequest("part_checksum_mismatch",
                        "Part " + partNumber + " does not match its X-Checksum-SHA256; send it again");
            }
            return tx.execute(status -> {
                StoredFile locked = files.lock(uploadId).orElseThrow(() -> ApiException.notFound("Upload"));
                if (locked.status() == StoredFile.Status.STORED || partNumber <= receivedParts(locked, total)) {
                    return progress(locked); // a concurrent retry of the same part won
                }
                requireUploading(locked);
                ObjectStore store = storedFiles.storeFor(locked.backend());
                storedFiles.timedRun(locked.category(), locked.backend(), "put",
                        () -> store.putPart(locked.address(), locked.uploadId(), partNumber, offset, spool, expectedSize));
                files.addBytesReceived(uploadId, expectedSize);
                return progress(files.find(uploadId).orElseThrow());
            });
        } finally {
            TempFiles.deleteQuietly(spool);
        }
    }

    /**
     * Finishes the upload: assembles the object, reads it back, and marks the file STORED
     * only if its SHA-256 equals the declared one. Calling it again returns the same file.
     * {@code inSameTransaction} runs once, in the transaction that marks the file STORED.
     */
    public StoredFile complete(UUID uploadId, Consumer<StoredFile> inSameTransaction) {
        StoredFile file = require(uploadId);
        if (file.status() == StoredFile.Status.STORED) {
            return file;
        }
        requireUploading(file);
        if (file.bytesReceived() != file.sizeBytes()) {
            throw ApiException.conflict("upload_incomplete",
                    "Received " + file.bytesReceived() + " of " + file.sizeBytes() + " bytes");
        }
        ObjectStore store = storedFiles.storeFor(file.backend());
        store.completeParts(file.address(), file.uploadId());

        // Read back what the backend now holds: this is the proof that the stored file is the sent file.
        Measured stored = storedFiles.timed(file.category(), file.backend(), "get", () -> measure(store, file));
        if (stored.size() != file.sizeBytes() || !stored.sha256().equals(file.sha256())) {
            log.error("Upload {} failed verification: declared sha256 {} / {} bytes, stored {} / {} bytes",
                    uploadId, file.sha256(), file.sizeBytes(), stored.sha256(), stored.size());
            StoredFileService.bestEffort(() -> store.delete(file.address()), "delete rejected upload " + uploadId);
            StoredFileService.bestEffort(() -> files.markStatus(uploadId, StoredFile.Status.FAILED), "mark FAILED");
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "checksum_mismatch",
                    "The stored file does not match the declared SHA-256; upload it again");
        }
        tx.executeWithoutResult(status -> {
            if (files.markStored(uploadId, stored.size(), stored.sha256(), stored.sha256())) {
                inSameTransaction.accept(files.find(uploadId).orElseThrow());
            }
        });
        return files.find(uploadId).orElseThrow();
    }

    private int totalParts(long total) {
        return (int) ((total + partSize - 1) / partSize);
    }

    /** Parts are fixed-size and arrive in order, so the count follows from the bytes received. */
    private int receivedParts(StoredFile file, long total) {
        return file.bytesReceived() >= total ? totalParts(total) : (int) (file.bytesReceived() / partSize);
    }

    private static void requireUploading(StoredFile file) {
        if (file.status() != StoredFile.Status.UPLOADING) {
            throw ApiException.conflict("upload_not_active", "Upload is " + file.status());
        }
    }

    private static String spool(InputStream body, Path target, long expectedSize) {
        MessageDigest digest = StoredFileService.sha256();
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long count = 0;
            int read;
            while ((read = body.read(buffer)) >= 0) {
                count += read;
                if (count > expectedSize) {
                    break;
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            if (count != expectedSize) {
                throw ApiException.badRequest("invalid_part_size",
                        "Part body had " + (count > expectedSize ? "more than " : "") + count
                                + " bytes, expected " + expectedSize + "; send it again");
            }
        } catch (IOException e) {
            // The connection dropped mid-part: nothing was written to the backend, the client retries.
            throw ApiException.badRequest("part_interrupted", "Part was not received completely; send it again");
        }
        return HEX.formatHex(digest.digest());
    }

    private Measured measure(ObjectStore store, StoredFile file) {
        MessageDigest digest = StoredFileService.sha256();
        long size;
        try (InputStream in = new DigestInputStream(store.open(file.address()), digest)) {
            size = in.transferTo(OutputStream.nullOutputStream());
        } catch (MissingObjectException e) {
            return new Measured(-1, "");
        } catch (IOException e) {
            throw new StorageUnavailableException(file.backend(), "get", e);
        }
        return new Measured(size, HEX.formatHex(digest.digest()));
    }

    private static void drain(InputStream body) {
        try {
            body.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // the part is already stored; nothing to do
        }
    }

    private record Measured(long size, String sha256) {
    }
}
