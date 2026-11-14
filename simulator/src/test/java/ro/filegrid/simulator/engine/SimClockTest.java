package ro.filegrid.simulator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class SimClockTest {

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final Instant WALL = Instant.parse("2026-11-14T12:00:00Z");

    @Test
    void parsesStart() {
        Clock wall = Clock.fixed(WALL, ZoneOffset.UTC);
        assertThat(SimClock.parseStart("now", wall)).isEqualTo(WALL);
        assertThat(SimClock.parseStart("", wall)).isEqualTo(WALL);
        assertThat(SimClock.parseStart("-P1D", wall)).isEqualTo(WALL.minus(Duration.ofDays(1)));
        assertThat(SimClock.parseStart("-PT6H", wall)).isEqualTo(WALL.minus(Duration.ofHours(6)));
        assertThat(SimClock.parseStart("2026-11-01T00:00:00Z", wall)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThatThrownBy(() -> SimClock.parseStart("yesterday", wall)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runsFasterAndClampsToTheWallClock() {
        MutableClock wall = new MutableClock(WALL);
        SimClock clock = new SimClock(wall, WALL.minus(Duration.ofHours(1)), 10, false);

        wall.advance(Duration.ofMinutes(3));
        assertThat(clock.now()).isEqualTo(WALL.minus(Duration.ofHours(1)).plus(Duration.ofMinutes(30)));
        assertThat(clock.effectiveSpeed()).isEqualTo(10);

        wall.advance(Duration.ofMinutes(10)); // would be 40 min ahead of the wall clock
        assertThat(clock.now()).isEqualTo(wall.now);
        assertThat(clock.effectiveSpeed()).isEqualTo(1);

        wall.advance(Duration.ofMinutes(1));
        assertThat(clock.now()).isEqualTo(wall.now);
    }

    @Test
    void allowFutureLetsItRunAhead() {
        MutableClock wall = new MutableClock(WALL);
        SimClock clock = new SimClock(wall, WALL, 2, true);
        wall.advance(Duration.ofMinutes(10));
        assertThat(clock.now()).isEqualTo(WALL.plus(Duration.ofMinutes(20)));
    }

    @Test
    void speedChangeKeepsTimeContinuous() {
        MutableClock wall = new MutableClock(WALL);
        SimClock clock = new SimClock(wall, WALL.minus(Duration.ofDays(1)), 1, false);
        wall.advance(Duration.ofMinutes(10));
        Instant before = clock.now();

        clock.setSpeed(6);
        wall.advance(Duration.ofMinutes(10));

        assertThat(clock.now()).isEqualTo(before.plus(Duration.ofMinutes(60)));
    }
}
