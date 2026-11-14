package ro.ppc.filegrid.meter;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} for the periodic raw-reading processor. */
@Configuration
@EnableScheduling
class MeterConfig {
}
