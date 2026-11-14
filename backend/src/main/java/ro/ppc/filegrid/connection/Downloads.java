package ro.ppc.filegrid.connection;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Streams a verified temp file to the client and deletes it afterwards. */
final class Downloads {

    private Downloads() {
    }

    /**
     * Sends the file with its checksum, so the receiver can verify it too:
     * {@code Repr-Digest} (RFC 9530) and the same value in hex as {@code X-Checksum-SHA256}.
     */
    static ResponseEntity<StreamingResponseBody> stream(Path file, String contentType, String filename,
                                                        String sha256Hex, AutoCloseable cleanup) {
        try {
            long size = Files.size(file);
            String base64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex));
            StreamingResponseBody body = out -> {
                try (AutoCloseable ignored = cleanup; InputStream in = Files.newInputStream(file)) {
                    in.transferTo(out);
                } catch (java.io.IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(size)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename).build().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("Repr-Digest", "sha-256=:" + base64 + ":")
                    .header("X-Checksum-SHA256", sha256Hex)
                    .body(body);
        } catch (Exception e) {
            closeQuietly(cleanup);
            throw new IllegalStateException("Could not prepare download", e);
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
