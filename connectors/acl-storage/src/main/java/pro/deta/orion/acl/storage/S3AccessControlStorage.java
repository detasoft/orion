package pro.deta.orion.acl.storage;

import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;
import pro.deta.orion.util.Result;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class S3AccessControlStorage implements AccessControlStorage {
    private final OrionConfiguration.BootstrapAccessControlConfig config;
    private final S3ObjectClient client;
    private final String bucket;
    private final String prefix;

    public S3AccessControlStorage(OrionConfiguration.BootstrapAccessControlConfig config) {
        this(config, new AwsS3ObjectClient(config.getAuth()));
    }

    S3AccessControlStorage(OrionConfiguration.BootstrapAccessControlConfig config, S3ObjectClient client) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        if (config.getPaths() == null || config.getPaths().isEmpty()) {
            throw new IllegalArgumentException("At least one ACL path must be configured");
        }
        ResourceLocation location = s3Location(config.getLocation());
        this.bucket = bucket(location);
        this.prefix = prefix(location);
    }

    @Override
    public Result<AccessControlSnapshot> load() {
        try {
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (String path : config.getPaths()) {
                Optional<byte[]> content = client.readObject(bucket, objectKey(path));
                if (content.isEmpty()) {
                    return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
                }
                files.put(path, content.get());
            }
            return new Result.Success<>(new AccessControlSnapshot(files, Optional.empty()));
        } catch (RuntimeException e) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage(), e);
        }
    }

    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        try {
            for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
                client.writeObject(bucket, objectKey(entry.getKey()), entry.getValue());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RuntimeException("Cannot save ACL configuration to S3", e);
        }
    }

    @Override
    public String primaryPath() {
        return config.primaryPath();
    }

    static boolean supportsLocation(String location) {
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "ACL location");
        return resourceLocation.scheme() instanceof ResourceScheme.Other other && "s3".equals(other.value());
    }

    private String objectKey(String path) {
        String normalizedPath = normalizeObjectPath(path);
        if (prefix.isBlank()) {
            return normalizedPath;
        }
        return prefix + "/" + normalizedPath;
    }

    private static ResourceLocation s3Location(String location) {
        ResourceLocation resourceLocation = ResourceLocation.parse(location, "ACL location");
        if (!(resourceLocation.scheme() instanceof ResourceScheme.Other other) || !"s3".equals(other.value())) {
            throw new IllegalArgumentException("Unsupported S3 ACL location: " + location);
        }
        return resourceLocation;
    }

    private static String bucket(ResourceLocation location) {
        String bucket = location.host();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 ACL location must include bucket name");
        }
        return bucket;
    }

    private static String prefix(ResourceLocation location) {
        String prefix = stripLeadingSlashes(location.path());
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (prefix.isBlank()) {
            return "";
        }
        return normalizeObjectPath(prefix);
    }

    private static String normalizeObjectPath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("S3 ACL object path must not be empty");
        }
        String path = value.replace('\\', '/');
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("S3 ACL object path must be relative: " + value);
        }

        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("S3 ACL object path escapes storage prefix: " + value);
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("S3 ACL object path must not be empty");
        }
        return String.join("/", segments);
    }

    private static String stripLeadingSlashes(String value) {
        if (value == null) {
            return "";
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    interface S3ObjectClient {
        Optional<byte[]> readObject(String bucket, String key);

        void writeObject(String bucket, String key, byte[] content);
    }

    private static final class AwsS3ObjectClient implements S3ObjectClient {
        private final S3Client client;

        private AwsS3ObjectClient(Map<String, String> auth) {
            this.client = createClient(auth);
        }

        @Override
        public Optional<byte[]> readObject(String bucket, String key) {
            try {
                ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
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

        @Override
        public void writeObject(String bucket, String key, byte[] content) {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build(),
                    RequestBody.fromBytes(content));
        }

        private static S3Client createClient(Map<String, String> auth) {
            auth = Objects.requireNonNullElseGet(auth, Map::of);
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
                    throw new IllegalArgumentException("auth.accessKeyId must not be blank");
                }
                String secretAccessKey = AccessControlStorageSecret.requiredSecret(
                        "auth.secretAccessKey",
                        auth.get("secretAccessKey"));
                if (auth.containsKey("sessionToken")) {
                    String sessionToken = AccessControlStorageSecret.requiredSecret(
                            "auth.sessionToken",
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
}
