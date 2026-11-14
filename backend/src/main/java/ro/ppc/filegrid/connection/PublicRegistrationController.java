package ro.ppc.filegrid.connection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.RequestSummary;

/**
 * Registration for people without an account: the racordare request is filed and a
 * login is created for it. The password is returned once, here, and never stored by FileGrid.
 */
@RestController
@RequestMapping("/api/v1/public/connection-requests")
@Tag(name = "Registration", description = "Submit a racordare without an account and receive a login")
public class PublicRegistrationController {

    public record Registration(String username, String password, RequestSummary request) {
    }

    private final ConnectionRequestService service;
    private final KeycloakAccounts accounts;

    public PublicRegistrationController(ConnectionRequestService service, KeycloakAccounts accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    @Operation(summary = "Submit a connection request and create a client login",
            description = "Same parts as POST /connection-requests. Returns the client id (username) and a "
                    + "generated password for logging in to follow the request; shown only once.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Registration> register(
            @Valid @RequestPart("request") ConnectionRequestForm request,
            @RequestPart("cerereRacordare") MultipartFile cerereRacordare,
            @RequestPart("actIdentitate") MultipartFile actIdentitate,
            @RequestPart("aviz") MultipartFile aviz) {
        KeycloakAccounts.Login login = accounts.createApplicant();
        RequestSummary created;
        try {
            created = service.submit(request,
                    new ConnectionRequestService.Uploads(cerereRacordare, actIdentitate, aviz), login.userId());
        } catch (RuntimeException e) {
            accounts.delete(login.userId());
            throw e;
        }
        return ResponseEntity.created(URI.create("/api/v1/connection-requests/" + created.clientId()))
                .header("Cache-Control", "no-store")
                .body(new Registration(login.username(), login.password(), created));
    }
}
