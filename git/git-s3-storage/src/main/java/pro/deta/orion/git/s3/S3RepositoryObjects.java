package pro.deta.orion.git.s3;

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repository request binding borrowing the application's transport and its per-request configuration. */
record S3RepositoryObjects(S3Transport transport, AwsRequestOverrideConfiguration overrides,
                           String bucket, String prefix) {
    @FunctionalInterface
    interface Reader<T> {
        T read(InputStream input, long length, String etag) throws IOException;
    }

    @FunctionalInterface
    interface Operation<T> {
        T run() throws IOException;
    }

    <T> T operation(Operation<T> operation) throws IOException {
        try {
            return transport.operation(() -> {
                try {
                    return operation.run();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        } catch (SdkException | IllegalStateException failure) {
            throw new IOException("S3 repository operation failed", failure);
        }
    }

    <T> Optional<T> read(String key, Reader<T> reader) throws IOException {
        return operation(() -> {
            try (ResponseInputStream<GetObjectResponse> input = transport.client().getObject(request -> request
                    .overrideConfiguration(overrides).bucket(bucket).key(prefix + key))) {
                boolean consumed = false;
                try {
                    T value = reader.read(input, input.response().contentLength(), input.response().eTag());
                    consumed = true;
                    return Optional.of(value);
                } finally {
                    if (!consumed) input.abort();
                }
            } catch (S3Exception failure) {
                if (missing(failure)) return Optional.empty();
                throw failure;
            }
        });
    }

    boolean put(String key, byte[] bytes, String expectedEtag) throws IOException {
        return operation(() -> {
            try {
                transport.client().putObject(request -> request.overrideConfiguration(overrides).bucket(bucket)
                        .key(prefix + key).ifMatch(expectedEtag).ifNoneMatch(expectedEtag == null ? "*" : null),
                        RequestBody.fromBytes(bytes));
                return true;
            } catch (S3Exception failure) {
                if (conflict(failure)) return false;
                throw failure;
            }
        });
    }

    List<String> list(String keyPrefix) throws IOException {
        return operation(() -> {
            List<String> keys = new ArrayList<>();
            for (ListObjectsV2Response page : transport.client().listObjectsV2Paginator(request -> request
                    .overrideConfiguration(overrides).bucket(bucket).prefix(prefix + keyPrefix))) {
                for (S3Object object : page.contents()) keys.add(object.key().substring(prefix.length()));
            }
            return List.copyOf(keys);
        });
    }

    static boolean missing(S3Exception failure) {
        return failure.statusCode() == 404 && failure.awsErrorDetails() != null
                && "NoSuchKey".equals(failure.awsErrorDetails().errorCode());
    }

    static boolean conflict(S3Exception failure) {
        return failure.statusCode() == 412 || failure.statusCode() == 409;
    }
}
