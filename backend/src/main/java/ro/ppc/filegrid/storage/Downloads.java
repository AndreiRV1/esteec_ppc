package ro.ppc.filegrid.storage;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ro.ppc.filegrid.common.ApiException;

/** Streams a verified temp file to the client and deletes it afterwards. */
public final class Downloads {

    private Downloads() {
    }

    /**
     * Sends the file with its checksum, so the receiver can verify it too:
     * {@code Repr-Digest} (RFC 9530) and the same value in hex as {@code X-Checksum-SHA256}.
     */
    public static ResponseEntity<StreamingResponseBody> stream(Path file, String contentType, String filename,
                                                               String sha256Hex, AutoCloseable cleanup) {
        return stream(file, contentType, filename, sha256Hex, cleanup, null);
    }

    /**
     * Same, honouring a single-range {@code Range} header (206). The checksum headers
     * always describe the whole file, which was verified before the first byte is sent.
     */
    public static ResponseEntity<StreamingResponseBody> stream(Path file, String contentType, String filename,
                                                               String sha256Hex, AutoCloseable cleanup,
                                                               String rangeHeader) {
        try {
            long size = Files.size(file);
            long start = 0;
            long end = size - 1;
            boolean partial = false;
            if (rangeHeader != null && !rangeHeader.isBlank() && size > 0) {
                List<HttpRange> ranges;
                try {
                    ranges = HttpRange.parseRanges(rangeHeader);
                    if (ranges.size() == 1) {
                        start = ranges.getFirst().getRangeStart(size);
                        end = ranges.getFirst().getRangeEnd(size);
                        partial = true;
                    }
                } catch (IllegalArgumentException e) {
                    throw new ApiException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "invalid_range",
                            "Range cannot be satisfied for a file of " + size + " bytes");
                }
            }
            long from = start;
            long length = end - start + 1;
            String base64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex));
            StreamingResponseBody body = out -> {
                try (AutoCloseable ignored = cleanup; InputStream in = Files.newInputStream(file)) {
                    in.skipNBytes(from);
                    copy(in, out, length);
                } catch (java.io.IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
            };
            ResponseEntity.BodyBuilder response = ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(length)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename).build().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header("Repr-Digest", "sha-256=:" + base64 + ":")
                    .header("X-Checksum-SHA256", sha256Hex);
            if (partial) {
                response.header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
            }
            return response.body(body);
        } catch (ApiException e) {
            closeQuietly(cleanup);
            throw e;
        } catch (Exception e) {
            closeQuietly(cleanup);
            throw new IllegalStateException("Could not prepare download", e);
        }
    }

    private static void copy(InputStream in, OutputStream out, long length) throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
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
