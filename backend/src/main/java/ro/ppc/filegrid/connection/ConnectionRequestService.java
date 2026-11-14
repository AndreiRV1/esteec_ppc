package ro.ppc.filegrid.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ro.ppc.filegrid.audit.AuditLog;
import ro.ppc.filegrid.common.ApiException;
import ro.ppc.filegrid.common.Region;
import ro.ppc.filegrid.common.UuidV7;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.AdminRequestDetail;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.ArchiveInfo;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.ArchiveManifest;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.ClientRow;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.DocumentRow;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.Page;
import ro.ppc.filegrid.connection.ConnectionRequestRecords.RequestSummary;
import ro.ppc.filegrid.connection.ConnectionTypes.ApplicantType;
import ro.ppc.filegrid.connection.ConnectionTypes.Decision;
import ro.ppc.filegrid.connection.ConnectionTypes.DocumentType;
import ro.ppc.filegrid.connection.ConnectionTypes.Status;
import ro.ppc.filegrid.storage.FileCategory;
import ro.ppc.filegrid.storage.StoredFile;
import ro.ppc.filegrid.storage.StoredFileService;
import ro.ppc.filegrid.storage.TempFiles;
import ro.ppc.filegrid.storage.VerifiedContent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Grid connection requests ("racordare").
 *
 * <p>Submit: the applicant sends the form plus three documents (cerere de racordare,
 * act de identitate, aviz/certificat). FileGrid validates them, zips them together
 * with a manifest, encrypts the zip with a per-file key, stores it per policy and
 * creates the {@code client} row as PENDING, all or nothing.
 *
 * <p>Review: an admin reads the decrypted request and documents and accepts or
 * rejects it. Every access to personal data and every decision is audited.
 */
@Service
public class ConnectionRequestService {

    static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;
    private static final HexFormat HEX = HexFormat.of();

    private final ConnectionRequestRepository repository;
    private final StoredFileService storedFiles;
    private final IdentityHasher identityHasher;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final TempFiles tempFiles;

    public ConnectionRequestService(ConnectionRequestRepository repository, StoredFileService storedFiles,
                                    IdentityHasher identityHasher, AuditLog audit, ObjectMapper json,
                                    TempFiles tempFiles) {
        this.repository = repository;
        this.storedFiles = storedFiles;
        this.identityHasher = identityHasher;
        this.audit = audit;
        this.json = json;
        this.tempFiles = tempFiles;
    }

    /** The three documents of a request, as uploaded. */
    public record Uploads(MultipartFile cerereRacordare, MultipartFile actIdentitate, MultipartFile aviz) {
    }

    // ------------------------------------------------------------------ applicant

    public RequestSummary submit(ConnectionRequestForm form, Uploads uploads, String applicant) {
        validateIdentity(form);
        List<PendingDocument> documents = validateDocuments(form, uploads);

        UUID clientId = UuidV7.generate();
        Region region = form.consumptionPlace().county().region();
        Instant submittedAt = Instant.now();
        List<DocumentRow> archived = new ArrayList<>();

        StoredFileService.ContentWriter zipWriter = out -> {
            ZipOutputStream zip = new ZipOutputStream(out);
            for (PendingDocument doc : documents) {
                archived.add(addEntry(zip, doc, submittedAt, form.permit()));
            }
            ArchiveManifest manifest = new ArchiveManifest(ArchiveManifest.CURRENT_VERSION, clientId, submittedAt,
                    applicant, form, List.copyOf(archived));
            ZipEntry entry = new ZipEntry(ArchiveManifest.ENTRY_NAME);
            entry.setTime(submittedAt.toEpochMilli());
            zip.putNextEntry(entry);
            zip.write(json.writeValueAsBytes(manifest));
            zip.closeEntry();
            zip.finish();
        };

        StoredFileService.NewFile spec = new StoredFileService.NewFile(FileCategory.CONNECTION_DOCUMENTS, region,
                "racordare-" + clientId + ".zip", "application/zip", applicant, true);

        storedFiles.store(spec, zipWriter, archive -> {
            repository.insert(new ClientRow(clientId, region, form.applicantType(), form.requestType(),
                    form.consumptionPlace().county().displayName(), form.consumptionPlace().locality().strip(),
                    form.requestedPowerKw(),
                    identityHasher.hash(form.applicantType(), form.applicant().identityNumber()),
                    archive.id(), Status.PENDING, null, null, null, applicant, null, null));
            archived.forEach(doc -> repository.insertDocument(clientId, region, doc));
            audit.record(region, applicant, "CONNECTION_REQUEST_SUBMITTED", "client", clientId, Map.of(
                    "archiveFileId", archive.id(),
                    "backend", archive.backend(),
                    "documents", archived.stream().map(d -> d.documentType().name()).toList()));
        });
        return RequestSummary.of(require(clientId));
    }

    public List<RequestSummary> mine(String applicant) {
        return repository.findBySubmitter(applicant).stream().map(RequestSummary::of).toList();
    }

    /** An applicant can only see their own requests; anything else is "not found". */
    public RequestSummary getOwn(UUID clientId, String applicant) {
        ClientRow row = repository.find(clientId)
                .filter(r -> r.submittedBy().equals(applicant))
                .orElseThrow(() -> ApiException.notFound("Connection request"));
        return RequestSummary.of(row);
    }

    // ------------------------------------------------------------------ admin

    public Page<RequestSummary> search(Status status, Region region, int page, int size) {
        List<RequestSummary> items = repository.search(status, region, size, (long) page * size).stream()
                .map(RequestSummary::of)
                .toList();
        return new Page<>(items, page, size, repository.count(status, region));
    }

    /** Decrypts the archive to show the personal data; the access itself is audited. */
    public AdminRequestDetail detail(UUID clientId, String admin) {
        ClientRow row = require(clientId);
        ArchiveManifest manifest;
        StoredFile archive;
        try (VerifiedContent content = storedFiles.retrieveVerified(row.archiveFileId())) {
            archive = content.file();
            manifest = readManifest(content.path());
        }
        audit.record(row.region(), admin, "CONNECTION_REQUEST_VIEWED", "client", clientId, Map.of());

        ConnectionRequestForm form = manifest.request();
        List<RequestSummary> others = repository.findByIdentity(row.identityHmac(), clientId).stream()
                .map(RequestSummary::of)
                .toList();
        return new AdminRequestDetail(RequestSummary.of(row), row.submittedBy(), row.reviewedBy(),
                form.applicant(), form.consumptionPlace(), form.permit(), repository.documents(clientId),
                new ArchiveInfo(archive.id(), archive.backend(), archive.sizeBytes(), archive.sha256(),
                        archive.encryption(), archive.storedAt(), Instant.now()),
                others);
    }

    /** The full decrypted, verified zip. Caller must close it. */
    public VerifiedContent openArchive(UUID clientId, String admin) {
        ClientRow row = require(clientId);
        VerifiedContent content = storedFiles.retrieveVerified(row.archiveFileId());
        audit.record(row.region(), admin, "CONNECTION_ARCHIVE_DOWNLOADED", "client", clientId,
                Map.of("archiveFileId", row.archiveFileId()));
        return content;
    }

    /** One document from the archive, re-verified against its own checksum. Caller must close it. */
    public DocumentContent openDocument(UUID clientId, UUID documentId, String admin) {
        ClientRow row = require(clientId);
        DocumentRow doc = repository.documents(clientId).stream()
                .filter(d -> d.id().equals(documentId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Document"));
        Path extracted = tempFiles.create("doc-");
        try (VerifiedContent archive = storedFiles.retrieveVerified(row.archiveFileId());
             ZipFile zip = new ZipFile(archive.path().toFile())) {
            ZipEntry entry = zip.getEntry(doc.entryName());
            if (entry == null) {
                throw integrity("entry " + doc.entryName() + " missing from archive");
            }
            MessageDigest digest = sha256();
            try (InputStream in = new DigestInputStream(zip.getInputStream(entry), digest)) {
                Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!HEX.formatHex(digest.digest()).equals(doc.sha256())) {
                throw integrity("checksum mismatch for " + doc.entryName());
            }
        } catch (IOException e) {
            TempFiles.deleteQuietly(extracted);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            TempFiles.deleteQuietly(extracted);
            throw e;
        }
        audit.record(row.region(), admin, "CONNECTION_DOCUMENT_DOWNLOADED", "client_document", documentId,
                Map.of("clientId", clientId, "documentType", doc.documentType()));
        return new DocumentContent(doc, extracted);
    }

    @Transactional
    public RequestSummary decide(UUID clientId, Decision decision, String reason, String admin) {
        ClientRow row = require(clientId);
        if (row.submittedBy().equals(admin)) {
            throw ApiException.forbidden("own_request", "Admins cannot review their own connection request");
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.strip();
        if (decision == Decision.REJECTED && cleanReason == null) {
            throw ApiException.badRequest("reason_required", "A reason is required to reject a request");
        }
        Status target = Status.valueOf(decision.name());
        if (!repository.decide(clientId, target, cleanReason, admin)) {
            throw ApiException.conflict("already_decided",
                    "Request is no longer PENDING (current status: " + require(clientId).status() + ")");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", Status.PENDING);
        details.put("to", target);
        if (cleanReason != null) {
            details.put("reason", cleanReason);
        }
        audit.record(row.region(), admin, "CONNECTION_REQUEST_" + target, "client", clientId, details);
        return RequestSummary.of(require(clientId));
    }

    // ------------------------------------------------------------------ helpers

    private ClientRow require(UUID clientId) {
        return repository.find(clientId).orElseThrow(() -> ApiException.notFound("Connection request"));
    }

    private static void validateIdentity(ConnectionRequestForm form) {
        String id = form.applicant().identityNumber();
        boolean valid = form.applicantType() == ApplicantType.PERSOANA_FIZICA
                ? IdentityNumbers.isValidCnp(id)
                : IdentityNumbers.isValidCui(id);
        if (!valid) {
            throw ApiException.badRequest("invalid_identity_number",
                    form.applicantType() == ApplicantType.PERSOANA_FIZICA
                            ? "applicant.identityNumber is not a valid CNP"
                            : "applicant.identityNumber is not a valid CUI");
        }
    }

    private record PendingDocument(DocumentType type, MultipartFile file, DocumentFormat format, String entryName) {
    }

    private static List<PendingDocument> validateDocuments(ConnectionRequestForm form, Uploads uploads) {
        List<PendingDocument> docs = new ArrayList<>();
        docs.add(validate(1, DocumentType.CERERE_RACORDARE, "cerereRacordare", uploads.cerereRacordare()));
        docs.add(validate(2, DocumentType.ACT_IDENTITATE, "actIdentitate", uploads.actIdentitate()));
        docs.add(validate(3, form.permit().type().documentType(), "aviz", uploads.aviz()));
        return docs;
    }

    private static PendingDocument validate(int order, DocumentType type, String part, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("document_missing", "Part '" + part + "' is required and must not be empty");
        }
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "document_too_large",
                    "Part '" + part + "' exceeds " + MAX_DOCUMENT_BYTES / (1024 * 1024) + " MB");
        }
        DocumentFormat format;
        try (InputStream in = file.getInputStream()) {
            format = DocumentFormat.detect(in).orElseThrow(() -> new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_document_format",
                    "Part '" + part + "' must be a PDF, JPEG or PNG"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Entry names are generated: client file names may contain personal data or path tricks.
        String entryName = "%02d-%s%s".formatted(order, type.slug(), format.extension());
        return new PendingDocument(type, file, format, entryName);
    }

    private static DocumentRow addEntry(ZipOutputStream zip, PendingDocument doc, Instant time,
                                        ConnectionRequestForm.Permit permit) throws IOException {
        ZipEntry entry = new ZipEntry(doc.entryName());
        entry.setTime(time.toEpochMilli());
        zip.putNextEntry(entry);
        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = doc.file().getInputStream()) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                zip.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                size += n;
            }
        }
        zip.closeEntry();
        boolean isPermit = doc.type() == permit.type().documentType();
        return new DocumentRow(UuidV7.generate(), doc.type(), doc.entryName(), doc.format().mediaType(), size,
                HEX.formatHex(digest.digest()),
                isPermit ? permit.issuer() : null,
                isPermit ? permit.issuedOn() : null,
                isPermit ? permit.validUntil() : null);
    }

    private ArchiveManifest readManifest(Path archive) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(ArchiveManifest.ENTRY_NAME);
            if (entry == null) {
                throw integrity("manifest missing from archive");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return json.readValue(in, ArchiveManifest.class);
            }
        } catch (IOException | JacksonException e) {
            throw integrity("unreadable archive: " + e.getMessage());
        }
    }

    private static ApiException integrity(String reason) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "integrity_check_failed",
                "Connection archive failed validation: " + reason);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An extracted, verified document. Closing deletes the temp file. */
    public record DocumentContent(DocumentRow document, Path path) implements AutoCloseable {
        @Override
        public void close() {
            TempFiles.deleteQuietly(path);
        }
    }
}
