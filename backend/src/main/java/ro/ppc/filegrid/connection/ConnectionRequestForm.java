package ro.ppc.filegrid.connection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import ro.ppc.filegrid.common.County;
import ro.ppc.filegrid.connection.ConnectionTypes.ApplicantType;
import ro.ppc.filegrid.connection.ConnectionTypes.PermitType;
import ro.ppc.filegrid.connection.ConnectionTypes.RequestType;

/**
 * The structured part of a connection request (the "request" multipart part).
 * Everything under {@code applicant} and the street addresses is personal data:
 * it is only ever written inside the encrypted archive, never to the database.
 */
public record ConnectionRequestForm(
        @NotNull ApplicantType applicantType,
        @NotNull RequestType requestType,
        @NotNull @Valid Applicant applicant,
        @NotNull @Valid ConsumptionPlace consumptionPlace,
        /* Puterea absorbită solicitată (Pa), kW. */
        @NotNull @DecimalMin("0.1") @DecimalMax("100000") @Digits(integer = 8, fraction = 2)
        BigDecimal requestedPowerKw,
        @NotNull @Valid Permit permit) {

    public record Applicant(
            /* Nume și prenume, or company name for a legal entity. */
            @NotBlank @Size(max = 200) String fullName,
            /* CNP for PERSOANA_FIZICA, CUI for PERSOANA_JURIDICA. */
            @NotBlank @Size(max = 16) String identityNumber,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\+?[0-9 ()-]{6,20}") String phone,
            /* Domiciliu / sediu. */
            @NotBlank @Size(max = 300) String address) {
    }

    public record ConsumptionPlace(
            @NotNull County county,
            @NotBlank @Size(max = 100) String locality,
            /* Street address of the consumption place. */
            @NotBlank @Size(max = 300) String address) {
    }

    public record Permit(
            @NotNull PermitType type,
            @NotBlank @Size(max = 50) String number,
            /* Primăria / consiliul județean (CU) or the operator's branch (aviz). */
            @NotBlank @Size(max = 200) String issuer,
            @NotNull @PastOrPresent LocalDate issuedOn,
            /* The permit must still be valid when the request is filed. */
            @NotNull @FutureOrPresent LocalDate validUntil) {

        @AssertTrue(message = "validUntil must not be before issuedOn")
        boolean isValidityRangeConsistent() {
            return issuedOn == null || validUntil == null || !validUntil.isBefore(issuedOn);
        }
    }
}
