package ro.filegrid.simulator.send;

/** Final result of delivering one message. */
public enum Outcome {
    /** 2xx. */
    DELIVERED,
    /** 409: the server already had this readingId. */
    DUPLICATE,
    /** Other 4xx: the server refused it; retrying would not help. */
    REJECTED,
    /** Gave up (retry.max-attempts reached, or the simulator stopped). */
    ABANDONED
}
