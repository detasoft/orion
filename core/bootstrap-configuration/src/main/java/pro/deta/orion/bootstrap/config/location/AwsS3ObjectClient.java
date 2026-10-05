package pro.deta.orion.bootstrap.config.location;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

final class AwsS3ObjectClient implements S3ObjectClient {
    @Override
    public Optional<byte[]> readObject(S3ConfigurationObject object) {
        try (S3Client client = createClient(object.auth())) {
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(object.bucket())
                    .key(object.key())
                    .build());
            return Optional.of(response.asByteArray());
        } catch (NoSuchBucketException | NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static S3Client createClient(Map<String, String> auth) {
        var builder = S3Client.builder()
                .region(Region.of(auth.getOrDefault("region", "us-east-1")));

        String endpoint = auth.get("endpoint");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            builder.serviceConfiguration(s3Configuration(true));
        } else if ("true".equalsIgnoreCase(auth.get("pathStyleAccess"))) {
            builder.serviceConfiguration(s3Configuration(true));
        }

        if (auth.containsKey("accessKeyId") || auth.containsKey("secretAccessKey")) {
            String accessKeyId = auth.get("accessKeyId");
            if (accessKeyId == null || accessKeyId.isBlank()) {
                throw new IllegalArgumentException("s3.accessKeyId must not be blank");
            }
            String secretAccessKey = ConfigurationLocationSecret.requiredSecret(
                    "s3.secretAccessKey",
                    auth.get("secretAccessKey"));
            if (auth.containsKey("sessionToken")) {
                String sessionToken = ConfigurationLocationSecret.requiredSecret(
                        "s3.sessionToken",
                        auth.get("sessionToken"));
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)));
            } else {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
            }
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    private static S3Configuration s3Configuration(boolean pathStyleAccess) {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyleAccess)
                .chunkedEncodingEnabled(false)
                .build();
    }
}
