package ro.ppc.filegrid.connection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"1960529460012", "2851215400124", "5030710123456", " 1960529460012 "})
    void acceptsValidCnp(String cnp) {
        assertThat(IdentityNumbers.isValidCnp(cnp)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1960529460013", // wrong control digit
            "1961329460012", // month 13
            "1960230460012", // 30 February
            "196052946001",  // too short
            "0960529460012", // invalid sex digit
            "1960529990012", // county 99
            "19605294600AB",
            ""})
    void rejectsInvalidCnp(String cnp) {
        assertThat(IdentityNumbers.isValidCnp(cnp)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"14399840", "RO14399840", "ro 18547290", "15900825"})
    void acceptsValidCui(String cui) {
        assertThat(IdentityNumbers.isValidCui(cui)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"14399841", "1", "12345678901", "RO", "0439984"})
    void rejectsInvalidCui(String cui) {
        assertThat(IdentityNumbers.isValidCui(cui)).isFalse();
    }
}
