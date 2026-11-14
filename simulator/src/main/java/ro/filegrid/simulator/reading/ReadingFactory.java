package ro.filegrid.simulator.reading;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import ro.filegrid.simulator.fleet.Meter;

/** Produces realistic interval values for a meter and advances its register. */
public final class ReadingFactory {

    private static final ZoneId LOCAL = ZoneId.of("Europe/Bucharest");
    /** Normal readings stay within EN 50160 limits (230 V +-10 %) with margin. */
    private static final int MIN_NORMAL_DECIV = 2_170;
    private static final int MAX_NORMAL_DECIV = 2_440;

    private final Duration interval;
    private final double intervalHours;

    public ReadingFactory(Duration interval) {
        this.interval = interval;
        this.intervalHours = interval.toSeconds() / 3600.0;
    }

    /**
     * Generates {@code count} consecutive values starting at the meter's
     * {@link Meter#coveredUntil()}. The value at {@code spikeIndex} (or none if -1)
     * gets a 20-50x consumption spike; the register stays consistent with it.
     */
    public List<MeterReading.Value> generate(Meter meter, int count, int spikeIndex, RandomGenerator rng) {
        List<MeterReading.Value> values = new ArrayList<>(count);
        Instant at = meter.coveredUntil();
        double sigma = meter.profile().noiseSigma();
        for (int i = 0; i < count; i++) {
            LocalDateTime local = LocalDateTime.ofInstant(at.plus(interval.dividedBy(2)), LOCAL);
            double shape = meter.profile().shape(local);
            double noise = Math.exp(rng.nextGaussian() * sigma - sigma * sigma / 2);
            long wh = Math.max(0, Math.round(meter.baseWatts() * shape * noise * intervalHours));
            if (i == spikeIndex) wh *= 20 + rng.nextInt(31);
            long register = meter.addEnergy(wh);
            values.add(new MeterReading.Value(at, wh, register, voltage(meter, shape, rng)));
            at = at.plus(interval);
        }
        return values;
    }

    /** Around 230 V, dipping slightly under load. */
    private static int voltage(Meter meter, double shape, RandomGenerator rng) {
        long dv = 2_300 + meter.voltageOffsetDeciV() - Math.round(20 * (shape - 1)) + Math.round(rng.nextGaussian() * 12);
        return (int) Math.clamp(dv, MIN_NORMAL_DECIV, MAX_NORMAL_DECIV);
    }
}
