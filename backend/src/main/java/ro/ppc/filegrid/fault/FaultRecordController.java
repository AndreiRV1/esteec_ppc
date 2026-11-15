package ro.ppc.filegrid.fault;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ro.ppc.filegrid.audit.AuditLog;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.common.County;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.common.Users;
import ro.ppc.filegrid.common.UuidV7;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.Page;
import ro.ppc.filegrid.fault.FaultRecords.Created;
import ro.ppc.filegrid.fault.FaultRecords.Metadata;
import ro.ppc.filegrid.fault.FaultRecords.Restore;
import ro.ppc.filegrid.fault.FaultRecords.Severity;
import ro.ppc.filegrid.fault.FaultRecords.View;
import ro.ppc.filegrid.storage.Downloads;
import ro.ppc.filegrid.storage.FileCategory;
import ro.ppc.filegrid.storage.StoredFile;
import ro.ppc.filegrid.storage.StoredFileService;
import ro.ppc.filegrid.storage.VerifiedContent;

/**
 * Fault records ("avarii") from transformer substations, uploaded by staff
 * (realm roles {@code employee}, {@code admin}). The file goes where the storage
 * policy says and is available for download as soon as the upload returns.
 */
@RestController
@RequestMapping("/api/v1/fault-records")
@Tag(name = "Fault records", description = "Avarii: upload, search and download")
public class FaultRecordController {

    private final FaultRecordRepository repository;
    private final StoredFileService storedFiles;
    private final AuditLog audit;

    public FaultRecordController(FaultRecordRepository repository, StoredFileService storedFiles, AuditLog audit) {
        this.repository = repository;
        this.storedFiles = storedFiles;
        this.audit = audit;
    }

    @Operation(summary = "Upload a fault record",
            description = "multipart/form-data with a JSON part `metadata` and the recording in part `file`.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Created> upload(@Valid @RequestPart("metadata") Metadata metadata,
                                          @RequestPart("file") MultipartFile file,
                                          @AuthenticationPrincipal Jwt user) {
        County county = County.parse(metadata.county(), metadata.region());
        if (metadata.restoredAt() != null && metadata.restoredAt().isBefore(metadata.occurredAt())) {
            throw ApiException.badRequest("invalid_restored_at", "Already restored, or restoredAt is before occurredAt or in the future");
        }
        if (file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "The file is empty");
        }
        UUID id = UuidV7.generate();
        String filename = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "fault-" + id : file.getOriginalFilename();
        String contentType = file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : file.getContentType();
        StoredFileService.NewFile spec = new StoredFileService.NewFile(FileCategory.FAULT_RECORD,
                metadata.region(), filename, contentType, user.getSubject(), false);

        StoredFile stored = storedFiles.store(spec, out -> {
            try (InputStream in = file.getInputStream()) {
                in.transferTo(out);
            }
        }, saved -> {
            repository.insert(id, metadata, county.displayName(), saved.id(), Users.username(user));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fileId", saved.id());
            details.put("backend", saved.backend());
            details.put("sizeBytes", saved.sizeBytes());
            details.put("severity", metadata.severity());
            audit.record(metadata.region(), user.getSubject(), "FAULT_RECORD_UPLOADED", "fault_record", id, details);
        });
        return ResponseEntity.created(URI.create("/api/v1/fault-records/" + id))
                .body(new Created(id, stored.id(), stored.sha256(), stored.backend().name(),
                        stored.fallbackFrom() == null ? null : stored.fallbackFrom().name()));
    }

    @Operation(summary = "Search fault records (newest first). Filters are optional.")
    @GetMapping
    public Page<View> search(@RequestParam(required = false) Region region,
                             @RequestParam(required = false) String county,
                             @RequestParam(required = false) Severity severity,
                             @RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to,
                             @RequestParam(defaultValue = "0") @Min(0) int page,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        String countyName = county == null || county.isBlank() ? null : County.parse(county).displayName();
        return new Page<>(repository.search(region, countyName, severity, from, to, size, (long) page * size),
                page, size, repository.count(region, countyName, severity, from, to));
    }

    @Operation(summary = "Get one fault record")
    @GetMapping("/{id}")
    public View get(@PathVariable UUID id) {
        return require(id);
    }

    @Operation(summary = "Download the recording (integrity-checked before the first byte is sent)")
    @GetMapping("/{id}/file")
    public ResponseEntity<StreamingResponseBody> file(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        View fault = require(id);
        VerifiedContent content = storedFiles.retrieveVerified(fault.file().fileId());
        audit.record(fault.region(), user.getSubject(), "FAULT_RECORD_DOWNLOADED", "fault_record", id, Map.of());
        return Downloads.stream(content.path(), content.file().contentType(), content.file().originalFilename(),
                content.file().sha256(), content);
    }

    @Operation(summary = "Mark the supply as restored (defaults to now)")
    @PostMapping("/{id}/restore")
    public View restore(@PathVariable UUID id, @RequestBody(required = false) Restore body,
                        @AuthenticationPrincipal Jwt user) {
        View fault = require(id);
        Instant at = body == null || body.restoredAt() == null ? Instant.now() : body.restoredAt();
        if (!repository.markRestored(id, at)) {
            throw ApiException.badRequest("invalid_restored_at", "Already restored, or restoredAt is before occurredAt or in the future");
        }
        audit.record(fault.region(), user.getSubject(), "FAULT_RECORD_RESTORED", "fault_record", id,
                Map.of("restoredAt", at.toString()));
        return require(id);
    }

    private View require(UUID id) {
        return repository.find(id).orElseThrow(() -> ApiException.notFound("Fault record"));
    }
}
