package ro.ppc.filegrid.support;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The real storage stack for integration tests, started once per JVM:
 * the same PostgreSQL image with the repo's role script and Flyway migrations,
 * MinIO, and the repo's own vsftpd image.
 */
public final class TestStorages {

    public static final Path REPO_ROOT = Path.of(System.getProperty("filegrid.repo-root", "..")).toAbsolutePath();

    public static final String MIGRATOR_PASSWORD = randomSecret();
    public static final String APP_PASSWORD = randomSecret();
    public static final String MINIO_USER = "test-root";
    public static final String MINIO_PASSWORD = randomSecret();
    public static final String FTP_USER = "filegrid";
    public static final String FTP_PASSWORD = randomSecret();
    public static final String MASTER_KEY = Base64.getEncoder().encodeToString(randomBytes(32));
    public static final List<String> BUCKETS =
            List.of("filegrid-fault-records", "filegrid-connection-documents", "filegrid-drone-videos");

    // Passive FTP ports must be bound 1:1 on the host, so they differ from the dev stack's range.
    private static final int PASV_MIN = 31100;
    private static final int PASV_MAX = 31103;

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("filegrid")
            .withUsername("postgres")
            .withPassword(randomSecret())
            .withEnv("FILEGRID_DB_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD)
            .withEnv("FILEGRID_DB_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(REPO_ROOT.resolve("infra/postgres/initdb/01-roles.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-roles.sh");

    public static final GenericContainer<?> MINIO = new GenericContainer<>(
            "cgr.dev/chainguard/minio@sha256:0f9fda72fbc6bd3560ea903aec2ceefa20f6ba82628d67656f37fb370c39ae9c")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", MINIO_USER)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    public static final GenericContainer<?> FTP = new GenericContainer<>(
            new ImageFromDockerfile("filegrid-vsftpd-test", false)
                    .withFileFromPath(".", REPO_ROOT.resolve("infra/vsftpd")))
            .withEnv("FTP_USER", FTP_USER)
            .withEnv("FTP_PASSWORD", FTP_PASSWORD)
            .withEnv("FTP_PASV_ADDRESS", dockerHostIp())
            .withEnv("FTP_PASV_MIN_PORT", String.valueOf(PASV_MIN))
            .withEnv("FTP_PASV_MAX_PORT", String.valueOf(PASV_MAX))
            .withExposedPorts(21)
            .withCreateContainerCmdModifier(cmd -> {
                List<PortBinding> bindings = new ArrayList<>(List.of(
                        new PortBinding(Ports.Binding.empty(), ExposedPort.tcp(21))));
                List<ExposedPort> exposed = new ArrayList<>(List.of(ExposedPort.tcp(21)));
                for (int port = PASV_MIN; port <= PASV_MAX; port++) {
                    bindings.add(new PortBinding(Ports.Binding.bindPort(port), ExposedPort.tcp(port)));
                    exposed.add(ExposedPort.tcp(port));
                }
                cmd.withExposedPorts(exposed);
                cmd.getHostConfig().withPortBindings(bindings);
            })
            .waitingFor(Wait.forListeningPort());

    private static boolean started;

    private TestStorages() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        POSTGRES.start();
        MINIO.start();
        FTP.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "filegrid_migrator", MIGRATOR_PASSWORD)
                .schemas("filegrid")
                .createSchemas(false)
                .locations("filesystem:" + REPO_ROOT.resolve("db/migration"))
                .load()
                .migrate();
        try (S3Client s3 = s3Client()) {
            BUCKETS.forEach(bucket -> s3.createBucket(b -> b.bucket(bucket)));
        }
        started = true;
    }

    public static void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "filegrid_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("filegrid.storage.s3.endpoint", TestStorages::minioEndpoint);
        registry.add("filegrid.storage.s3.access-key", () -> MINIO_USER);
        registry.add("filegrid.storage.s3.secret-key", () -> MINIO_PASSWORD);
        registry.add("filegrid.storage.ftp.host", FTP::getHost);
        registry.add("filegrid.storage.ftp.port", () -> FTP.getMappedPort(21));
        registry.add("filegrid.storage.ftp.username", () -> FTP_USER);
        registry.add("filegrid.storage.ftp.password", () -> FTP_PASSWORD);
        registry.add("filegrid.crypto.master-keys.kek-v1", () -> MASTER_KEY);
        registry.add("filegrid.identity-pepper", TestStorages::randomSecret);
    }

    public static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(minioEndpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO_USER, MINIO_PASSWORD)))
                .build();
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    /** vsftpd needs an IP (not a host name) for PASV replies. */
    private static String dockerHostIp() {
        String host = DockerClientFactory.instance().dockerHostIpAddress();
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Cannot resolve docker host " + host, e);
        }
    }

    private static String randomSecret() {
        return HexFormat.of().formatHex(randomBytes(24));
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
