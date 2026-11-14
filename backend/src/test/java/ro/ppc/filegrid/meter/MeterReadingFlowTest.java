package ro.ppc.filegrid.meter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import ro.ppc.filegrid.support.TestStorages;

/** Ingest -> raw table -> periodic processor -> meter_reading, against the real schema. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeterReadingFlowTest {

    private static final String METER = "MTR-B-0000001";
    /** Start of a quarter hour, two hours ago. */
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS);

    @DynamicPropertySource
    static void storages(DynamicPropertyRegistry registry) {
        TestStorages.register(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterReadingProcessor processor;

    @Test
    void ingestsOnceThenProcessesIntoReadingsWithAnomalies() throws Exception {
        jdbc.update("INSERT INTO meter (id, region, county, locality) VALUES (?, 'BANAT', 'Timiș', 'Timișoara')", METER);

        String first = reading(METER, value(0, "0.400", "100.400", "230.1") + "," + value(1, "0.410", "100.810", "190.0"));
        send(first, "wrong-key", status().isUnauthorized());
        send(first, "test-meter-key", status().isAccepted());
        send(first, "test-meter-key", status().isOk()); // retry: not stored again
        send(reading("MTR-B-9999999", value(0, "0.1", "1.0", "230")), "test-meter-key", status().isUnprocessableContent());
        send("{\"readingId\":", "test-meter-key", status().isBadRequest());
        // Second message: negative energy, a register rollback, and an interval already sent above.
        send(reading(METER, value(1, "0.410", "100.810", "230.0") + "," + value(2, "-0.300", "101.200", "230.0")
                + "," + value(3, "0.400", "50.000", "230.0")), "test-meter-key", status().isAccepted());
        // Third message: timestamps a day ahead of the server clock.
        send(reading(METER, value(24 * 4, "0.400", "102.000", "230.0")), "test-meter-key", status().isAccepted());
        // Fourth message: a voltage too large for numeric(6,2) must be rejected, not block the batch.
        send(reading(METER, value(5, "0.400", "102.000", "10000")), "test-meter-key", status().isAccepted());

        assertThat(count("meter_reading_raw WHERE meter_id = '" + METER + "' AND processing_status = 'PENDING'")).isEqualTo(4);
        assertThat(processor.processBatch()).isGreaterThanOrEqualTo(4);
        assertThat(processor.processBatch()).isZero();

        assertThat(count("meter_reading_raw WHERE meter_id = '" + METER + "' AND processing_status = 'PROCESSED'")).isEqualTo(2);
        assertThat(count("meter_reading_raw WHERE meter_id = '" + METER + "' AND processing_status = 'REJECTED' AND processing_error LIKE '%future%'"))
                .isEqualTo(1);
        assertThat(count("meter_reading_raw WHERE meter_id = '" + METER + "' AND processing_status = 'REJECTED' AND processing_error LIKE '%voltage out of range%'"))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT coalesce(anomaly_reason, quality) FROM meter_reading WHERE meter_id = ? "
                + "ORDER BY interval_start", String.class, METER))
                .containsExactly("VALID", "VOLTAGE_OUT_OF_RANGE", "NEGATIVE_ENERGY", "REGISTER_ROLLBACK");
    }

    private void send(String body, String key, ResultMatcher expected) throws Exception {
        mvc.perform(post("/api/v1/meter-readings").header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(expected);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }

    private static String reading(String meterId, String values) {
        return """
                {"readingId":"%s","meterId":"%s","sequence":1,"region":"BANAT","measuredAt":"%s",
                 "intervalMinutes":15,"firmware":"2.4.1","values":[%s]}""".formatted(UUID.randomUUID(), meterId, T0, values);
    }

    private static String value(int quarter, String energy, String register, String voltage) {
        return "{\"at\":\"%s\",\"activeEnergyKwh\":%s,\"registerKwh\":%s,\"voltageV\":%s}"
                .formatted(T0.plus(15L * quarter, ChronoUnit.MINUTES), energy, register, voltage);
    }
}
