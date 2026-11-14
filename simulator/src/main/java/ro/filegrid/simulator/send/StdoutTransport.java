package ro.filegrid.simulator.send;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Prints each body as one JSON line (sink.type=stdout); for trying the simulator without a backend. */
public final class StdoutTransport implements Transport {

    private final PrintStream out;

    public StdoutTransport(PrintStream out) {
        this.out = out;
    }

    @Override
    public Response send(OutgoingMessage message, String traceparent) {
        String line = new String(message.body(), StandardCharsets.UTF_8);
        synchronized (out) {
            out.println(line);
        }
        return Response.of(202);
    }
}
