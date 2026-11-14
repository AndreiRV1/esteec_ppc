package ro.ppc.filegrid.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ro.ppc.filegrid.support.TestUsers.client;
import static ro.ppc.filegrid.support.TestUsers.employee;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ro.ppc.filegrid.support.TestStorages;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** What a customer sees after logging in with their client id: own meter, consumption, faults nearby. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerPortalTest {

    private static final ZoneId LOCAL = ZoneId.of("Europe/Bucharest");

    @DynamicPropertySource
    static void storages(DynamicPropertyRegistry registry) {
        TestStorages.register(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void customerSeesOwnConsumptionAndFaultsInTheirCounty() throws Exception {
        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(TestStorages.POSTGRES.getJdbcUrl(),
                TestStorages.POSTGRES.getUsername(), TestStorages.POSTGRES.getPassword()));
        db.update("INSERT INTO filegrid.meter (id, region, county, locality) VALUES "
                + "('MTR-D-0000042', 'DOBROGEA', 'Tulcea', 'Tulcea'), ('MTR-D-0000043', 'DOBROGEA', 'Tulcea', 'Babadag')");
        db.update("INSERT INTO filegrid.customer_account (username, region, meter_id) VALUES "
                + "('cl-0000042', 'DOBROGEA', 'MTR-D-0000042')");
        // Readings: 3 this month (one invalid), 2 last month, and another customer's meter.
        ZonedDateTime monthStart = ZonedDateTime.now(LOCAL).truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
        Long raw = db.queryForObject("INSERT INTO filegrid.meter_reading_raw (region, meter_id, message_id, period_start, "
                + "period_end, content_type, payload, sha256) VALUES ('DOBROGEA', 'MTR-D-0000042', gen_random_uuid(), "
                + "now() - interval '1 hour', now(), 'application/json', '{}'::bytea, repeat('0', 64)) RETURNING id", Long.class);
        Long otherRaw = db.queryForObject("INSERT INTO filegrid.meter_reading_raw (region, meter_id, message_id, period_start, "
                + "period_end, content_type, payload, sha256) VALUES ('DOBROGEA', 'MTR-D-0000043', gen_random_uuid(), "
                + "now() - interval '1 hour', now(), 'application/json', '{}'::bytea, repeat('0', 64)) RETURNING id", Long.class);
        reading(db, raw, "MTR-D-0000042", monthStart.toInstant(), "1.500", null);
        reading(db, raw, "MTR-D-0000042", monthStart.plusMinutes(15).toInstant(), "2.000", "VOLTAGE_OUT_OF_RANGE");
        reading(db, raw, "MTR-D-0000042", monthStart.plusMinutes(30).toInstant(), "-9.000", "NEGATIVE_ENERGY");
        reading(db, raw, "MTR-D-0000042", monthStart.minusDays(2).toInstant(), "4.000", null);
        reading(db, raw, "MTR-D-0000042", monthStart.minusDays(3).toInstant(), "6.000", null);
        reading(db, otherRaw, "MTR-D-0000043", monthStart.toInstant(), "100.000", null);

        mvc.perform(get("/api/v1/me").with(client("CL-0000042"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("cl-0000042"))
                .andExpect(jsonPath("$.roles[0]").value("client"))
                .andExpect(jsonPath("$.meterId").value("MTR-D-0000042"))
                .andExpect(jsonPath("$.county").value("Tulcea"));

        JsonNode summary = getJson("/api/v1/me/consumption/summary");
        assertThat(summary.get("monthToDateKwh").decimalValue()).isEqualByComparingTo("3.5");
        assertThat(summary.get("previousMonthKwh").decimalValue()).isEqualByComparingTo("10");
        assertThat(Instant.parse(summary.get("lastReadingAt").asString()))
                .isEqualTo(monthStart.plusMinutes(30).toInstant());

        JsonNode daily = getJson("/api/v1/me/consumption?bucket=DAY&from=" + monthStart.minusDays(5).toInstant()
                + "&to=" + monthStart.plusDays(1).toInstant());
        assertThat(daily.get("meterId").asString()).isEqualTo("MTR-D-0000042");
        assertThat(daily.get("points")).hasSize(3);
        JsonNode today = daily.get("points").get(2);
        assertThat(Instant.parse(today.get("start").asString())).isEqualTo(monthStart.toInstant());
        assertThat(today.get("kwh").decimalValue()).isEqualByComparingTo("3.5");
        assertThat(today.get("anomalies").asInt()).isEqualTo(2);
        mvc.perform(get("/api/v1/me/consumption?bucket=HOUR&from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z")
                .with(client("cl-0000042"))).andExpect(status().isBadRequest());

        // A fault in the customer's county shows up; one elsewhere does not.
        mvc.perform(get("/api/v1/me/faults").with(client("cl-0000042")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        fault("Tulcea");
        fault("Constanța");
        mvc.perform(get("/api/v1/me/faults").with(client("cl-0000042"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].county").value("Tulcea"))
                .andExpect(jsonPath("$[0].severity").value("CRITICAL"));

        // Staff have no meter: /me works, the customer data does not exist for them.
        mvc.perform(get("/api/v1/me").with(employee("emp-1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.meterId").isEmpty());
        mvc.perform(get("/api/v1/me/consumption").with(employee("emp-1"))).andExpect(status().isNotFound());
    }

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url).with(client("cl-0000042"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void fault(String county) throws Exception {
        String metadata = "{\"region\":\"DOBROGEA\",\"county\":\"" + county + "\",\"substationCode\":\"ST-DOB-1\","
                + "\"occurredAt\":\"" + Instant.now().minusSeconds(600) + "\",\"severity\":\"CRITICAL\"}";
        mvc.perform(multipart("/api/v1/fault-records")
                        .file(new MockMultipartFile("file", "f.json", "application/json", "{\"x\":1}".getBytes()))
                        .file(new MockMultipartFile("metadata", "", "application/json", metadata.getBytes()))
                        .with(employee("emp-1")))
                .andExpect(status().isCreated());
    }

    private static void reading(JdbcTemplate db, Long rawId, String meter, Instant at, String kwh, String anomaly) {
        db.update("INSERT INTO filegrid.meter_reading (region, meter_id, raw_id, interval_start, register_kwh, "
                        + "consumption_kwh, quality, anomaly_reason) VALUES ('DOBROGEA', ?, ?, ?::timestamptz, 100, ?::numeric, ?, ?)",
                meter, rawId, at.toString(), kwh, anomaly == null ? "VALID" : "ANOMALY", anomaly);
    }
}
