package ro.ppc.filegrid.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ro.ppc.filegrid.support.TestUsers.admin;
import static ro.ppc.filegrid.support.TestUsers.employee;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;
import ro.ppc.filegrid.support.TestStorages;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fault records and drone videos through the API, on every storage backend the
 * admin can choose, against the real PostgreSQL, MinIO and vsftpd.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileIngestFlowTest {

    private static final int PART = 5 * 1024 * 1024; // filegrid.uploads.part-size in application-test.yaml

    @DynamicPropertySource
    static void storages(DynamicPropertyRegistry registry) {
        TestStorages.register(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    // The real vsftpd store, made to fail on demand to stand for "FTP is down".
    @MockitoSpyBean
    FtpObjectStore ftp;

    @AfterEach
    void restoreDefaultPolicies() {
        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(TestStorages.POSTGRES.getJdbcUrl(),
                TestStorages.POSTGRES.getUsername(), TestStorages.POSTGRES.getPassword()));
        db.update("UPDATE filegrid.storage_policy SET backend = 'FTP', target = '/files/fault-records', "
                + "max_size_bytes = 52428800 WHERE category = 'FAULT_RECORD'");
        db.update("UPDATE filegrid.storage_policy SET backend = 'S3', target = 'filegrid-drone-videos', "
                + "max_size_bytes = 10737418240 WHERE category = 'DRONE_VIDEO'");
    }

    @Test
    void faultRecordIsStoredWhereThePolicySaysAndComesBackIdentical() throws Exception {
        byte[] recording = random(300_000, 1);

        JsonNode onFtp = uploadFault("BANAT", "TM", recording);
        assertThat(onFtp.get("sha256").asString()).isEqualTo(sha256(recording));
        String id = onFtp.get("id").asString();

        mvc.perform(get("/api/v1/fault-records?region=BANAT&county=Timiș&severity=HIGH").with(employee("emp-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].file.backend").value("FTP"))
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].county").value("Timiș"));

        MvcResult download = download("/api/v1/fault-records/" + id + "/file", null, 200);
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(recording);
        assertThat(download.getResponse().getHeader("X-Checksum-SHA256")).isEqualTo(sha256(recording));

        // The admin moves the category to S3: the next file goes there, the old one stays readable.
        policy("FAULT_RECORD", "BANAT", Map.of("backend", "S3", "target", "filegrid-fault-records",
                "encryptAtRest", false, "maxSizeBytes", 52428800)).andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedBy").value("admin-1"));
        JsonNode onS3 = uploadFault("BANAT", "Arad", random(200_000, 2));
        mvc.perform(get("/api/v1/fault-records/" + onS3.get("id").asString()).with(admin("admin-1")))
                .andExpect(jsonPath("$.file.backend").value("S3"));
        assertThat(download("/api/v1/fault-records/" + id + "/file", null, 200).getResponse().getContentAsByteArray())
                .isEqualTo(recording);

        // Rules: county must belong to the region; personal data must stay encrypted.
        mvc.perform(faultUpload("BANAT", "Constanța", recording)).andExpect(status().isBadRequest());
        policy("CONNECTION_DOCUMENTS", "BANAT", Map.of("backend", "S3", "target", "filegrid-connection-documents",
                "encryptAtRest", false, "maxSizeBytes", 52428800)).andExpect(status().isBadRequest());

        // Restoring the supply closes the fault.
        mvc.perform(post("/api/v1/fault-records/" + id + "/restore").with(employee("emp-1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.restoredAt").isNotEmpty());
    }

    @Test
    void droneVideoUploadResumesAndVerifiesOnEveryBackend() throws Exception {
        policy("DRONE_VIDEO", "DOBROGEA", Map.of("backend", "FTP", "target", "/files/drone-videos",
                "encryptAtRest", false, "maxSizeBytes", 10737418240L)).andExpect(status().isOk());
        policy("DRONE_VIDEO", "MUNTENIA", Map.of("backend", "DATABASE", "encryptAtRest", false,
                "maxSizeBytes", 104857600)).andExpect(status().isOk());

        String[][] cases = {{"BANAT", "TM", "S3"}, {"DOBROGEA", "CT", "FTP"}, {"MUNTENIA", "B", "DATABASE"}};
        for (String[] c : cases) {
            byte[] video = random(PART + 1_234_567, c[2].hashCode()); // two parts
            String uploadId = startUpload(c[0], c[1], video.length, sha256(video)).get("uploadId").asString();
            String parts = "/api/v1/drone-videos/uploads/" + uploadId;

            part(parts, 2, Arrays.copyOfRange(video, PART, video.length), "emp-1").andExpect(status().isConflict());
            part(parts, 1, Arrays.copyOf(video, PART - 1), "emp-1").andExpect(status().isBadRequest()); // cut off
            part(parts, 1, Arrays.copyOf(video, PART), "emp-1").andExpect(status().isNoContent());
            part(parts, 1, Arrays.copyOf(video, PART), "emp-1").andExpect(status().isNoContent()); // retry: no effect
            part(parts, 1, Arrays.copyOf(video, PART), "emp-2").andExpect(status().isNotFound()); // not theirs

            // "Connection lost": the client asks where it was and continues with the next part.
            mvc.perform(get(parts).with(employee("emp-1"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UPLOADING"))
                    .andExpect(jsonPath("$.bytesReceived").value(PART))
                    .andExpect(jsonPath("$.receivedParts.length()").value(1));
            mvc.perform(post(parts + "/complete").with(employee("emp-1"))).andExpect(status().isConflict());
            part(parts, 2, Arrays.copyOfRange(video, PART, video.length), "emp-1").andExpect(status().isNoContent());

            JsonNode done = json.readTree(mvc.perform(post(parts + "/complete").with(employee("emp-1")))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
            assertThat(done.get("sha256").asString()).as(c[2]).isEqualTo(sha256(video));
            mvc.perform(post(parts + "/complete").with(employee("emp-1"))).andExpect(status().isCreated());

            String videoUrl = "/api/v1/drone-videos/" + done.get("id").asString();
            mvc.perform(get(videoUrl).with(employee("emp-2"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.file.backend").value(c[2]))
                    .andExpect(jsonPath("$.file.sizeBytes").value(video.length));
            assertThat(download(videoUrl + "/file", null, 200).getResponse().getContentAsByteArray())
                    .as(c[2]).isEqualTo(video);
            MvcResult range = download(videoUrl + "/file", "bytes=" + (PART - 5) + "-" + (PART + 4), 206);
            assertThat(range.getResponse().getContentAsByteArray())
                    .isEqualTo(Arrays.copyOfRange(video, PART - 5, PART + 5));
            assertThat(range.getResponse().getHeader("Content-Range"))
                    .isEqualTo("bytes " + (PART - 5) + "-" + (PART + 4) + "/" + video.length);
        }

        // A file that does not match its declared checksum never becomes visible.
        byte[] small = random(1_000, 9);
        String bad = startUpload("BANAT", "TM", small.length, sha256(random(1_000, 10))).get("uploadId").asString();
        part("/api/v1/drone-videos/uploads/" + bad, 1, small, "emp-1").andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/drone-videos/uploads/" + bad + "/complete").with(employee("emp-1")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/drone-videos?region=BANAT&lineCode=LEA-TEST-01").with(employee("emp-1")))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void whenThePolicyStorageIsDownFilesGoToTheOtherStorageAndTheResponseSaysSo() throws Exception {
        StorageUnavailableException down = new StorageUnavailableException(StorageBackendType.FTP, "put",
                new java.io.IOException("Connection refused"));
        doThrow(down).when(ftp).put(any(), any(), anyLong());
        doThrow(down).when(ftp).beginParts(any());

        // Fault records: the policy says FTP.
        byte[] recording = random(150_000, 21);
        JsonNode created = uploadFault("BANAT", "TM", recording);
        assertThat(created.get("backend").asString()).isEqualTo("S3");
        assertThat(created.get("fallbackFrom").asString()).isEqualTo("FTP");
        String faultUrl = "/api/v1/fault-records/" + created.get("id").asString();
        mvc.perform(get(faultUrl).with(admin("admin-1")))
                .andExpect(jsonPath("$.file.backend").value("S3"))
                .andExpect(jsonPath("$.file.fallbackFrom").value("FTP"));
        assertThat(download(faultUrl + "/file", null, 200).getResponse().getContentAsByteArray()).isEqualTo(recording);

        // Drone videos with an FTP policy move to S3 when the upload starts, before any part is stored.
        policy("DRONE_VIDEO", "DOBROGEA", Map.of("backend", "FTP", "target", "/files/drone-videos",
                "encryptAtRest", false, "maxSizeBytes", 10737418240L)).andExpect(status().isOk());
        byte[] video = random(1_000_000, 22);
        String uploadId = startUpload("DOBROGEA", "CT", video.length, sha256(video)).get("uploadId").asString();
        String parts = "/api/v1/drone-videos/uploads/" + uploadId;
        part(parts, 1, video, "emp-1").andExpect(status().isNoContent());
        JsonNode done = json.readTree(mvc.perform(post(parts + "/complete").with(employee("emp-1")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(done.get("backend").asString()).isEqualTo("S3");
        assertThat(done.get("fallbackFrom").asString()).isEqualTo("FTP");
        assertThat(download("/api/v1/drone-videos/" + done.get("id").asString() + "/file", null, 200)
                .getResponse().getContentAsByteArray()).isEqualTo(video);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode uploadFault(String region, String county, byte[] content) throws Exception {
        return json.readTree(mvc.perform(faultUpload(region, county, content)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.RequestBuilder faultUpload(String region, String county,
                                                                             byte[] content) {
        String metadata = json.writeValueAsString(Map.of("region", region, "county", county,
                "locality", "Test", "substationCode", "ST-TEST-110", "occurredAt", "2026-11-14T08:00:00Z",
                "severity", "HIGH", "affectedCustomers", 1200));
        return multipart("/api/v1/fault-records")
                .file(new MockMultipartFile("file", "fault.json", "application/json", content))
                .file(new MockMultipartFile("metadata", "", "application/json", metadata.getBytes()))
                .with(employee("emp-1"));
    }

    private JsonNode startUpload(String region, String county, long size, String sha256) throws Exception {
        String body = json.writeValueAsString(Map.of("region", region, "county", county,
                "lineCode", "LEA-TEST-01", "droneId", "DR-1", "flightStartedAt", "2026-11-14T09:00:00Z",
                "durationSeconds", 60, "filename", "flight.mp4", "contentType", "video/mp4",
                "sizeBytes", size, "sha256", sha256));
        return json.readTree(mvc.perform(post("/api/v1/drone-videos/uploads").with(employee("emp-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.partSizeBytes").value(PART))
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions part(String uploadUrl, int number, byte[] bytes, String user) throws Exception {
        return mvc.perform(put(uploadUrl + "/parts/" + number).with(employee(user))
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes));
    }

    private ResultActions policy(String category, String region, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/admin/storage-policies/" + category + "/" + region).with(admin("admin-1"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private MvcResult download(String url, String range, int expectedStatus) throws Exception {
        var request = get(url).with(employee("emp-1"));
        if (range != null) {
            request.header("Range", range);
        }
        MvcResult started = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().is(expectedStatus)).andReturn();
    }

    private static byte[] random(int size, int seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
