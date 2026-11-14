package ro.ppc.filegrid.crypto;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;

/**
 * Envelope encryption for stored files.
 *
 * <ul>
 *   <li>Every file gets its own data key (DEK): a Tink AES-256-GCM-HKDF streaming
 *       keyset, so files of any size are encrypted and decrypted in 1 MB segments
 *       with constant memory, and every segment is authenticated.</li>
 *   <li>The DEK is wrapped with a master key (KEK, AES-256-GCM) that only exists in
 *       the backend's environment. Storages and the database only ever see the
 *       ciphertext and the wrapped DEK.</li>
 *   <li>The file id is bound as associated data, so a ciphertext cannot be swapped
 *       onto another record.</li>
 * </ul>
 * Deleting a file's wrapped DEK makes the file unrecoverable (crypto-shredding),
 * which is how a GDPR erasure can be honoured even for versioned/backup copies.
 */
@Component
public class EnvelopeEncryption {

    /** Value stored in {@code stored_file.encryption}. */
    public static final String ALGORITHM = "AES_256_GCM";

    private static final int KEK_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKey> masterKeys = new HashMap<>();
    private final String activeKeyId;

    public EnvelopeEncryption(FileGridProperties properties) throws GeneralSecurityException {
        StreamingAeadConfig.register();
        FileGridProperties.Crypto crypto = properties.crypto();
        crypto.masterKeys().forEach((id, encoded) -> masterKeys.put(id, decodeMasterKey(id, encoded)));
        if (!masterKeys.containsKey(crypto.activeKeyId())) {
            throw new IllegalStateException("filegrid.crypto.active-key-id '" + crypto.activeKeyId()
                    + "' has no matching entry in filegrid.crypto.master-keys");
        }
        this.activeKeyId = crypto.activeKeyId();
    }

    /** A fresh data key, already wrapped with the active master key. */
    public DataKey newDataKey() {
        try {
            KeysetHandle keyset = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB);
            byte[] serialized = TinkProtoKeysetFormat.serializeKeyset(keyset, InsecureSecretKeyAccess.get());
            try {
                return new DataKey(activeKeyId, wrap(activeKeyId, serialized), primitive(keyset));
            } finally {
                Arrays.fill(serialized, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate a data key", e);
        }
    }

    /** Unwraps a stored data key. Fails if the master key is unknown or the wrapped key was tampered with. */
    public StreamingAead unwrap(String keyId, byte[] wrappedKey) throws GeneralSecurityException {
        byte[] serialized = unwrapBytes(keyId, wrappedKey);
        try {
            return primitive(TinkProtoKeysetFormat.parseKeyset(serialized, InsecureSecretKeyAccess.get()));
        } finally {
            Arrays.fill(serialized, (byte) 0);
        }
    }

    /** Associated data binding a ciphertext to its file record. */
    public static byte[] associatedData(UUID fileId) {
        return ("filegrid:file:" + fileId).getBytes(StandardCharsets.UTF_8);
    }

    private static StreamingAead primitive(KeysetHandle keyset) throws GeneralSecurityException {
        return keyset.getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
    }

    private byte[] wrap(String keyId, byte[] plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, masterKeys.get(keyId), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(kekAad(keyId));
        byte[] ciphertext = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(IV_BYTES + ciphertext.length).put(iv).put(ciphertext).array();
    }

    private byte[] unwrapBytes(String keyId, byte[] wrapped) throws GeneralSecurityException {
        SecretKey kek = masterKeys.get(keyId);
        if (kek == null) {
            throw new GeneralSecurityException("Unknown master key id: " + keyId);
        }
        if (wrapped == null || wrapped.length <= IV_BYTES) {
            throw new GeneralSecurityException("Wrapped key is truncated");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, wrapped, 0, IV_BYTES));
        cipher.updateAAD(kekAad(keyId));
        return cipher.doFinal(wrapped, IV_BYTES, wrapped.length - IV_BYTES);
    }

    private static byte[] kekAad(String keyId) {
        return ("filegrid:dek:" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private static SecretKey decodeMasterKey(String id, String encoded) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Master key '" + id + "' is not valid base64", e);
        }
        if (raw.length != KEK_BYTES) {
            throw new IllegalStateException("Master key '" + id + "' must be 32 bytes, got " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    /** A per-file data key: the wrapped form is persisted, the primitive is used to encrypt. */
    public record DataKey(String keyId, byte[] wrappedKey, StreamingAead aead) {
    }
}
