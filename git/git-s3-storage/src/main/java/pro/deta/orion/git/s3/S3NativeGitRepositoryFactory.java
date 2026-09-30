package pro.deta.orion.git.s3;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryFactory;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.util.Result;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Opens repositories borrowing the shared S3 transport; factory metadata calls are guarded by its caller. */
final class S3NativeGitRepositoryFactory implements NativeGitRepositoryFactory {
    private static final String METADATA_FILE = "orion-native-repository.properties";
    private static final String DEFAULT_HEAD = "refs/heads/main";
    private static final int MAX_METADATA_BYTES = 8192;

    private final String bucket;
    private final String prefix;
    private final S3Transport transport;
    private final AwsRequestOverrideConfiguration overrides;

    S3NativeGitRepositoryFactory(String bucket, String prefix, S3Transport transport,
            AwsRequestOverrideConfiguration overrides) {
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.overrides = Objects.requireNonNull(overrides, "request configuration");
    }

    List<String> repositoryNames() {
        List<String> names = new ArrayList<>();
        try {
            for (ListObjectsV2Response page : transport.client().listObjectsV2Paginator(
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

    }

    boolean exists(RepositoryName repositoryName) {
        String name = repositoryName.value();
        try {
            return readName(key(name)) != null;
        } catch (IOException | SdkException failure) {
            throw storageFailure("Cannot check S3 repository metadata", failure);
        }

    }

    @Override
    public Result<NativeGitRepository> open(RepositoryName repositoryName) {
        String name = repositoryName.value();
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

    }

    @Override
    public Result<NativeGitRepository> create(RepositoryName repositoryName) {
        String name = repositoryName.value();
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
            transport.client().putObject(request -> request.overrideConfiguration(overrides).bucket(bucket).key(key(name))
                    .ifNoneMatch("*").contentType("text/plain; charset=utf-8"), RequestBody.fromBytes(content));
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

    }

    private String readName(String key) throws IOException {
        try {
            byte[] content = transport.client().getObject(request -> request.overrideConfiguration(overrides).bucket(bucket)
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

    private NativeGitRepository repository(String name) {
        String metadataKey = key(name);
        S3RepositoryObjects objects = new S3RepositoryObjects(transport, overrides, bucket,
                metadataKey.substring(0, metadataKey.length() - METADATA_FILE.length()));
        return new NativeGitRepository(name, new S3GitStorageApi(objects), new S3GitIndexApi(objects), DEFAULT_HEAD);
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

    @Override
    public void close() {
    }
}
