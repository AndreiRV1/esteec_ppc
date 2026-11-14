package ro.ppc.filegrid.connection;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;

/**
 * HMAC-SHA256 of the applicant's CNP/CUI with a server-side secret. Lets admins
 * find all requests of one applicant (and auditors check a given person) without
 * the database ever holding the identifier. A plain hash would not do: the CNP
 * space is small enough to brute-force.
 */
@Component
public class IdentityHasher {

    private final SecretKeySpec key;

    public IdentityHasher(FileGridProperties properties) {
        this.key = new SecretKeySpec(properties.identityPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String hash(ConnectionTypes.ApplicantType type, String identityNumber) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            String input = type.name() + ":" + IdentityNumbers.normalize(identityNumber);
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
