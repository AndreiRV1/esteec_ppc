package ro.ppc.filegrid.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import ro.ppc.filegrid.common.Region;

/** API shapes of the customer portal ({@code /api/v1/me}). */
public final class Accounts {

    private Accounts() {
    }

    public enum Bucket { HOUR, DAY }

    /** The customer's meter and its area. */
    public record Account(String username, Region region, String county, String locality, String meterId) {
    }

    /** Who is logged in. The area and meter are null for users without a customer account (staff). */
    public record Me(String username, List<String> roles, Region region, String county, String locality,
                     String meterId) {
    }

    /** @param anomalies readings in the bucket flagged ANOMALY by the meter pipeline */
    public record Point(Instant start, BigDecimal kwh, int anomalies) {
    }

    public record Consumption(String meterId, Bucket bucket, List<Point> points) {
    }

    public record ConsumptionSummary(BigDecimal monthToDateKwh, BigDecimal previousMonthKwh, Instant lastReadingAt) {
    }
}
