package ro.ppc.filegrid.connection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.RequestSummary;

/** Applicant-facing endpoints (realm role {@code client}). */
@RestController
@RequestMapping("/api/v1/connection-requests")
@Tag(name = "Connection requests", description = "Racordare: submit and track your own requests")
public class ConnectionRequestController {

    private final ConnectionRequestService service;

    public ConnectionRequestController(ConnectionRequestService service) {
        this.service = service;
    }

    @Operation(summary = "Submit a connection request",
            description = "multipart/form-data with a JSON part `request` and three files (PDF/JPEG/PNG, max 10 MB each): "
                    + "`cerereRacordare`, `actIdentitate`, `aviz`. The documents are zipped, encrypted and stored; "
                    + "the request starts as PENDING until an admin reviews it.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RequestSummary> submit(
            @Valid @RequestPart("request") ConnectionRequestForm request,
            @RequestPart("cerereRacordare") MultipartFile cerereRacordare,
            @RequestPart("actIdentitate") MultipartFile actIdentitate,
            @RequestPart("aviz") MultipartFile aviz,
            @AuthenticationPrincipal Jwt user) {
        RequestSummary created = service.submit(request,
                new ConnectionRequestService.Uploads(cerereRacordare, actIdentitate, aviz), user.getSubject());
        return ResponseEntity.created(URI.create("/api/v1/connection-requests/" + created.clientId()))
                .body(created);
    }

    @Operation(summary = "List my connection requests")
    @GetMapping
    public List<RequestSummary> mine(@AuthenticationPrincipal Jwt user) {
        return service.mine(user.getSubject());
    }

    @Operation(summary = "Get one of my connection requests")
    @GetMapping("/{clientId}")
    public RequestSummary get(@PathVariable UUID clientId, @AuthenticationPrincipal Jwt user) {
        return service.getOwn(clientId, user.getSubject());
    }
}
