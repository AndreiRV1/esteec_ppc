package ro.ppc.filegrid.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("filegrid")
public record FileGridProperties(
        @NotNull @Valid Storage storage,
        @NotNull @Valid Crypto crypto,
        /* Secret for HMAC-ing CNP/CUI; rotating it breaks lookups of older requests. */
        @NotBlank String identityPepper) {

    public record Storage(@NotNull Path tempDir, @NotNull @Valid S3 s3, @NotNull @Valid Ftp ftp) {
    }

    public record S3(
            @NotNull URI endpoint,
            @NotBlank String region,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            @NotNull Duration connectTimeout,
            @NotNull Duration socketTimeout) {
    }

    public record Ftp(
            @NotBlank String host,
            int port,
            @NotBlank String username,
            @NotBlank String password,
            @NotNull Duration connectTimeout,
            @NotNull Duration dataTimeout) {
    }

    /**
     * Master keys (KEKs) that wrap per-file data keys. Several can be listed so
     * old files stay readable after rotation; new files use {@code activeKeyId}.
     * Values are base64-encoded 32-byte keys and come from the environment only.
     */
    public record Crypto(@NotBlank String activeKeyId, @NotEmpty Map<String, String> masterKeys) {
    }
}
