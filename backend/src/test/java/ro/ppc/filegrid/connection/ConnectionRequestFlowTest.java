package ro.ppc.filegrid.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.ppc.filegrid.config.KeycloakRealmRoleConverter;
import ro.ppc.filegrid.storage.FtpObjectStore;
import ro.ppc.filegrid.storage.StorageBackendType;
import ro.ppc.filegrid.storage.StorageUnavailableException;
import ro.ppc.filegrid.support.TestStorages;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** End-to-end racordare flow against real PostgreSQL, MinIO and vsftpd containers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConnectionRequestFlowTest {

    private static final String CNP = "1960529460012";
    private static final String APPLICANT_NAME = "Ion Popescu-Testescu";

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG = jpeg();
    private static final byte[] PNG = png();

    @DynamicPropertySource
    static void storages(DynamicPropertyRegistry registry) {
        TestStorages.register(registry);
    }

    @Autowired
    MockMvc mvc;

    // The real vsftpd store; one test makes it fail to stand for "FTP is down".
    @MockitoSpyBean
    FtpObjectStore ftp;

    @Autowired
    ObjectMapper json;

    /** Superuser connection for assertions and policy changes (the app role cannot see/do everything). */
    private JdbcTemplate db;

    @BeforeEach
    void resetPolicies() {
        db = new JdbcTemplate(new DriverManagerDataSource(TestStorages.POSTGRES.getJdbcUrl(),
                TestStorages.POSTGRES.getUsername(), TestStorages.POSTGRES.getPassword()));
        db.update("""
                UPDATE filegrid.storage_policy SET backend = 'S3', target = 'filegrid-connection-documents',
                       encrypt_at_rest = true
                WHERE category = 'CONNECTION_DOCUMENTS'
                """);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void submitReviewAndAcceptARequest() throws Exception {
        String applicant = "applicant-" + UUID.randomUUID();
        JsonNode created = submit(applicant, form("IF", "PERSOANA_FIZICA", CNP), PDF, JPEG, PDF);
        UUID clientId = UUID.fromString(created.get("clientId").asText());

        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("region").asText()).isEqualTo("MUNTENIA"); // derived from county IF
        assertThat(created.get("county").asText()).isEqualTo("Ilfov");

        // The applicant sees it; another applicant does not.
        mvc.perform(get("/api/v1/connection-requests").with(client(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").value(clientId.toString()));
        mvc.perform(get("/api/v1/connection-requests/" + clientId).with(client("someone-else")))
                .andExpect(status().isNotFound());

        // Admin queue + detail with decrypted personal data.
        mvc.perform(get("/api/v1/admin/connection-requests?status=PENDING&region=MUNTENIA&size=100")
                        .with(admin("admin-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.clientId == '" + clientId + "')]").exists());
        JsonNode detail = getJson("/api/v1/admin/connection-requests/" + clientId, admin("admin-1"));
        assertThat(detail.at("/applicant/fullName").asText()).isEqualTo(APPLICANT_NAME);
        assertThat(detail.at("/applicant/identityNumber").asText()).isEqualTo(CNP);
        assertThat(detail.at("/permit/type").asText()).isEqualTo("AVIZ_AMPLASAMENT");
        assertThat(detail.at("/archive/backend").asText()).isEqualTo("S3");
        assertThat(detail.at("/archive/encryption").asText()).isEqualTo("AES_256_GCM");
        assertThat(detail.get("documents")).hasSize(3);

        // Rejecting needs a reason; accepting works once; a second decision conflicts.
        decide(clientId, "admin-1", Map.of("decision", "REJECTED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("reason_required"));
        decide(clientId, "admin-1", Map.of("decision", "ACCEPTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        decide(clientId, "admin-2", Map.of("decision", "REJECTED", "reason", "late"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_decided"));

        mvc.perform(get("/api/v1/connection-requests/" + clientId).with(client(applicant)))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        List<String> actions = db.queryForList(
                "SELECT action FROM filegrid.audit_log WHERE entity_id = ? ORDER BY id", String.class,
                clientId.toString());
        assertThat(actions).containsExactly(
                "CONNECTION_REQUEST_SUBMITTED", "CONNECTION_REQUEST_VIEWED", "CONNECTION_REQUEST_ACCEPTED");
    }

    @Test
    void rejectionKeepsTheReason() throws Exception {
        UUID clientId = submitId("applicant-" + UUID.randomUUID(), form("CT", "PERSOANA_FIZICA", CNP));

        decide(clientId, "admin-1", Map.of("decision", "REJECTED", "reason", "Aviz expirat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReason").value("Aviz expirat"))
                .andExpect(jsonPath("$.region").value("DOBROGEA"));
    }

    // ------------------------------------------------------------------ data protection & integrity

    @Test
    void storedArchiveIsEncryptedAndDatabaseHoldsNoPersonalData() throws Exception {
        UUID clientId = submitId("applicant-" + UUID.randomUUID(), form("B", "PERSOANA_FIZICA", CNP));
        String location = db.queryForObject("""
                SELECT f.location FROM filegrid.client c JOIN filegrid.stored_file f ON f.id = c.archive_file_id
                WHERE c.client_id = ?""", String.class, clientId);

        byte[] raw;
        try (S3Client s3 = TestStorages.s3Client()) {
            int slash = location.indexOf('/');
            raw = s3.getObjectAsBytes(b -> b.bucket(location.substring(0, slash)).key(location.substring(slash + 1)))
                    .asByteArray();
        }
        // Direct access to the bucket yields ciphertext: no zip header, no readable PII.
        assertThat(new String(raw, 0, 2, StandardCharsets.ISO_8859_1)).isNotEqualTo("PK");
        assertThat(new String(raw, StandardCharsets.ISO_8859_1)).doesNotContain(CNP).doesNotContain(APPLICANT_NAME);

        String rows = db.queryForObject("""
                SELECT row_to_json(c)::text || (SELECT json_agg(d)::text FROM filegrid.client_document d
                                                 WHERE d.client_id = c.client_id)
                FROM filegrid.client c WHERE c.client_id = ?""", String.class, clientId);
        assertThat(rows).doesNotContain(CNP).doesNotContain(APPLICANT_NAME).doesNotContain("str. Testului");
    }

    @Test
    void downloadsAreVerifiedAndIdenticalToTheUpload() throws Exception {
        UUID clientId = submitId("applicant-" + UUID.randomUUID(), form("IF", "PERSOANA_FIZICA", CNP));

        MvcResult archive = download("/api/v1/admin/connection-requests/" + clientId + "/archive");
        byte[] zip = archive.getResponse().getContentAsByteArray();
        assertThat(sha256(zip)).isEqualTo(archive.getResponse().getHeader("X-Checksum-SHA256"));
        assertThat(archive.getResponse().getHeader("Repr-Digest")).startsWith("sha-256=:");

        Map<String, byte[]> entries = unzip(zip);
        assertThat(entries.keySet()).containsExactly(
                "01-cerere-racordare.pdf", "02-act-identitate.jpg", "03-aviz-amplasament.pdf", "manifest.json");
        assertThat(entries.get("02-act-identitate.jpg")).isEqualTo(JPEG);
        assertThat(new String(entries.get("manifest.json"), StandardCharsets.UTF_8)).contains(APPLICANT_NAME);

        JsonNode detail = getJson("/api/v1/admin/connection-requests/" + clientId, admin("admin-1"));
        String idDocument = null;
        for (JsonNode d : detail.get("documents")) {
            if (d.get("documentType").asText().equals("ACT_IDENTITATE")) {
                idDocument = d.get("id").asText();
            }
        }
        MvcResult doc = download("/api/v1/admin/connection-requests/" + clientId + "/documents/" + idDocument);
        assertThat(doc.getResponse().getContentType()).isEqualTo("image/jpeg");
        assertThat(doc.getResponse().getContentAsByteArray()).isEqualTo(JPEG);
    }

    @Test
    void corruptedObjectIsDetectedAndNeverServed() throws Exception {
        UUID clientId = submitId("applicant-" + UUID.randomUUID(), form("IF", "PERSOANA_FIZICA", CNP));
        String location = db.queryForObject("""
                SELECT f.location FROM filegrid.client c JOIN filegrid.stored_file f ON f.id = c.archive_file_id
                WHERE c.client_id = ?""", String.class, clientId);
        int slash = location.indexOf('/');
        try (S3Client s3 = TestStorages.s3Client()) {
            byte[] raw = s3.getObjectAsBytes(b -> b.bucket(location.substring(0, slash))
                    .key(location.substring(slash + 1))).asByteArray();
            raw[raw.length / 2] ^= 0x01; // flip one bit
            s3.putObject(b -> b.bucket(location.substring(0, slash)).key(location.substring(slash + 1)),
                    RequestBody.fromBytes(raw));
        }

        mvc.perform(get("/api/v1/admin/connection-requests/" + clientId + "/archive").with(admin("admin-1")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("integrity_check_failed"));
        assertThat(db.queryForObject("""
                SELECT f.status FROM filegrid.client c JOIN filegrid.stored_file f ON f.id = c.archive_file_id
                WHERE c.client_id = ?""", String.class, clientId)).isEqualTo("CORRUPTED");
    }

    // ------------------------------------------------------------------ storage policy

    @Test
    void policyCanRouteArchivesToFtpOrTheDatabaseWithoutCodeChanges() throws Exception {
        for (String backend : List.of("FTP", "DATABASE")) {
            db.update("""
                    UPDATE filegrid.storage_policy SET backend = ?, target = ?
                    WHERE category = 'CONNECTION_DOCUMENTS' AND region = 'BANAT'""",
                    backend, backend.equals("FTP") ? "/files/connection-documents" : null);

            UUID clientId = submitId("applicant-" + UUID.randomUUID(), form("TM", "PERSOANA_FIZICA", CNP));

            JsonNode detail = getJson("/api/v1/admin/connection-requests/" + clientId, admin("admin-1"));
            assertThat(detail.at("/archive/backend").asText()).isEqualTo(backend);
            assertThat(detail.at("/applicant/fullName").asText()).isEqualTo(APPLICANT_NAME);
            byte[] zip = download("/api/v1/admin/connection-requests/" + clientId + "/archive")
                    .getResponse().getContentAsByteArray();
            assertThat(unzip(zip).get("01-cerere-racordare.pdf")).isEqualTo(PDF);
        }
    }

    @Test
    void storageFailureLeavesNoHalfCreatedRequest() throws Exception {
        // S3 (the policy) points at a missing bucket, and its stand-in FTP is down too: nothing can be stored.
        doThrow(new StorageUnavailableException(StorageBackendType.FTP, "put", new java.io.IOException("down")))
                .when(ftp).put(any(), any(), anyLong());
        db.update("""
                UPDATE filegrid.storage_policy SET target = 'bucket-that-does-not-exist'
                WHERE category = 'CONNECTION_DOCUMENTS' AND region = 'MUNTENIA'""");
        String applicant = "applicant-" + UUID.randomUUID();

        mvc.perform(submission(form("B", "PERSOANA_FIZICA", CNP), PDF, PNG, PDF).with(client(applicant)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("storage_unavailable"));

        assertThat(db.queryForObject("SELECT count(*) FROM filegrid.client WHERE submitted_by = ?",
                Long.class, applicant)).isZero();
        assertThat(db.queryForObject("SELECT status FROM filegrid.stored_file WHERE uploaded_by = ?",
                String.class, applicant)).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ validation

    @Test
    @SuppressWarnings("unchecked")
    void rejectsInvalidInput() throws Exception {
        String applicant = "applicant-" + UUID.randomUUID();

        mvc.perform(submission(form("IF", "PERSOANA_FIZICA", "1960529460013"), PDF, JPEG, PDF).with(client(applicant)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_identity_number"));

        mvc.perform(submission(form("IF", "PERSOANA_JURIDICA", "RO14399840"), PDF, JPEG, PDF).with(client(applicant)))
                .andExpect(status().isCreated());

        byte[] text = "just some text".getBytes(StandardCharsets.UTF_8);
        mvc.perform(submission(form("IF", "PERSOANA_FIZICA", CNP), PDF, text, PDF).with(client(applicant)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_document_format"));

        Map<String, Object> expired = form("IF", "PERSOANA_FIZICA", CNP);
        ((Map<String, Object>) expired.get("permit")).put("validUntil", "2020-01-01");
        ((Map<String, Object>) expired.get("permit")).put("issuedOn", "2019-01-01");
        mvc.perform(submission(expired, PDF, JPEG, PDF).with(client(applicant)))
                .andExpect(status().isBadRequest());

        mvc.perform(multipart("/api/v1/connection-requests")
                        .file(new MockMultipartFile("request", "", "application/json",
                                json.writeValueAsBytes(form("IF", "PERSOANA_FIZICA", CNP))))
                        .file(new MockMultipartFile("cerereRacordare", "c.pdf", "application/pdf", PDF))
                        .with(client(applicant)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ access control

    @Test
    void enforcesRoles() throws Exception {
        mvc.perform(get("/api/v1/connection-requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/connection-requests").with(client("applicant-x")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/connection-requests").with(admin("admin-only")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotReviewOwnRequest() throws Exception {
        String user = "admin-and-applicant-" + UUID.randomUUID();
        RequestPostProcessor both = jwt().jwt(j -> j.subject(user)
                        .claim("realm_access", Map.of("roles", List.of("client", "admin"))))
                .authorities(new KeycloakRealmRoleConverter());
        String body = mvc.perform(submission(form("IF", "PERSOANA_FIZICA", CNP), PDF, JPEG, PDF).with(both))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String clientId = json.readTree(body).get("clientId").asText();

        mvc.perform(post("/api/v1/admin/connection-requests/" + clientId + "/decision").with(both)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPTED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("own_request"));
    }

    // ------------------------------------------------------------------ helpers

    private static RequestPostProcessor client(String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("realm_access", Map.of("roles", List.of("client"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private static RequestPostProcessor admin(String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private static Map<String, Object> form(String county, String applicantType, String identityNumber) {
        Map<String, Object> permit = new LinkedHashMap<>();
        permit.put("type", "AVIZ_AMPLASAMENT");
        permit.put("number", "AA-2026-0042");
        permit.put("issuer", "Rețele Electrice Muntenia - Sucursala Ilfov");
        permit.put("issuedOn", "2026-01-15");
        permit.put("validUntil", "2099-01-15");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("applicantType", applicantType);
        form.put("requestType", "LOC_CONSUM_NOU");
        form.put("applicant", Map.of(
                "fullName", APPLICANT_NAME,
                "identityNumber", identityNumber,
                "email", "ion@example.com",
                "phone", "+40 721 000 000",
                "address", "str. Testului nr. 1, Voluntari"));
        form.put("consumptionPlace", Map.of("county", county, "locality", "Voluntari",
                "address", "str. Testului nr. 2"));
        form.put("requestedPowerKw", 8.5);
        form.put("permit", permit);
        return form;
    }

    private MockMultipartHttpServletRequestBuilder submission(Map<String, Object> form, byte[] cerere, byte[] ci,
                                                             byte[] aviz) throws Exception {
        return multipart("/api/v1/connection-requests")
                .file(new MockMultipartFile("request", "", "application/json", json.writeValueAsBytes(form)))
                .file(new MockMultipartFile("cerereRacordare", "Cerere Popescu.pdf", "application/pdf", cerere))
                .file(new MockMultipartFile("actIdentitate", "CI Popescu.jpg", "image/jpeg", ci))
                .file(new MockMultipartFile("aviz", "aviz.pdf", "application/pdf", aviz));
    }

    private JsonNode submit(String applicant, Map<String, Object> form, byte[] cerere, byte[] ci, byte[] aviz)
            throws Exception {
        String body = mvc.perform(submission(form, cerere, ci, aviz).with(client(applicant)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private UUID submitId(String applicant, Map<String, Object> form) throws Exception {
        return UUID.fromString(submit(applicant, form, PDF, JPEG, PDF).get("clientId").asText());
    }

    private JsonNode getJson(String url, RequestPostProcessor auth) throws Exception {
        String body = mvc.perform(get(url).with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private org.springframework.test.web.servlet.ResultActions decide(UUID clientId, String admin,
                                                                     Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v1/admin/connection-requests/" + clientId + "/decision")
                .with(admin(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private MvcResult download(String url) throws Exception {
        MvcResult started = mvc.perform(get(url).with(admin("admin-1")))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] jpeg() {
        List<Byte> bytes = new ArrayList<>(List.of((byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0));
        for (int i = 0; i < 2048; i++) {
            bytes.add((byte) (i * 31));
        }
        bytes.addAll(List.of((byte) 0xFF, (byte) 0xD9));
        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bytes.get(i);
        }
        return out;
    }

    private static byte[] png() {
        byte[] out = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        System.arraycopy(magic, 0, out, 0, magic.length);
        return out;
    }
}
