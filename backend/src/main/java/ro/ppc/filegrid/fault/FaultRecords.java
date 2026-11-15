package ro.ppc.filegrid.fault;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.storage.StoredFileInfo;

/** API shapes for fault records ("avarii"). */
public final class FaultRecords {

    private FaultRecords() {
    }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    /** The JSON part {@code metadata} of an upload. {@code county} is a code ("TM") or a name ("Timiș"). */
    public record Metadata(
            @NotNull Region region,
            @NotBlank String county,
            @Size(max = 200) String locality,
            @NotBlank @Size(max = 100) String substationCode,
            @NotNull Instant occurredAt,
            Instant restoredAt,
            @NotNull Severity severity,
            @Size(max = 500) String cause,
            @Min(0) Integer affectedCustomers,
            @Size(max = 4000) String description) {
    }

    /** @param fallbackFrom the storage the policy asked for, when it was down and the file was written to {@code backend} */
    public record Created(UUID id, UUID fileId, String sha256, String backend, String fallbackFrom) {
    }

    public record View(UUID id, Region region, String county, String locality, String substationCode,
                       Instant occurredAt, Instant restoredAt, Severity severity, String cause,
                       Integer affectedCustomers, String description, String reportedBy, Instant createdAt,
                       StoredFileInfo file) {
    }

    /** What a customer sees about a fault in their area. */
    public record Summary(UUID id, String county, String locality, String substationCode, Instant occurredAt,
                          Instant restoredAt, Severity severity, String cause, Integer affectedCustomers) {
    }

    public record Restore(Instant restoredAt) {
    }
}
