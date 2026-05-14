package pro.deta.orion.git.s3;

import lombok.Getter;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Getter
public class S3ClientRule {
    private final AbstractClient client;

    public S3ClientRule(MinioS3TestServer server, String bucketName) {
        this.client = buildClient(server, bucketName, "");
    }

    private AbstractClient buildClient(MinioS3TestServer server, String bucketName, String path) {
        S3Client s3Client = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(server.endpoint()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        server.accessKeyId(),
                        server.secretAccessKey())))
                .build();
        return new AwsS3Client(bucketName, path, s3Client);
    }
}
