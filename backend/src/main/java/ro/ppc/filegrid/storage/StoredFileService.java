package ro.ppc.filegrid.storage;

import com.google.crypto.tink.StreamingAead;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.common.TraceIds;
import ro.ppc.filegrid.common.UuidV7;
import ro.ppc.filegrid.crypto.EnvelopeEncryption;

/**
 * Writes and reads files according to {@code storage_policy}, with integrity
 * checks on both paths. Category-specific services supply the content and the
 * metadata rows; this class owns encryption, hashing, the backend call and the
 * {@code stored_file} life cycle (UPLOADING -> STORED | FAILED, STORED -> CORRUPTED).
 */
@Service
public class StoredFileService {

    private static final Logger log = LoggerFactory.getLogger(StoredFileService.class);
    private static final HexFormat HEX = HexFormat.of();

    private final StoragePolicyRepository policies;
    private final StoredFileRepository files;
    private final Map<StorageBackendType, ObjectStore> stores;
    private final EnvelopeEncryption encryption;
    private final TempFiles tempFiles;
    private final TransactionTemplate tx;
    private final TraceIds traceIds;
    private final MeterRegistry meters;

    public StoredFileService(StoragePolicyRepository policies, StoredFileRepository files, List<ObjectStore> stores,
                             EnvelopeEncryption encryption, TempFiles tempFiles, TransactionTemplate tx,
                             TraceIds traceIds, MeterRegistry meters) {
        this.policies = policies;
        this.files = files;
        this.stores = stores.stream().collect(Collectors.toMap(ObjectStore::type, Function.identity()));
        this.encryption = encryption;
        this.tempFiles = tempFiles;
        this.tx = tx;
        this.traceIds = traceIds;
        this.meters = meters;
    }

    /** Produces the plaintext content. Must not close the stream it is given. */
    @FunctionalInterface
    public interface ContentWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    public record NewFile(FileCategory category, Region region, String originalFilename, String contentType,
                          String uploadedBy, boolean requireEncryption) {
    }

    /**
     * Stores a new file and runs {@code inSameTransaction} in the transaction that
     * marks it STORED, so category metadata and the file become visible together.
     * If anything fails, the object is removed and the record marked FAILED.
     */
    public StoredFile store(NewFile spec, ContentWriter writer, Consumer<StoredFile> inSameTransaction) {
        StoragePolicy policy = policies.find(spec.category(), spec.region())
                .orElseThrow(() -> new IllegalStateException(
                        "No storage policy for " + spec.category() + "/" + spec.region()));
        if (spec.requireEncryption() && !policy.encryptAtRest()) {
            throw new IllegalStateException("Policy for " + spec.category() + " must encrypt at rest");
        }
        ObjectStore store = storeFor(policy.backend());
        UUID id = UuidV7.generate();
        ObjectStore.Address address = new ObjectStore.Address(id, spec.region(),
                store.locationFor(policy, id, Instant.now()));
        EnvelopeEncryption.DataKey key = policy.encryptAtRest() ? encryption.newDataKey() : null;

        Path spool = tempFiles.create("store-");
        boolean recorded = false;
        boolean uploaded = false;
        try {
            Written written = spool(spool, writer, key, id, policy.maxSizeBytes());
            Map<String, String> fileTags = Map.of("filegrid.file_id", id.toString(),
                    "filegrid.category", spec.category().name());
            traceIds.inSpan("db record file", fileTags, () -> {
                files.insertUploading(id, spec.region(), spec.category(), policy.backend(), address.location(),
                        spec.originalFilename(), spec.contentType(), key, spec.uploadedBy(), traceIds.current());
                return null;
            });
            recorded = true;
            timedRun(spec.category(), policy.backend(), "put", () -> {
                // The storage call itself: which file, where, how big.
                traceIds.tag("filegrid.file_id", id.toString());
                traceIds.tag("filegrid.location", address.location() == null ? "postgresql:stored_file_blob" : address.location());
                traceIds.tag("filegrid.bytes", Long.toString(written.storedSize()));
                store.put(address, spool, written.storedSize());
            });
            uploaded = true;
            traceIds.inSpan("db confirm file", fileTags, () -> {
                tx.executeWithoutResult(status -> {
                    files.markStored(id, written.plainSize(), written.plainSha256(), written.storedSha256());
                    inSameTransaction.accept(files.find(id).orElseThrow());
                });
                return null;
            });
            return files.find(id).orElseThrow();
        } catch (RuntimeException e) {
            if (uploaded) {
                bestEffort(() -> store.delete(address), "delete orphaned object " + id);
            }
            if (recorded) {
                bestEffort(() -> files.markStatus(id, StoredFile.Status.FAILED), "mark " + id + " FAILED");
            }
            throw e;
        } finally {
            TempFiles.deleteQuietly(spool);
        }
    }

    /**
     * Fetches a file, checks the stored checksum, decrypts it and checks the
     * original checksum. Any mismatch marks the file CORRUPTED and fails the
     * request; the caller only ever receives verified bytes.
     */
    public VerifiedContent retrieveVerified(UUID fileId) {
        StoredFile file = files.find(fileId).orElseThrow(() -> ApiException.notFound("File"));
        if (file.status() == StoredFile.Status.CORRUPTED) {
            throw integrityFailure(file, "previously detected corruption");
        }
        if (file.status() != StoredFile.Status.STORED) {
            throw ApiException.conflict("file_not_available", "File is " + file.status());
        }
        ObjectStore store = storeFor(file.backend());
        Path fetched = tempFiles.create("fetch-");
        Path plain = tempFiles.create("plain-");
        try {
            String storedSha = timed(file.category(), file.backend(), "get", () -> fetch(store, file, fetched));
            if (!storedSha.equals(file.storedSha256())) {
                throw corrupted(file, "stored checksum mismatch");
            }
            String plainSha;
            try {
                plainSha = decrypt(file, fetched, plain);
            } catch (IOException | GeneralSecurityException e) {
                throw corrupted(file, "decryption failed: " + e.getMessage());
            }
            if (!plainSha.equals(file.sha256())) {
                throw corrupted(file, "content checksum mismatch");
            }
            files.markVerified(fileId);
            return new VerifiedContent(file, plain);
        } catch (RuntimeException e) {
            TempFiles.deleteQuietly(plain);
            throw e;
        } finally {
            TempFiles.deleteQuietly(fetched);
        }
    }

    private String fetch(ObjectStore store, StoredFile file, Path target) {
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(store.open(file.address()), digest)) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (MissingObjectException e) {
            throw corrupted(file, "object missing from " + file.backend());
        } catch (IOException e) {
            throw new StorageUnavailableException(file.backend(), "get", e);
        }
        return HEX.formatHex(digest.digest());
    }

    private String decrypt(StoredFile file, Path source, Path target) throws IOException, GeneralSecurityException {
        MessageDigest digest = sha256();
        InputStream raw = Files.newInputStream(source);
        InputStream in = raw;
        if (file.encrypted()) {
            StreamingAead aead = encryption.unwrap(file.encryptionKeyId(), file.wrappedDek());
            in = aead.newDecryptingStream(raw, EnvelopeEncryption.associatedData(file.id()));
        }
        try (InputStream content = new DigestInputStream(in, digest)) {
            Files.copy(content, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return HEX.formatHex(digest.digest());
    }

    private Written spool(Path target, ContentWriter writer, EnvelopeEncryption.DataKey key, UUID id, long maxBytes) {
        MessageDigest plainDigest = sha256();
        MessageDigest storedDigest = sha256();
        LimitedOutputStream limited;
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(target), 64 * 1024)) {
            OutputStream stored = new DigestOutputStream(file, storedDigest);
            OutputStream encrypted = key == null
                    ? stored
                    : key.aead().newEncryptingStream(stored, EnvelopeEncryption.associatedData(id));
            limited = new LimitedOutputStream(new DigestOutputStream(encrypted, plainDigest), maxBytes);
            writer.writeTo(new CloseShield(limited));
            limited.close(); // flushes the final encrypted segment
        } catch (IOException e) {
            throw new UncheckedIOException("Could not spool upload", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not start encryption", e);
        }
        try {
            return new Written(limited.count(), HEX.formatHex(plainDigest.digest()),
                    Files.size(target), HEX.formatHex(storedDigest.digest()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ApiException corrupted(StoredFile file, String reason) {
        log.error("Integrity check failed for file {} ({} on {}): {}",
                file.id(), file.category(), file.backend(), reason);
        bestEffort(() -> files.markStatus(file.id(), StoredFile.Status.CORRUPTED), "mark CORRUPTED");
        Counter.builder("filegrid.storage.integrity.failures")
                .tag("category", file.category().name())
                .tag("backend", file.backend().name())
                .register(meters)
                .increment();
        return integrityFailure(file, reason);
    }

    private static ApiException integrityFailure(StoredFile file, String reason) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "integrity_check_failed",
                "File " + file.id() + " failed its integrity check (" + reason + ") and was not returned");
    }

    ObjectStore storeFor(StorageBackendType type) {
        ObjectStore store = stores.get(type);
        if (store == null) {
            throw new IllegalStateException("No object store for backend " + type);
        }
        return store;
    }

    /** Times one backend call (per category and backend) and wraps it in a trace span. */
    <T> T timed(FileCategory category, StorageBackendType backend, String operation,
                java.util.function.Supplier<T> action) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            return traceIds.inSpan("storage " + operation,
                    Map.of("filegrid.category", category.name(), "filegrid.backend", backend.name()), action);
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("filegrid.storage.operation")
                    .description("Storage backend calls, per category and backend")
                    .tag("category", category.name())
                    .tag("backend", backend.name())
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meters));
        }
    }

    void timedRun(FileCategory category, StorageBackendType backend, String operation, Runnable action) {
        timed(category, backend, operation, () -> {
            action.run();
            return null;
        });
    }

    static void bestEffort(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Cleanup step failed ({}): {}", what, e.toString());
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Written(long plainSize, String plainSha256, long storedSize, String storedSha256) {
    }

    /** Lets content writers call close() (e.g. ZipOutputStream) without ending the encryption stream. */
    private static final class CloseShield extends FilterOutputStream {
        CloseShield(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    /** Enforces the policy's max size while streaming, before anything reaches a backend. */
    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long max;
        private long count;

        LimitedOutputStream(OutputStream out, long max) {
            super(out);
            this.max = max;
        }

        @Override
        public void write(int b) throws IOException {
            check(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check(len);
            out.write(b, off, len);
        }

        private void check(long more) {
            count += more;
            if (count > max) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                        "File exceeds the " + max + " byte limit of its storage policy");
            }
        }

        long count() {
            return count;
        }
    }

    /** Trace id of the request that stored the file, if it was traced. */
    public java.util.Optional<String> traceId(java.util.UUID fileId) {
        return files.traceId(fileId);
    }
}
