package pro.deta.orion.git.s3;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.*;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;

@Getter
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3ServerRule implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback {
    private S3ClientRule clientRule;
    private final String bucketName;
    private ServerSide serverSide;
    private MinioS3TestServer minio;

    public S3ServerRule() {
        this.bucketName = "orion-s3test";
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        minio = MinioS3TestServer.start(bucketName);
        serverSide = new ServerSide(minio.host(), minio.port());
        clientRule = new S3ClientRule(minio, bucketName);
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
        if (minio != null) {
            minio.close();
        }
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
