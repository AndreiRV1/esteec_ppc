package ro.ppc.filegrid.connection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.connection.ConnectionTypes.ApplicantType;
import ro.ppc.filegrid.connection.ConnectionTypes.DocumentType;
import ro.ppc.filegrid.connection.ConnectionTypes.RequestType;
import ro.ppc.filegrid.connection.ConnectionTypes.Status;
import ro.ppc.filegrid.storage.StorageBackendType;

/** Rows, archive manifest and API views of the connection-request domain. */
public final class ConnectionRequestRecords {

    private ConnectionRequestRecords() {
    }

    /** A row of {@code client}. */
    public record ClientRow(
            UUID clientId,
            Region region,
            ApplicantType applicantType,
            RequestType requestType,
            String county,
            String locality,
            BigDecimal requestedPowerKw,
            String identityHmac,
            UUID archiveFileId,
            Status status,
            String statusReason,
            String reviewedBy,
            Instant reviewedAt,
            String submittedBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** A row of {@code client_document}. */
    public record DocumentRow(
            UUID id,
            DocumentType documentType,
            String entryName,
            String contentType,
            long sizeBytes,
            String sha256,
            String issuer,
            LocalDate issuedOn,
            LocalDate validUntil) {
    }

    /** {@code manifest.json} inside the encrypted archive: the full request, including personal data. */
    public record ArchiveManifest(
            int schemaVersion,
            UUID clientId,
            Instant submittedAt,
            String submittedBy,
            ConnectionRequestForm request,
            List<DocumentRow> documents) {

        public static final String ENTRY_NAME = "manifest.json";
        public static final int CURRENT_VERSION = 1;
    }

    /** What the applicant (and the admin list) sees. No personal data. */
    public record RequestSummary(
            UUID clientId,
            Region region,
            ApplicantType applicantType,
            RequestType requestType,
            String county,
            String locality,
            BigDecimal requestedPowerKw,
            Status status,
            String statusReason,
            Instant createdAt,
            Instant reviewedAt) {

        static RequestSummary of(ClientRow row) {
            return new RequestSummary(row.clientId(), row.region(), row.applicantType(), row.requestType(),
                    row.county(), row.locality(), row.requestedPowerKw(), row.status(), row.statusReason(),
                    row.createdAt(), row.reviewedAt());
        }
    }

    /** Full review view for an admin, with the personal data decrypted from the archive. */
    public record AdminRequestDetail(
            RequestSummary request,
            String submittedBy,
            String reviewedBy,
            ConnectionRequestForm.Applicant applicant,
            ConnectionRequestForm.ConsumptionPlace consumptionPlace,
            ConnectionRequestForm.Permit permit,
            List<DocumentRow> documents,
            ArchiveInfo archive,
            /* Other requests from the same CNP/CUI (matched by HMAC). */
            List<RequestSummary> otherRequestsFromApplicant) {
    }

    public record ArchiveInfo(
            UUID fileId,
            StorageBackendType backend,
            long sizeBytes,
            String sha256,
            String encryption,
            Instant storedAt,
            Instant lastVerifiedAt,
            /** Trace of the submission: follow it from the proxy down to the storage write. */
            String traceId) {
    }

    public record Page<T>(List<T> items, int page, int size, long total) {
    }
}
