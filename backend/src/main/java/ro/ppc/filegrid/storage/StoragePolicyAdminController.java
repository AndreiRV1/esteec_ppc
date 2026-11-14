package ro.ppc.filegrid.storage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.ppc.filegrid.audit.AuditLog;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.common.Users;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Lets an admin choose, per category and region, which storage receives new files.
 * The policy is read on every upload, so a change applies to the next file;
 * files already stored stay where they are and remain readable.
 */
@RestController
@RequestMapping("/api/v1/admin/storage-policies")
@Tag(name = "Storage policies (admin)", description = "Where each data category is written")
public class StoragePolicyAdminController {

    static final long MAX_DATABASE_BYTES = 104_857_600; // storage_policy_db_size_limit
    private static final Pattern BUCKET = Pattern.compile("^filegrid-[a-z0-9][a-z0-9-]{1,50}$");
    private static final Pattern FTP_DIRECTORY = Pattern.compile("^/files(/[A-Za-z0-9][A-Za-z0-9._-]*)+$");

    private final StoragePolicyRepository policies;
    private final S3Client s3;
    private final AuditLog audit;

    public StoragePolicyAdminController(StoragePolicyRepository policies, S3Client s3, AuditLog audit) {
        this.policies = policies;
        this.s3 = s3;
        this.audit = audit;
    }

    public record PolicyView(FileCategory category, Region region, StorageBackendType backend, String target,
                             boolean encryptAtRest, long maxSizeBytes, Instant updatedAt, String updatedBy) {
    }

    /** @param target S3 bucket (filegrid-...) or FTP directory (/files/...); omitted for DATABASE */
    public record PolicyUpdate(@NotNull StorageBackendType backend, String target, @NotNull Boolean encryptAtRest,
                               @Min(1) long maxSizeBytes) {
    }

    @Operation(summary = "All storage policies")
    @GetMapping
    public List<PolicyView> list() {
        return policies.list();
    }

    @Operation(summary = "Change where a category is written in a region (audited, applies to the next upload)")
    @PutMapping("/{category}/{region}")
    @Transactional
    public PolicyView update(@PathVariable FileCategory category, @PathVariable Region region,
                             @Valid @RequestBody PolicyUpdate body, @AuthenticationPrincipal Jwt admin) {
        StoragePolicy before = policies.find(category, region)
                .orElseThrow(() -> ApiException.notFound("Storage policy"));
        String target = body.target() == null || body.target().isBlank() ? null : body.target().strip();
        validate(category, body, target);

        policies.update(category, region, body.backend(), target, body.encryptAtRest(), body.maxSizeBytes(),
                Users.username(admin));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("category", category);
        details.put("from", describe(before.backend(), before.target(), before.encryptAtRest(), before.maxSizeBytes()));
        details.put("to", describe(body.backend(), target, body.encryptAtRest(), body.maxSizeBytes()));
        audit.record(region, admin.getSubject(), "STORAGE_POLICY_CHANGED", "storage_policy",
                category + "/" + region, details);
        return policies.list().stream()
                .filter(p -> p.category() == category && p.region() == region).findFirst().orElseThrow();
    }

    /** Mirrors the CHECK constraints of {@code storage_policy}, with messages a person can act on. */
    private void validate(FileCategory category, PolicyUpdate body, String target) {
        if (category == FileCategory.CONNECTION_DOCUMENTS && !body.encryptAtRest()) {
            throw invalid("Connection documents contain personal data and must be encrypted at rest");
        }
        if (category == FileCategory.DRONE_VIDEO && body.encryptAtRest()) {
            throw invalid("Drone videos are uploaded in resumable parts, which cannot be encrypted at rest");
        }
        switch (body.backend()) {
            case DATABASE -> {
                if (target != null) {
                    throw invalid("DATABASE takes no target");
                }
                if (body.maxSizeBytes() > MAX_DATABASE_BYTES) {
                    throw invalid("Files stored in the database are limited to " + MAX_DATABASE_BYTES + " bytes");
                }
            }
            case S3 -> {
                if (target == null || !BUCKET.matcher(target).matches()) {
                    throw invalid("S3 target must be a bucket named filegrid-...");
                }
                try {
                    s3.headBucket(b -> b.bucket(target));
                } catch (NoSuchBucketException e) {
                    throw invalid("Bucket " + target + " does not exist");
                } catch (SdkException e) {
                    throw new StorageUnavailableException(StorageBackendType.S3, "head bucket", e);
                }
            }
            case FTP -> {
                if (target == null || !FTP_DIRECTORY.matcher(target).matches()) {
                    throw invalid("FTP target must be a directory under /files, e.g. /files/fault-records");
                }
            }
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("invalid_storage_policy", message);
    }

    private static Map<String, Object> describe(StorageBackendType backend, String target, boolean encrypt, long max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("backend", backend);
        m.put("target", target);
        m.put("encryptAtRest", encrypt);
        m.put("maxSizeBytes", max);
        return m;
    }
}
