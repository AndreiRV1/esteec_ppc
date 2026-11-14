package ro.filegrid.simulator.send;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * POSTs readings to FileGrid. Headers:
 * <ul>
 *   <li>{@code X-API-Key}: shared meter key;</li>
 *   <li>{@code Idempotency-Key}: the readingId, identical on every retry, so the
 *       server can deduplicate even a body it cannot parse;</li>
 *   <li>{@code traceparent}: W3C trace context, one trace per message, one span per attempt.</li>
 * </ul>
 */
public final class HttpTransport implements Transport {

    private final HttpClient client;
    private final URI url;
    private final String apiKey;
    private final Duration requestTimeout;

    public HttpTransport(URI url, String apiKey, Duration connectTimeout, Duration requestTimeout) {
        this.url = url;
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Override
    public Response send(OutgoingMessage message, String traceparent) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .header("Idempotency-Key", message.readingId().toString())
                .header("traceparent", traceparent)
                .header("User-Agent", "filegrid-meter-simulator/0.1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(message.body()))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        return new Response(response.statusCode(),
                response.headers().firstValue("Retry-After").flatMap(HttpTransport::parseRetryAfter));
    }

    private static Optional<Duration> parseRetryAfter(String value) {
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return Optional.empty(); // HTTP-date form: ignore, use normal backoff
        }
    }
}
