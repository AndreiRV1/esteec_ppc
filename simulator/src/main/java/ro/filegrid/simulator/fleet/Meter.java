package ro.filegrid.simulator.fleet;

import java.time.Instant;

/**
 * One simulated smart meter: fixed identity plus the device state that evolves
 * as it reports. Mutable state is touched only by the engine thread.
 */
public final class Meter {

    private final String id;
    private final Region region;
    private final Region.Locality locality;
    private final double latitude;
    private final double longitude;
    private final LoadProfile profile;
    private final int baseWatts;
    private final int voltageOffsetDeciV;
    private final String firmware;

    private long sequence;
    private long registerWh;
    private Instant coveredUntil;
    private Instant nextBoundary;
    private Instant dueAt;

    Meter(String id, Region region, Region.Locality locality, double latitude, double longitude,
          LoadProfile profile, int baseWatts, int voltageOffsetDeciV, String firmware,
          long sequence, long registerWh) {
        this.id = id;
        this.region = region;
        this.locality = locality;
        this.latitude = latitude;
        this.longitude = longitude;
        this.profile = profile;
        this.baseWatts = baseWatts;
        this.voltageOffsetDeciV = voltageOffsetDeciV;
        this.firmware = firmware;
        this.sequence = sequence;
        this.registerWh = registerWh;
    }

    public String id() { return id; }
    public Region region() { return region; }
    public Region.Locality locality() { return locality; }
    public double latitude() { return latitude; }
    public double longitude() { return longitude; }
    public LoadProfile profile() { return profile; }
    public int baseWatts() { return baseWatts; }
    public int voltageOffsetDeciV() { return voltageOffsetDeciV; }
    public String firmware() { return firmware; }

    public long sequence() { return sequence; }
    public long registerWh() { return registerWh; }
    /** End of the last interval already reported (start of the next one to report). */
    public Instant coveredUntil() { return coveredUntil; }
    /** Interval boundary at which the next report closes. */
    public Instant nextBoundary() { return nextBoundary; }
    /** Simulated time at which the next report is sent. */
    public Instant dueAt() { return dueAt; }

    public long nextSequence() {
        return ++sequence;
    }

    /** Simulates a reboot: the message counter starts over. */
    public void resetSequence() {
        sequence = 0;
    }

    /** Adds consumed energy to the cumulative register and returns the new index. */
    public long addEnergy(long wh) {
        registerWh += wh;
        return registerWh;
    }

    public void schedule(Instant coveredUntil, Instant nextBoundary, Instant dueAt) {
        this.coveredUntil = coveredUntil;
        this.nextBoundary = nextBoundary;
        this.dueAt = dueAt;
    }
}
