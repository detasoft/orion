package pro.deta.orion.test.integration.s3;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public final class MinioS3TestServer implements AutoCloseable {
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse("quay.io/minio/minio");
    private static final int MINIO_API_PORT = 9000;
    private static final String USERNAME = "orion4test";
    private static final String PASSWORD = "orion4test";

    private final GenericContainer<?> minio;
    private final String bucketName;
    private final S3Client client;

    private MinioS3TestServer(GenericContainer<?> minio, String bucketName, S3Client client) {
        this.minio = minio;
        this.bucketName = bucketName;
        this.client = client;
    }

    public static MinioS3TestServer start(String bucketName) {
        GenericContainer<?> minio = new GenericContainer<>(MINIO_IMAGE)
                .withEnv("MINIO_ROOT_USER", USERNAME)
                .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
                .withCommand("server /data")
                .withReuse(true)
                .withExposedPorts(MINIO_API_PORT);
        minio.start();

        S3Client client = client(endpoint(minio));
        createBucket(client, bucketName);
        return new MinioS3TestServer(minio, bucketName, client);
    }

    public String bucketName() {
        return bucketName;
    }

    public String endpoint() {
        return endpoint(minio);
    }

    public String host() {
        return minio.getHost();
    }

    public int port() {
        return minio.getMappedPort(MINIO_API_PORT);
    }

    public String accessKeyId() {
        return USERNAME;
    }

    public String secretAccessKey() {
        return PASSWORD;
    }

    public void putObject(String key, byte[] content) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build(),
                RequestBody.fromBytes(content));
    }

    public byte[] getObject(String key) {
        ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build());
        return response.asByteArray();
    }

    @Override
    public void close() {
        try {
            deleteBucketContent();
            client.deleteBucket(DeleteBucketRequest.builder()
                    .bucket(bucketName)
                    .build());
        } catch (S3Exception ignored) {
        } finally {
            client.close();
            minio.stop();
        }
    }

    private void deleteBucketContent() {
        List<S3Object> objects = new ArrayList<>();
        var request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .build();
        for (var page : client.listObjectsV2Paginator(request)) {
            objects.addAll(page.contents());
        }
        for (S3Object object : objects) {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(object.key())
                    .build());
        }
    }

    private static S3Client client(String endpoint) {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        USERNAME,
                        PASSWORD)))
                .build();
    }

    private static void createBucket(S3Client client, String bucketName) {
        try {
            client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build());
        } catch (S3Exception e) {
            if (!"BucketAlreadyOwnedByYou".equals(e.awsErrorDetails().errorCode())) {
                throw e;
            }
        }
    }

    private static String endpoint(GenericContainer<?> minio) {
        return "http://" + minio.getHost() + ":" + minio.getMappedPort(MINIO_API_PORT);
    }
}
