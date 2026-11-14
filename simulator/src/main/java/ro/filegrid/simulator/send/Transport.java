package ro.filegrid.simulator.send;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/** Delivers one request body. Throws {@link IOException} on network failures. */
public interface Transport {

    Response send(OutgoingMessage message, String traceparent) throws IOException, InterruptedException;

    record Response(int status, Optional<Duration> retryAfter) {

        public static Response of(int status) {
            return new Response(status, Optional.empty());
        }
    }
}
