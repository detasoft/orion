package pro.deta.orion.git.s3;

import io.minio.MinioClient;
import lombok.Getter;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.URISyntaxException;

@Getter
public class S3ClientRule {
    private final TestMode mode;
    private final S3ServerRule serverRule;
    private final AbstractClient client;

    public S3ClientRule(TestMode mode, S3ServerRule serverRule, String bucketName) {
        this.mode = mode;
        this.serverRule = serverRule;
        this.client = buildClient(mode, serverRule, bucketName, "");
    }

    private AbstractClient buildClient(TestMode mode, S3ServerRule serverRule, String bucketName, String path) {
        return switch (mode) {
            case MINIO -> {
                MinioClient m4Client = MinioClient.builder()
                        .endpoint(serverRule.getMinioURL())
                        .credentials(serverRule.getUsername(), serverRule.getPassword())
                        .build();
                yield new M4Client(bucketName, path, m4Client);
            }
            case AWS -> {
                S3Client s3Client = S3Client.builder()
                        .region(Region.US_EAST_1)
                        .endpointOverride(minioUri(serverRule))
                        .forcePathStyle(true)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                                serverRule.getUsername(),
                                serverRule.getPassword())))
                        .build();
                yield new AwsS3Client(bucketName, path, s3Client);
            }
        };
    }

    private URI minioUri(S3ServerRule serverRule) {
        try {
            return serverRule.getMinioURL().toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
