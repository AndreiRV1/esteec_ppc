package ro.filegrid.simulator.reading;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ro.filegrid.simulator.fleet.Region;

/**
 * One message from a meter: a batch of interval values. Energy, register and
 * voltage are kept as integers (Wh, Wh, decivolts) so JSON decimals are exact.
 *
 * @param measuredAt end of the window the message covers (an interval boundary)
 */
public record MeterReading(
        UUID readingId,
        String meterId,
        long sequence,
        Region region,
        Instant measuredAt,
        int intervalMinutes,
        String firmware,
        List<Value> values) {

    public MeterReading {
        values = List.copyOf(values);
    }

    /**
     * @param at            start of the interval
     * @param energyWh      energy consumed during the interval
     * @param registerWh    cumulative register (meter index) at the end of the interval
     * @param voltageDeciV  average voltage, in tenths of a volt
     */
    public record Value(Instant at, long energyWh, long registerWh, int voltageDeciV) {

        public Value shifted(Duration by) {
            return new Value(at.plus(by), energyWh, registerWh, voltageDeciV);
        }
    }

    public MeterReading withIdentity(UUID readingId, long sequence) {
        return new MeterReading(readingId, meterId, sequence, region, measuredAt, intervalMinutes, firmware, values);
    }

    public MeterReading withMeter(String meterId, Region region) {
        return new MeterReading(readingId, meterId, sequence, region, measuredAt, intervalMinutes, firmware, values);
    }

    public MeterReading withValues(List<Value> values) {
        return new MeterReading(readingId, meterId, sequence, region, measuredAt, intervalMinutes, firmware, values);
    }

    /** Moves every timestamp (clock drift, misalignment). */
    public MeterReading shifted(Duration by) {
        return new MeterReading(readingId, meterId, sequence, region, measuredAt.plus(by), intervalMinutes, firmware,
                values.stream().map(v -> v.shifted(by)).toList());
    }
}
