package ro.ppc.filegrid.drone;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ro.ppc.filegrid.audit.AuditLog;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.common.County;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.common.Users;
import ro.ppc.filegrid.common.UuidV7;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.Page;
import ro.ppc.filegrid.drone.DroneVideos.Created;
import ro.ppc.filegrid.drone.DroneVideos.UploadRequest;
import ro.ppc.filegrid.drone.DroneVideos.UploadStarted;
import ro.ppc.filegrid.drone.DroneVideos.UploadStatus;
import ro.ppc.filegrid.drone.DroneVideos.View;
import ro.ppc.filegrid.storage.Downloads;
import ro.ppc.filegrid.storage.FileCategory;
import ro.ppc.filegrid.storage.ResumableUploadService;
import ro.ppc.filegrid.storage.ResumableUploadService.Progress;
import ro.ppc.filegrid.storage.StoredFile;
import ro.ppc.filegrid.storage.StoredFileService;
import ro.ppc.filegrid.storage.VerifiedContent;

/**
 * Drone inspection videos (100 MB to over 1 GB), uploaded by staff in ordered parts
 * so a transfer over a slow or unstable link can stop and continue.
 * Flow: start an upload, PUT the parts 1..n, complete. Realm roles {@code employee}, {@code admin}.
 */
@RestController
@RequestMapping("/api/v1/drone-videos")
@Tag(name = "Drone videos", description = "Inspection videos: resumable upload, search and download")
public class DroneVideoController {

    private final DroneVideoRepository repository;
    private final ResumableUploadService uploads;
    private final StoredFileService storedFiles;
    private final AuditLog audit;

    public DroneVideoController(DroneVideoRepository repository, ResumableUploadService uploads,
                                StoredFileService storedFiles, AuditLog audit) {
        this.repository = repository;
        this.uploads = uploads;
        this.storedFiles = storedFiles;
        this.audit = audit;
    }

    @Operation(summary = "Start a resumable upload",
            description = "Send the metadata plus the size and SHA-256 of the whole file. Then PUT the parts in "
                    + "order; every part except the last is exactly `partSizeBytes` long.")
    @PostMapping("/uploads")
    public ResponseEntity<UploadStarted> start(@Valid @RequestBody UploadRequest request,
                                               @AuthenticationPrincipal Jwt user) {
        County county = County.parse(request.county(), request.region());
        UUID id = UuidV7.generate();
        StoredFileService.NewFile spec = new StoredFileService.NewFile(FileCategory.DRONE_VIDEO, request.region(),
                request.filename(), request.contentType(), user.getSubject(), false);
        StoredFile file = uploads.begin(spec, request.sizeBytes(), request.sha256().toLowerCase(Locale.ROOT),
                created -> repository.insert(id, request, county.displayName(), created.id(), Users.username(user)));
        return ResponseEntity.created(URI.create("/api/v1/drone-videos/uploads/" + file.id()))
                .body(new UploadStarted(file.id(), uploads.partSizeBytes()));
    }

    @Operation(summary = "Upload one part (raw bytes)",
            description = "Parts are numbered from 1 and must arrive in order. A part the server already has is "
                    + "accepted again without effect (204); a part ahead of the expected one is refused (409). "
                    + "Optional header `X-Checksum-SHA256` (hex) is checked against the part.")
    @PutMapping("/uploads/{uploadId}/parts/{partNumber}")
    public ResponseEntity<Void> part(@PathVariable UUID uploadId, @PathVariable int partNumber,
                                     @RequestHeader(value = "X-Checksum-SHA256", required = false) String sha256,
                                     HttpServletRequest request, @AuthenticationPrincipal Jwt user)
            throws IOException {
        own(uploadId, user);
        long length = request.getContentLengthLong();
        if (length < 0) {
            throw new ApiException(HttpStatus.LENGTH_REQUIRED, "length_required", "Content-Length is required");
        }
        uploads.putPart(uploadId, partNumber, request.getInputStream(), length, sha256);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Upload progress: which parts the server has")
    @GetMapping("/uploads/{uploadId}")
    public UploadStatus status(@PathVariable UUID uploadId, @AuthenticationPrincipal Jwt user) {
        Progress p = uploads.progress(own(uploadId, user));
        return new UploadStatus(p.status(), p.bytesReceived(), p.receivedParts(), p.sizeBytes(), p.partSizeBytes());
    }

    @Operation(summary = "Complete the upload",
            description = "The stored file is read back and compared with the declared SHA-256 before the video "
                    + "becomes visible. Safe to call again.")
    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<Created> complete(@PathVariable UUID uploadId, @AuthenticationPrincipal Jwt user) {
        own(uploadId, user);
        StoredFile file = uploads.complete(uploadId, stored -> {
            View video = repository.findByFile(stored.id()).orElseThrow();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fileId", stored.id());
            details.put("backend", stored.backend());
            details.put("sizeBytes", stored.sizeBytes());
            audit.record(video.region(), user.getSubject(), "DRONE_VIDEO_UPLOADED", "drone_video", video.id(), details);
        });
        View video = repository.findByFile(file.id()).orElseThrow(() -> ApiException.notFound("Drone video"));
        return ResponseEntity.created(URI.create("/api/v1/drone-videos/" + video.id()))
                .body(new Created(video.id(), file.id(), file.sha256()));
    }

    @Operation(summary = "Search videos (newest flights first). Filters are optional.")
    @GetMapping
    public Page<View> search(@RequestParam(required = false) Region region,
                             @RequestParam(required = false) String lineCode,
                             @RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to,
                             @RequestParam(defaultValue = "0") @Min(0) int page,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new Page<>(repository.search(region, lineCode, from, to, size, (long) page * size), page, size,
                repository.count(region, lineCode, from, to));
    }

    @Operation(summary = "Get one video's metadata")
    @GetMapping("/{id}")
    public View get(@PathVariable UUID id) {
        return require(id);
    }

    @Operation(summary = "Download the video (integrity-checked first; supports a single Range)")
    @GetMapping("/{id}/file")
    public ResponseEntity<StreamingResponseBody> file(@PathVariable UUID id,
                                                      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                                                      @AuthenticationPrincipal Jwt user) {
        View video = require(id);
        VerifiedContent content = storedFiles.retrieveVerified(video.file().fileId());
        if (range == null) {
            audit.record(video.region(), user.getSubject(), "DRONE_VIDEO_DOWNLOADED", "drone_video", id, Map.of());
        }
        return Downloads.stream(content.path(), content.file().contentType(), content.file().originalFilename(),
                content.file().sha256(), content, range);
    }

    /** An upload can only be continued by whoever started it. */
    private StoredFile own(UUID uploadId, Jwt user) {
        StoredFile file = uploads.require(uploadId);
        if (file.category() != FileCategory.DRONE_VIDEO || !file.uploadedBy().equals(user.getSubject())) {
            throw ApiException.notFound("Upload");
        }
        return file;
    }

    private View require(UUID id) {
        return repository.find(id).orElseThrow(() -> ApiException.notFound("Drone video"));
    }
}
