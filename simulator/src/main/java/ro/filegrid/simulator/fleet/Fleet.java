package ro.filegrid.simulator.fleet;

import java.util.List;

/**
 * The generated meters. {@code fingerprint} identifies the exact fleet so the
 * seed file and a simulator run can be checked to match.
 */
public record Fleet(List<Meter> meters, long seed, String fingerprint) {

    public int size() {
        return meters.size();
    }
}
