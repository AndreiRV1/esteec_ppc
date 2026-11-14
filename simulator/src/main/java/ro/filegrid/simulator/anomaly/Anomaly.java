package ro.filegrid.simulator.anomaly;

/**
 * Faults the simulator can inject, each with its own probability per message
 * ({@code simulator.anomalies.rates.<kebab-name>}).
 */
public enum Anomaly {

    // --- delivery ---------------------------------------------------------------
    /** The exact same message (same readingId, same bytes) is sent again later. */
    DUPLICATE,
    /** The exact same message is sent twice at the same time (concurrent retry). */
    RACE_DUPLICATE,
    /** The same intervals are sent again later under a new readingId and sequence. */
    RESENT_INTERVALS,
    /** The message is held back and arrives after newer ones (past timestamps, out of order). */
    LATE,

    // --- timestamps and continuity ---------------------------------------------
    /** All timestamps are shifted 1-48 h into the future (clock drift). */
    FUTURE_TIMESTAMP,
    /** All timestamps are shifted a few seconds off the interval grid. */
    MISALIGNED_TIMESTAMP,
    /** Some intervals are missing from the batch; the register still includes their energy. */
    GAP,
    /** The meter rebooted: its sequence counter restarts at 1. */
    SEQUENCE_RESET,

    // --- payload ---------------------------------------------------------------
    /** Truncated JSON, a missing required field, a wrong type, or an empty values array. */
    MALFORMED,
    /** Negative energy, or a register lower than before (rollback). */
    INVALID_VALUE,
    /** A meter id that is not in the seed file. */
    UNKNOWN_METER,
    /** A region that does not match the meter. */
    WRONG_REGION,

    // --- real grid events (valid data that should be flagged, not rejected) -----
    /** Voltage sag (180-206 V) or swell (254-270 V) on one or more intervals. */
    VOLTAGE_OUT_OF_RANGE,
    /** One interval with 20-50x normal consumption. */
    CONSUMPTION_SPIKE
}
