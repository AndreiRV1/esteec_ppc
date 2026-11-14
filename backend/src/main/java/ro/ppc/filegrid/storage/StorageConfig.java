package ro.ppc.filegrid.storage;

import java.io.IOException;
import java.nio.file.Files;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.ppc.filegrid.config.FileGridProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(FileGridProperties properties) {
        FileGridProperties.S3 s3 = properties.storage().s3();
        return S3Client.builder()
                .endpointOverride(s3.endpoint())
                .region(Region.of(s3.region()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(s3.connectTimeout())
                        .socketTimeout(s3.socketTimeout())
                        .maxConnections(64))
                .build();
    }

    @Bean
    TempFiles tempFiles(FileGridProperties properties) throws IOException {
        return new TempFiles(Files.createDirectories(properties.storage().tempDir()));
    }
}
