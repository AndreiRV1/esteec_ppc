package ro.ppc.filegrid.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;

/** S3 / MinIO. Location format: {@code <bucket>/<key>}. */
@Component
public class S3ObjectStore implements ObjectStore {

    private final S3Client s3;

    public S3ObjectStore(S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public StorageBackendType type() {
        return StorageBackendType.S3;
    }

    @Override
    public void put(Address address, Path source, long size) {
        Location loc = Location.parse(address.location());
        try {
            // A single PUT is atomic: the object is either fully visible or not at all.
            s3.putObject(b -> b.bucket(loc.bucket()).key(loc.key()).contentLength(size),
                    RequestBody.fromFile(source));
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "put", e);
        }
    }

    @Override
    public InputStream open(Address address) {
        Location loc = Location.parse(address.location());
        try {
            return s3.getObject(b -> b.bucket(loc.bucket()).key(loc.key()));
        } catch (NoSuchKeyException e) {
            throw new MissingObjectException(address, e);
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "get", e);
        }
    }

    @Override
    public void delete(Address address) {
        Location loc = Location.parse(address.location());
        try {
            s3.deleteObject(b -> b.bucket(loc.bucket()).key(loc.key()));
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "delete", e);
        }
    }

    @Override
    public String beginParts(Address address) {
        Location loc = Location.parse(address.location());
        try {
            return s3.createMultipartUpload(b -> b.bucket(loc.bucket()).key(loc.key())).uploadId();
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "put", e);
        }
    }

    @Override
    public void putPart(Address address, String uploadId, int partNumber, long offset, Path source, long size) {
        Location loc = Location.parse(address.location());
        try {
            s3.uploadPart(b -> b.bucket(loc.bucket()).key(loc.key()).uploadId(uploadId).partNumber(partNumber)
                    .contentLength(size), RequestBody.fromFile(source));
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "put", e);
        }
    }

    @Override
    public void completeParts(Address address, String uploadId) {
        Location loc = Location.parse(address.location());
        try {
            List<CompletedPart> parts = s3.listPartsPaginator(
                            b -> b.bucket(loc.bucket()).key(loc.key()).uploadId(uploadId))
                    .parts().stream()
                    .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build())
                    .toList();
            s3.completeMultipartUpload(b -> b.bucket(loc.bucket()).key(loc.key()).uploadId(uploadId)
                    .multipartUpload(m -> m.parts(parts)));
        } catch (NoSuchUploadException e) {
            // Already completed by an earlier attempt: fine as long as the object is there.
            try {
                s3.headObject(b -> b.bucket(loc.bucket()).key(loc.key()));
            } catch (SdkException missing) {
                throw new StorageUnavailableException(type(), "put", e);
            }
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "put", e);
        }
    }

    @Override
    public void abortParts(Address address, String uploadId) {
        Location loc = Location.parse(address.location());
        try {
            s3.abortMultipartUpload(b -> b.bucket(loc.bucket()).key(loc.key()).uploadId(uploadId));
        } catch (SdkException e) {
            throw new StorageUnavailableException(type(), "delete", e);
        }
    }

    private record Location(String bucket, String key) {
        static Location parse(String location) {
            int slash = location.indexOf('/');
            if (slash <= 0 || slash == location.length() - 1) {
                throw new IllegalArgumentException("S3 location must be <bucket>/<key>: " + location);
            }
            return new Location(location.substring(0, slash), location.substring(slash + 1));
        }
    }
}
