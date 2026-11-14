package ro.ppc.filegrid.connection;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;

/**
 * Accepted document formats, detected from the file's magic bytes. The
 * client-supplied Content-Type and file name are never trusted.
 */
public enum DocumentFormat {
    PDF("application/pdf", ".pdf", new byte[] {'%', 'P', 'D', 'F', '-'}),
    JPEG("image/jpeg", ".jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    PNG("image/png", ".png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

    private final String mediaType;
    private final String extension;
    private final byte[] magic;

    DocumentFormat(String mediaType, String extension, byte[] magic) {
        this.mediaType = mediaType;
        this.extension = extension;
        this.magic = magic;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<DocumentFormat> detect(InputStream in) throws IOException {
        byte[] head = in.readNBytes(8);
        for (DocumentFormat format : values()) {
            if (head.length >= format.magic.length
                    && Arrays.equals(head, 0, format.magic.length, format.magic, 0, format.magic.length)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
