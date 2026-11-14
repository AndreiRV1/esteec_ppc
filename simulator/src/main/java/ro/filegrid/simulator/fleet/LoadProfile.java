package ro.filegrid.simulator.fleet;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

/**
 * Consumer types and their daily load curves. {@link #shape} returns a factor
 * around 1.0 to multiply with the meter's base power, in local (Bucharest) time.
 */
public enum LoadProfile {

    /** Low at night, small morning peak, big evening peak. */
    HOUSEHOLD(0.85, 300, 900, 0.20, new double[] {
            0.45, 0.38, 0.35, 0.34, 0.35, 0.45, 0.75, 1.10, 1.05, 0.85, 0.80, 0.85,
            0.95, 0.90, 0.85, 0.85, 0.95, 1.25, 1.65, 1.80, 1.70, 1.45, 1.05, 0.70}),

    /** Shops and offices: business hours on weekdays. */
    COMMERCIAL(0.12, 3_000, 15_000, 0.10, new double[] {
            0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.30, 0.60, 1.00, 1.05, 1.10, 1.10,
            1.10, 1.10, 1.10, 1.05, 1.05, 1.00, 0.90, 0.70, 0.45, 0.35, 0.30, 0.25}),

    /** Factories: near-flat, lower at night and on weekends. */
    INDUSTRIAL(0.03, 40_000, 250_000, 0.08, new double[] {
            0.75, 0.75, 0.75, 0.75, 0.75, 0.80, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00,
            1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 0.80, 0.75});

    /** Heating in winter, air conditioning in summer (index 0 = January). */
    private static final double[] SEASON = {1.25, 1.20, 1.05, 0.95, 0.90, 1.00, 1.15, 1.15, 0.95, 1.00, 1.15, 1.25};

    private final double share;
    private final int minBaseWatts;
    private final int maxBaseWatts;
    private final double noiseSigma;
    private final double[] hourly;

    LoadProfile(double share, int minBaseWatts, int maxBaseWatts, double noiseSigma, double[] hourly) {
        this.share = share;
        this.minBaseWatts = minBaseWatts;
        this.maxBaseWatts = maxBaseWatts;
        this.noiseSigma = noiseSigma;
        this.hourly = hourly;
    }

    public double share() {
        return share;
    }

    public int minBaseWatts() {
        return minBaseWatts;
    }

    public int maxBaseWatts() {
        return maxBaseWatts;
    }

    public double noiseSigma() {
        return noiseSigma;
    }

    public double shape(LocalDateTime local) {
        int h = local.getHour();
        double frac = local.getMinute() / 60.0;
        double base = hourly[h] * (1 - frac) + hourly[(h + 1) % 24] * frac;
        return base * dayFactor(local.getDayOfWeek(), h) * SEASON[local.getMonthValue() - 1];
    }

    private double dayFactor(DayOfWeek day, int hour) {
        boolean saturday = day == DayOfWeek.SATURDAY;
        boolean weekend = saturday || day == DayOfWeek.SUNDAY;
        if (!weekend) return 1.0;
        boolean daytime = hour >= 8 && hour < 19;
        return switch (this) {
            case HOUSEHOLD -> daytime ? 1.25 : 1.0;      // people are home
            case COMMERCIAL -> daytime ? (saturday ? 0.6 : 0.35) : 1.0;
            case INDUSTRIAL -> 0.55;
        };
    }
}
