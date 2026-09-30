package pro.deta.orion.git.s3;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.util.Result;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S3 owns repository metadata; each lookup reads it afresh. Standalone providers own their connection;
 * shared providers borrow their connection and closing them does not close it. Repository handles own no S3
 * resources. Git data is unsupported.
 */
public final class S3NativeGitRepositoryProvider implements NativeGitRepositoryProvider {
    private static final String METADATA_FILE = "orion-native-repository.properties";
    private static final String DEFAULT_HEAD = "refs/heads/main";
    private static final int MAX_METADATA_BYTES = 8192;

    private final String bucket;
    private final String prefix;
    private final S3Client client;
    private final S3Transport owner;
    private final AwsRequestOverrideConfiguration overrides;
    private final boolean ownsConnection;
    private final AtomicBoolean closed = new AtomicBoolean();

    S3NativeGitRepositoryProvider(String location, S3Transport owner,
            AwsRequestOverrideConfiguration overrides) {
        this(parseLocation(location), owner, overrides, false);
    }

    private S3NativeGitRepositoryProvider(Location location, S3Transport owner,
            AwsRequestOverrideConfiguration overrides, boolean ownsConnection) {
        this.owner = Objects.requireNonNull(owner, "client owner");
        this.overrides = Objects.requireNonNull(overrides, "request configuration");
        this.ownsConnection = ownsConnection;
        client = owner.client();
        bucket = location.bucket();
        prefix = location.prefix();
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
        if ((prefix + "0".repeat(64) + "/" + METADATA_FILE).getBytes(StandardCharsets.UTF_8).length > 1024) {
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
            List<String> names = new ArrayList<>();
            try {
                for (ListObjectsV2Response page : client.listObjectsV2Paginator(
                        request -> request.overrideConfiguration(overrides).bucket(bucket).prefix(prefix))) {
                    for (S3Object object : page.contents()) {
                        String key = object.key();
                        String relative = key.substring(prefix.length());
                        if (!relative.matches("[0-9a-f]{64}/" + METADATA_FILE.replace(".", "\\."))) {
                            continue;
                        }
                        String name = readName(key);
                        if (name != null) {
                            names.add(name);
                        }
                    }
                }
                names.sort(String::compareTo);
                return List.copyOf(names);
            } catch (IOException | SdkException failure) {
                throw storageFailure("Cannot list S3 repositories", failure);
            }

        });
    }

    @Override
    public boolean exists(String repositoryName) {
        return owner.operation(() -> {
            requireOpen();
            String name = RepositoryName.parse(repositoryName).value();
            try {
                return readName(key(name)) != null;
            } catch (IOException | SdkException failure) {
                throw storageFailure("Cannot check S3 repository metadata", failure);
            }

        });
    }

    @Override
    public Result<NativeGitRepository> find(String repositoryName) {
        return owner.operation(() -> {
            requireOpen();
            String name = RepositoryName.parse(repositoryName).value();
            try {
                String storedName = readName(key(name));
                return storedName == null
                        ? new Result.Failure<>(Result.FailureCode.NOT_FOUND,
                                "S3 repository does not exist: " + name)
                        : new Result.Success<>(repository(storedName));
            } catch (IOException | SdkException failure) {
                return new Result.Failure<>(Result.FailureCode.GENERAL,
                        "Cannot read S3 repository metadata", failure);
            }

        });
    }

    @Override
    public Result<NativeGitRepository> create(String repositoryName) {
        return owner.operation(() -> {
            requireOpen();
            String name = RepositoryName.parse(repositoryName).value();
            Properties properties = new Properties();
            properties.setProperty("name", name);
            properties.setProperty("defaultHead", DEFAULT_HEAD);
            try {
                StringWriter writer = new StringWriter();
                properties.store(writer, null);
                byte[] content = writer.toString().getBytes(StandardCharsets.UTF_8);
                if (content.length > MAX_METADATA_BYTES) {
                    throw new IllegalArgumentException("S3 repository metadata is too large");
                }
                client.putObject(request -> request.overrideConfiguration(overrides).bucket(bucket).key(key(name)).ifNoneMatch("*")
                        .contentType("text/plain; charset=utf-8"), RequestBody.fromBytes(content));
                return new Result.Success<>(repository(name));
            } catch (S3Exception failure) {
                if (failure.statusCode() == 412 && "PreconditionFailed".equals(errorCode(failure))) {
                    return new Result.Failure<>(Result.FailureCode.FILE_ALREADY_EXISTS,
                            "S3 repository already exists: " + name);
                }
                return new Result.Failure<>(Result.FailureCode.GENERAL,
                        "Cannot create S3 repository metadata", failure);
            } catch (IOException | SdkException failure) {
                return new Result.Failure<>(Result.FailureCode.GENERAL,
                        "Cannot create S3 repository metadata", failure);
            }

        });
    }

    private String readName(String key) throws IOException {
        try {
            byte[] content = client.getObject(request -> request.overrideConfiguration(overrides).bucket(bucket)
                    .key(key), (response, input) -> {
                byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
                if (bytes.length > MAX_METADATA_BYTES) {
                    input.abort();
                    throw new IOException("S3 repository metadata is too large");
                }
                return bytes;
            });
            Properties properties = new Properties();
            properties.load(new StringReader(new String(content, StandardCharsets.UTF_8)));
            String name = properties.getProperty("name");
            if (name == null || !RepositoryName.parse(name).value().equals(name)
                    || !key(name).equals(key) || !DEFAULT_HEAD.equals(properties.getProperty("defaultHead"))) {
                throw new IOException("Invalid S3 repository metadata");
            }
            return name;
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404 && "NoSuchKey".equals(errorCode(failure))) {
                return null;
            }
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid S3 repository metadata", failure);
        }
    }

    private static NativeGitRepository repository(String name) {
        return new NativeGitRepository(name, new S3GitStorage(), new S3GitIndex(), DEFAULT_HEAD);
    }

    private String key(String name) {
        return prefix + HexFormat.of().formatHex(GitHashAlgorithm.SHA256.newDigest()
                .digest(name.getBytes(StandardCharsets.UTF_8))) + "/" + METADATA_FILE;
    }

    private static String errorCode(S3Exception failure) {
        return failure.awsErrorDetails() == null ? "" : failure.awsErrorDetails().errorCode();
    }

    private static UncheckedIOException storageFailure(String message, Exception failure) {
        return new UncheckedIOException(message, new IOException(message, failure));
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
