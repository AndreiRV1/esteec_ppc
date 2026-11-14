package ro.ppc.filegrid.meter;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param apiKey          shared key the meters send in {@code X-API-Key}
 * @param processInterval pause between two runs of the raw-reading processor
 * @param batchSize       raw messages processed per transaction
 */
@Validated
@ConfigurationProperties("filegrid.meters")
public record MeterProperties(@NotBlank String apiKey, @NotNull Duration processInterval, @Min(1) int batchSize) {
}
