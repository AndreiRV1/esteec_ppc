package ro.ppc.filegrid.connection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.AdminRequestDetail;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.Page;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.RequestSummary;
import ro.ppc.filegrid.connection.ConnectionTypes.Decision;
import ro.ppc.filegrid.connection.ConnectionTypes.Status;
import ro.ppc.filegrid.storage.Downloads;
import ro.ppc.filegrid.storage.VerifiedContent;

/** Review queue for admins (realm role {@code admin}). */
@RestController
@RequestMapping("/api/v1/admin/connection-requests")
@Tag(name = "Connection requests (admin)", description = "Review, download and accept/reject racordare requests")
public class AdminConnectionRequestController {

    private final ConnectionRequestService service;

    public AdminConnectionRequestController(ConnectionRequestService service) {
        this.service = service;
    }

    public record DecisionRequest(@NotNull Decision decision, @Size(max = 1000) String reason) {
    }

    @Operation(summary = "List requests (oldest first). Filters are optional.")
    @GetMapping
    public Page<RequestSummary> list(@RequestParam(required = false) Status status,
                                     @RequestParam(required = false) Region region,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(status, region, page, size);
    }

    @Operation(summary = "Full request incl. applicant data decrypted from the archive (audited)")
    @GetMapping("/{clientId}")
    public AdminRequestDetail detail(@PathVariable UUID clientId, @AuthenticationPrincipal Jwt admin) {
        return service.detail(clientId, admin.getSubject());
    }

    @Operation(summary = "Download the decrypted, integrity-checked zip (audited)")
    @GetMapping("/{clientId}/archive")
    public ResponseEntity<StreamingResponseBody> archive(@PathVariable UUID clientId,
                                                         @AuthenticationPrincipal Jwt admin) {
        VerifiedContent content = service.openArchive(clientId, admin.getSubject());
        return Downloads.stream(content.path(), "application/zip", content.file().originalFilename(),
                content.file().sha256(), content);
    }

    @Operation(summary = "Download one document from the archive (audited)")
    @GetMapping("/{clientId}/documents/{documentId}")
    public ResponseEntity<StreamingResponseBody> document(@PathVariable UUID clientId,
                                                          @PathVariable UUID documentId,
                                                          @AuthenticationPrincipal Jwt admin) {
        ConnectionRequestService.DocumentContent content =
                service.openDocument(clientId, documentId, admin.getSubject());
        return Downloads.stream(content.path(), content.document().contentType(), content.document().entryName(),
                content.document().sha256(), content);
    }

    @Operation(summary = "Accept or reject a PENDING request. Rejecting requires a reason.")
    @PostMapping("/{clientId}/decision")
    public RequestSummary decide(@PathVariable UUID clientId, @Valid @RequestBody DecisionRequest body,
                                 @AuthenticationPrincipal Jwt admin) {
        return service.decide(clientId, body.decision(), body.reason(), admin.getSubject());
    }
}
