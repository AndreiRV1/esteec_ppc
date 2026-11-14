package ro.ppc.filegrid.common;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/** RFC 9562 version-7 UUIDs: time-ordered, so they index well (same as PostgreSQL's uuidv7()). */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        long millis = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            b[i] = (byte) (millis >>> (40 - 8 * i));
        }
        b[6] = (byte) ((b[6] & 0x0F) | 0x70); // version 7
        b[8] = (byte) ((b[8] & 0x3F) | 0x80); // IETF variant
        ByteBuffer buf = ByteBuffer.wrap(b);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
