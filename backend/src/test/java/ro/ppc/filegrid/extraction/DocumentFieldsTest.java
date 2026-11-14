package ro.ppc.filegrid.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/** Form autofill on the demo documents (demo/, made by scripts/make-demo-documents.py). */
class DocumentFieldsTest {

    private static String textOf(String demoFile) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(Path.of("..", "demo", demoFile).toFile())) {
            return DocumentText.textLayer(pdf);
        }
    }

    @Test
    void readsConnectionRequestFromTextLayer() throws Exception {
        assertThat(DocumentFields.connection(textOf("documents/cerere-racordare.pdf"))).containsAllEntriesOf(Map.ofEntries(
                Map.entry("applicantType", "PERSOANA_FIZICA"),
                Map.entry("applicant.fullName", "Ion Demo"),
                Map.entry("applicant.identityNumber", "1960529460012"),
                Map.entry("applicant.email", "ion.demo@example.ro"),
                Map.entry("applicant.phone", "+40 721 000 000"),
                Map.entry("applicant.address", "Str. Exemplului nr. 1, Voluntari, jud. Ilfov"),
                Map.entry("requestType", "LOC_CONSUM_NOU"),
                Map.entry("consumptionPlace.county", "Ilfov"),
                Map.entry("consumptionPlace.locality", "Voluntari"),
                Map.entry("consumptionPlace.address", "Str. Exemplului nr. 2"),
                Map.entry("requestedPowerKw", "9.5"),
                Map.entry("permit.type", "AVIZ_AMPLASAMENT"),
                Map.entry("permit.number", "AA-2026-0042"),
                Map.entry("permit.issuedOn", "2026-11-01")));
    }

    @Test
    void readsCompanyRequestAndPermit() throws Exception {
        assertThat(DocumentFields.connection(textOf("documents/cerere-racordare-firma.pdf"))).containsAllEntriesOf(Map.of(
                "applicantType", "PERSOANA_JURIDICA", "applicant.fullName", "Demo Energie SRL",
                "applicant.identityNumber", "RO18547290", "requestType", "SPOR_PUTERE", "requestedPowerKw", "45",
                "permit.type", "CERTIFICAT_URBANISM", "permit.number", "CU-2026-0815"));
        assertThat(DocumentFields.connection(textOf("documents/aviz-amplasament.pdf"))).containsAllEntriesOf(Map.of(
                "permit.type", "AVIZ_AMPLASAMENT", "permit.number", "AA-2026-0042", "permit.issuedOn", "2026-11-01",
                "permit.validUntil", "2027-11-01", "permit.issuer", "Operatorul de distribuție – Sucursala Ilfov"));
    }

    @Test
    void readsFaultReports() throws Exception {
        assertThat(DocumentFields.fault(textOf("faults/avarie-voluntari.pdf"))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "region", "MUNTENIA", "county", "Ilfov", "locality", "Voluntari", "substationCode", "ST-MUN-VOLUNTARI-110",
                "occurredAt", "2026-11-14T17:30", "severity", "HIGH", "affectedCustomers", "1240",
                "cause", "Cablu subteran deteriorat",
                "description", "Declanșare protecție pe fiderul 20 kV Voluntari Nord. Echipă trimisă la fața locului."));
        assertThat(DocumentFields.fault(textOf("faults/avarie-constanta.pdf"))).containsAllEntriesOf(Map.of(
                "region", "DOBROGEA", "county", "Constanța", "restoredAt", "2026-11-12T06:40", "severity", "MEDIUM",
                "description", "Conductor rupt pe LEA 20 kV Mangalia Sud între stâlpii 14 și 15. Alimentare refăcută prin buclare."));
    }

    /** Tesseract 5 output for the "-scanata" demo copies: diacritics dropped, "I" read as "l", "@" as "Q". */
    @Test
    void readsOcrTextWithTypicalMistakes() {
        String cerere = """
                Tip solicitant Persoană fizică
                Nume si prenume lon Demo
                CNP 1960529460012
                E-mail ion.demoQexample.ro
                Judet Ilfov
                Putere solicitată 9,5 kW (monofazat)
                . Aviz de amplasament nr. AA-2026-0042 din 01.11.2026
                """;
        assertThat(DocumentFields.connection(cerere)).containsAllEntriesOf(Map.of(
                "applicant.fullName", "Ion Demo", "applicant.email", "ion.demo@example.ro",
                "consumptionPlace.county", "Ilfov", "requestedPowerKw", "9.5", "permit.number", "AA-2026-0042"));

        String fault = """
                Regiune Muntenia
                Judet Ilfov
                Momentul avariei 14.11.2026 17:30
                Alimentare restabilită -
                Severitate Ridicata
                Clienti afectati 1.240
                Descriere Declansare protectie pe fiderul 20 kV Voluntari Nord.
                Echipă trimisa la fata locului.
                3. Masuratori la declansare
                """;
        assertThat(DocumentFields.fault(fault))
                .containsEntry("occurredAt", "2026-11-14T17:30").containsEntry("severity", "HIGH")
                .containsEntry("affectedCustomers", "1240").doesNotContainKey("restoredAt")
                .containsEntry("description", "Declansare protectie pe fiderul 20 kV Voluntari Nord. Echipă trimisa la fata locului.");
    }

    @Test
    void leavesOutWhatItCannotRead() {
        assertThat(DocumentFields.fault("Momentul avariei 31.02.2026 25:00\nRegiune Atlantida")).isEmpty();
        assertThat(DocumentFields.connection("")).isEmpty();
    }
}
