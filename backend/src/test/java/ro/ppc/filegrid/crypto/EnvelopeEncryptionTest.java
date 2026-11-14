package ro.ppc.filegrid.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.crypto.tink.StreamingAead;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.ppc.filegrid.config.FileGridProperties;

class EnvelopeEncryptionTest {

    private static final String KEY_V1 = randomKey();
    private static final String KEY_V2 = randomKey();

    @Test
    void roundTripsMultiSegmentContent() throws Exception {
        EnvelopeEncryption enc = encryption("kek-v1", Map.of("kek-v1", KEY_V1));
        byte[] plain = randomBytes(3 * 1024 * 1024 + 17); // > 1 MB segments
        UUID fileId = UUID.randomUUID();

        EnvelopeEncryption.DataKey key = enc.newDataKey();
        byte[] cipher = encrypt(key.aead(), plain, fileId);

        assertThat(cipher).isNotEqualTo(plain);
        assertThat(decrypt(enc.unwrap(key.keyId(), key.wrappedKey()), cipher, fileId)).isEqualTo(plain);
    }

    @Test
    void detectsTamperedCiphertext() throws Exception {
        EnvelopeEncryption enc = encryption("kek-v1", Map.of("kek-v1", KEY_V1));
        UUID fileId = UUID.randomUUID();
        EnvelopeEncryption.DataKey key = enc.newDataKey();
        byte[] cipher = encrypt(key.aead(), randomBytes(10_000), fileId);
        cipher[cipher.length / 2] ^= 1;

        assertThatThrownBy(() -> decrypt(key.aead(), cipher, fileId)).isInstanceOf(IOException.class);
    }

    @Test
    void ciphertextIsBoundToItsFileId() throws Exception {
        EnvelopeEncryption enc = encryption("kek-v1", Map.of("kek-v1", KEY_V1));
        EnvelopeEncryption.DataKey key = enc.newDataKey();
        byte[] cipher = encrypt(key.aead(), randomBytes(1000), UUID.randomUUID());

        assertThatThrownBy(() -> decrypt(key.aead(), cipher, UUID.randomUUID())).isInstanceOf(IOException.class);
    }

    @Test
    void oldFilesStayReadableAfterMasterKeyRotation() throws Exception {
        EnvelopeEncryption before = encryption("kek-v1", Map.of("kek-v1", KEY_V1));
        EnvelopeEncryption.DataKey oldKey = before.newDataKey();
        UUID fileId = UUID.randomUUID();
        byte[] plain = randomBytes(5000);
        byte[] cipher = encrypt(oldKey.aead(), plain, fileId);

        EnvelopeEncryption after = encryption("kek-v2", Map.of("kek-v1", KEY_V1, "kek-v2", KEY_V2));

        assertThat(after.newDataKey().keyId()).isEqualTo("kek-v2");
        assertThat(decrypt(after.unwrap("kek-v1", oldKey.wrappedKey()), cipher, fileId)).isEqualTo(plain);
    }

    @Test
    void wrappedKeyNeedsTheRightMasterKey() {
        EnvelopeEncryption enc = encryption("kek-v1", Map.of("kek-v1", KEY_V1));
        EnvelopeEncryption other = encryption("kek-v1", Map.of("kek-v1", KEY_V2));
        byte[] wrapped = enc.newDataKey().wrappedKey();

        assertThatThrownBy(() -> other.unwrap("kek-v1", wrapped)).isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> enc.unwrap("kek-v9", wrapped)).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void rejectsMisconfiguredMasterKeys() {
        assertThatThrownBy(() -> encryption("kek-v1", Map.of("kek-v1", "dG9vLXNob3J0")))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> encryption("kek-v2", Map.of("kek-v1", KEY_V1)))
                .hasMessageContaining("active-key-id");
    }

    private static byte[] encrypt(StreamingAead aead, byte[] plain, UUID fileId) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream enc = aead.newEncryptingStream(out, EnvelopeEncryption.associatedData(fileId))) {
            enc.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] decrypt(StreamingAead aead, byte[] cipher, UUID fileId) throws Exception {
        try (InputStream in = aead.newDecryptingStream(new ByteArrayInputStream(cipher),
                EnvelopeEncryption.associatedData(fileId))) {
            return in.readAllBytes();
        }
    }

    private static EnvelopeEncryption encryption(String active, Map<String, String> keys) {
        FileGridProperties props = new FileGridProperties(
                new FileGridProperties.Storage(Path.of("/tmp"),
                        new FileGridProperties.S3(java.net.URI.create("http://x"), "r", "a", "s",
                                Duration.ofSeconds(1), Duration.ofSeconds(1)),
                        new FileGridProperties.Ftp("h", 21, "u", "p", Duration.ofSeconds(1), Duration.ofSeconds(1))),
                new FileGridProperties.Crypto(active, keys),
                "pepper");
        try {
            return new EnvelopeEncryption(props);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomKey() {
        return Base64.getEncoder().encodeToString(randomBytes(32));
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
