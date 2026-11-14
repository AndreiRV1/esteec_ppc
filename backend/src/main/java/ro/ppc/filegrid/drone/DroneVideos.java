package ro.ppc.filegrid.drone;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.storage.StoredFile;
import ro.ppc.filegrid.storage.StoredFileInfo;

/** API shapes for drone inspection videos. */
public final class DroneVideos {

    private DroneVideos() {
    }

    /** Starts a resumable upload: the video's metadata plus the size and SHA-256 of the file to come. */
    public record UploadRequest(
            @NotNull Region region,
            @NotBlank String county,
            @NotBlank @Size(max = 100) String lineCode,
            @Size(max = 100) String droneId,
            @NotNull Instant flightStartedAt,
            @Min(1) Integer durationSeconds,
            @Size(max = 4000) String notes,
            @NotBlank @Size(max = 255) String filename,
            @NotBlank @Size(max = 100) String contentType,
            @Min(1) long sizeBytes,
            @NotNull @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256) {
    }

    public record UploadStarted(UUID uploadId, long partSizeBytes) {
    }

    public record UploadStatus(StoredFile.Status status, long bytesReceived, List<Integer> receivedParts,
                               long sizeBytes, long partSizeBytes) {
    }

    public record Created(UUID id, UUID fileId, String sha256) {
    }

    public record View(UUID id, Region region, String county, String lineCode, String droneId,
                       Instant flightStartedAt, Integer durationSeconds, String notes, String uploadedBy,
                       Instant createdAt, StoredFileInfo file) {
    }
}
