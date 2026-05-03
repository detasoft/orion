package pro.deta.orion.git.s3;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

@Getter
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3ServerRule implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback {
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse("quay.io/minio/minio");
    private static final int MINIO_API_PORT = 9000;

    private final String username = "orion4test";
    private final String password = "orion4test";

    @Container
    private GenericContainer<?> minio;
    private S3ClientRule clientRule;
    private final TestMode testMode;
    private final String bucketName;
    private ServerSide serverSide;

    public S3ServerRule() {
        this.testMode = TestMode.AWS;
        this.bucketName = "orion-s3test";
    }

    public @NotNull URL getMinioURL() {
        serverSide = new ServerSide(minio.getHost(), minio.getFirstMappedPort());

        try {
            return URI.create("http://" + serverSide.host + ":" + serverSide.port).toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        minio = new GenericContainer<>(MINIO_IMAGE)
                .withEnv("MINIO_ROOT_USER", username)
                .withEnv("MINIO_ROOT_PASSWORD", password)
                .withCommand("server /data")
                .withReuse(true)
                .withExposedPorts(MINIO_API_PORT);
        minio.start();

        clientRule = new S3ClientRule(testMode, this, bucketName);
        clientRule.getClient().createBucket();
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Object testInstance = context.getTestInstance().get();

        if (testInstance instanceof ServerSideAware test) {
            test.setServerSide(serverSide);
        }
        if (testInstance instanceof AbstractClientAware test) {
            test.setAbstractClient(clientRule.getClient());
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        clientRule.getClient().removeBucket();
    }

    public interface ServerSideAware {
        void setServerSide(ServerSide serverSide);
    }

    public interface AbstractClientAware {
        void setAbstractClient(AbstractClient abstractClient);
    }

    @RequiredArgsConstructor
    @Getter
    public static class ServerSide {
        private final String host;
        private final int port;
    }
}
