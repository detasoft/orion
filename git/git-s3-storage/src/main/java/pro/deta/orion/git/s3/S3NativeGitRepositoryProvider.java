package pro.deta.orion.git.s3;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.util.Result;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S3 owns repository metadata; each lookup reads it afresh. Standalone providers own their connection;
 * shared providers borrow their connection and closing them does not close it. Repository handles own no S3
 * resources. Pack bytes, index manifests and refs are persisted independently in S3.
 */
public final class S3NativeGitRepositoryProvider implements NativeGitRepositoryProvider {
    private final S3NativeGitRepositoryFactory factory;
    private final S3Transport owner;
    private final boolean ownsConnection;
    private final AtomicBoolean closed = new AtomicBoolean();

    S3NativeGitRepositoryProvider(String location, S3Transport owner,
            AwsRequestOverrideConfiguration overrides) {
        this(parseLocation(location), owner, overrides, false);
    }

    private S3NativeGitRepositoryProvider(Location location, S3Transport owner,
            AwsRequestOverrideConfiguration overrides, boolean ownsConnection) {
        this.owner = Objects.requireNonNull(owner, "client owner");
        this.ownsConnection = ownsConnection;
        factory = new S3NativeGitRepositoryFactory(location.bucket(), location.prefix(), owner, overrides);
    }

    public S3NativeGitRepositoryProvider(String location, String endpoint, Map<String, String> auth,
            Map<String, String> environment) {
        this(parseLocation(location), standalone(endpoint, auth, environment));
    }

    private S3NativeGitRepositoryProvider(Location location, Standalone standalone) {
        this(location, standalone.owner(), standalone.overrides(), true);
    }

    private record Standalone(S3Transport owner, AwsRequestOverrideConfiguration overrides) {}

    private record Location(String bucket, String prefix) {}

    public static void validateLocation(String location) {
        parseLocation(location);
    }

    private static Location parseLocation(String location) {
        URI uri = uri(location, "storage location");
        String host = uri.getHost();
        if (!"s3".equalsIgnoreCase(uri.getScheme()) || host == null
                || !host.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || host.contains("..") || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("S3 storage location must be s3://bucket/prefix");
        }

        String path = uri.getPath();
        String configuredPrefix = path == null || path.isEmpty() ? "" : path.substring(1);
        if (configuredPrefix.startsWith("/")) {
            throw new IllegalArgumentException("Invalid S3 repository prefix");
        }
        if (configuredPrefix.endsWith("/")) {
            configuredPrefix = configuredPrefix.substring(0, configuredPrefix.length() - 1);
        }
        if (!configuredPrefix.isEmpty()) {
            for (String segment : configuredPrefix.split("/", -1)) {
                if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                        || segment.contains("\\") || segment.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Invalid S3 repository prefix");
                }
            }
        }
        String prefix = configuredPrefix.isEmpty() ? "" : configuredPrefix + "/";
        if ((prefix + "0".repeat(64) + "/indexes/" + "0".repeat(36) + ".index").getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new IllegalArgumentException("S3 repository prefix is too long");
        }
        return new Location(host, prefix);
    }

    private static Standalone standalone(String endpoint, Map<String, String> auth, Map<String, String> environment) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(environment, "environment");
        String pathStyle = auth.getOrDefault("pathStyleAccess", "false");
        if (!"true".equalsIgnoreCase(pathStyle) && !"false".equalsIgnoreCase(pathStyle)) {
            throw new IllegalArgumentException("S3 pathStyleAccess must be true or false");
        }
        Optional<AwsCredentialsProvider> credentials = credentials(auth, environment);
        S3Transport owner = new S3Transport();
        try {
            return new Standalone(owner, owner.overrides(endpoint, auth.getOrDefault("region", "us-east-1"),
                    Boolean.parseBoolean(pathStyle), credentials));
        } catch (RuntimeException failure) {
            owner.close();
            throw failure;
        }
    }

    @Override
    public List<String> repositoryNames() {
        return owner.operation(() -> {
            requireOpen();
            return factory.repositoryNames();
        });
    }

    @Override
    public boolean exists(String repositoryName) {
        return owner.operation(() -> {
            requireOpen();
            return factory.exists(RepositoryName.parse(repositoryName));
        });
    }

    @Override
    public Result<NativeGitRepository> find(String repositoryName) {
        return owner.operation(() -> {
            requireOpen();
            return factory.open(RepositoryName.parse(repositoryName));
        });
    }

    @Override
    public Result<NativeGitRepository> create(String repositoryName) {
        return owner.operation(() -> {
            requireOpen();
            return factory.create(RepositoryName.parse(repositoryName));
        });
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("S3 repository provider is closed");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsConnection) owner.close();
    }

    private static URI uri(String value, String label) {
        try {
            return URI.create(Objects.requireNonNull(value));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalArgumentException("Invalid S3 " + label);
        }
    }

    private static Optional<AwsCredentialsProvider> credentials(
            Map<String, String> auth, Map<String, String> environment) {
        if (!auth.containsKey("accessKeyId") && !auth.containsKey("secretAccessKey")
                && !auth.containsKey("sessionToken")) {
            return Optional.empty();
        }
        String accessKey = auth.get("accessKeyId");
        if (accessKey == null || accessKey.isBlank()) {
            throw new IllegalArgumentException("S3 accessKeyId must not be blank");
        }
        String secret = secret(auth.get("secretAccessKey"), environment);
        return Optional.of(StaticCredentialsProvider.create(auth.containsKey("sessionToken")
                ? AwsSessionCredentials.create(accessKey, secret, secret(auth.get("sessionToken"), environment))
                : AwsBasicCredentials.create(accessKey, secret)));
    }

    private static String secret(String reference, Map<String, String> environment) {
        String value;
        if (reference != null && reference.startsWith("env:")) {
            value = environment.get(reference.substring(4));
        } else if (reference != null && reference.startsWith("file:")) {
            try {
                URI location = uri(reference, "credential file reference");
                Path path = location.isOpaque() ? Path.of(location.getSchemeSpecificPart()) : Path.of(location);
                value = Files.readString(path, StandardCharsets.UTF_8).trim();
            } catch (IOException | IllegalArgumentException failure) {
                throw new IllegalArgumentException("Cannot read S3 credential file");
            }
        } else {
            throw new IllegalArgumentException("S3 secrets must use env: or file: references");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("S3 secret is missing or blank");
        }
        return value;
    }
}
