package ro.filegrid.simulator.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simulated time: starts at a configurable instant and runs {@code speed} times
 * faster than the wall clock. Unless future timestamps are allowed, it never
 * passes the wall clock: once it catches up it continues in real time.
 */
public final class SimClock {

    private static final Logger log = LoggerFactory.getLogger(SimClock.class);

    private final Clock wall;
    private final boolean allowFuture;
    private Instant anchorSim;
    private Instant anchorWall;
    private double speed;
    private boolean caughtUp;

    public SimClock(Clock wall, Instant start, double speed, boolean allowFuture) {
        this.wall = wall;
        this.allowFuture = allowFuture;
        this.anchorSim = start;
        this.anchorWall = wall.instant();
        this.speed = speed;
    }

    /** "now", an ISO instant ("2026-11-01T00:00:00Z"), or an offset from now ("-P1D", "-PT6H"). */
    public static Instant parseStart(String spec, Clock wall) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("now")) return wall.instant();
        String s = spec.trim();
        try {
            if (s.startsWith("-") || s.startsWith("+") || s.startsWith("P")) return wall.instant().plus(Duration.parse(s));
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("clock.start must be 'now', an ISO instant or an ISO duration: " + spec, e);
        }
    }

    public synchronized Instant now() {
        Instant w = wall.instant();
        long elapsed = Duration.between(anchorWall, w).toNanos();
        Instant sim = anchorSim.plusNanos((long) (elapsed * speed));
        if (!allowFuture && sim.isAfter(w)) {
            if (!caughtUp) log.info("Simulated time caught up with the wall clock; continuing in real time");
            caughtUp = true;
            anchorSim = w;
            anchorWall = w;
            return w;
        }
        caughtUp = false;
        return sim;
    }

    public synchronized double speed() {
        return speed;
    }

    /** Speed actually in effect (1 once clamped to the wall clock). */
    public synchronized double effectiveSpeed() {
        return caughtUp ? Math.min(speed, 1.0) : speed;
    }

    public synchronized void setSpeed(double newSpeed) {
        if (!(newSpeed > 0)) throw new IllegalArgumentException("speed must be > 0");
        anchorSim = now();
        anchorWall = wall.instant();
        speed = newSpeed;
    }
}
