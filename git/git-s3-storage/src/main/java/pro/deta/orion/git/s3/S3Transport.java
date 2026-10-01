package pro.deta.orion.git.s3;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointProvider;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** One SDK client and bounded HTTP pool; connection configuration belongs exclusively to each request. */
@jakarta.inject.Singleton
public final class S3Transport implements AutoCloseable {
    private final S3EndpointProvider endpoints = S3EndpointProvider.defaultProvider();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private Resources resources;
    private boolean closed;

    @jakarta.inject.Inject
    public S3Transport() {}

    private synchronized Resources resources() {
        if (resources != null) return resources;
        DefaultCredentialsProvider defaults = DefaultCredentialsProvider.builder().build();
        SdkHttpClient http;
        try {
            http = ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                    .connectionAcquisitionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(10)).build();
        } catch (RuntimeException failure) {
            defaults.close();
            throw failure;
        }
        try {
            S3Client client = S3Client.builder().region(Region.US_EAST_1).credentialsProvider(defaults).httpClient(http)
                    .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30))
                            .apiCallAttemptTimeout(Duration.ofSeconds(15))).build();
            resources = new Resources(client, http, defaults);
            return resources;
        } catch (RuntimeException failure) {
            http.close();
            defaults.close();
            throw failure;
        }
    }

    public NativeGitRepositoryProvider repositories(String location, String endpoint, String region,
            boolean pathStyleAccess, Optional<AwsCredentialsProvider> credentials) {
        return new NativeGitRepositoryProvider(factory(location, endpoint, region, pathStyleAccess, credentials));
    }

    public S3NativeGitRepositoryFactory factory(String location, String endpoint, String region,
            boolean pathStyleAccess, Optional<AwsCredentialsProvider> credentials) {
        return operation(() -> S3NativeGitRepositoryFactory.shared(location, this,
                overrides(endpoint, region, pathStyleAccess, credentials)));
    }

    AwsRequestOverrideConfiguration overrides(String endpoint, String region, boolean pathStyleAccess,
            Optional<AwsCredentialsProvider> credentials) {
        if (region == null || !region.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("S3 region must not be blank or malformed");
        }
        if (endpoint != null) {
            URI uri;
            try {
                uri = URI.create(endpoint);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid S3 endpoint");
            }
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("S3 endpoint must be an HTTP(S) URL without credentials");
            }
        }
        S3EndpointProvider selected = parameters -> endpoints.resolveEndpoint(parameters.toBuilder()
                .endpoint(endpoint).region(Region.of(region)).forcePathStyle(endpoint != null || pathStyleAccess).build());
        return AwsRequestOverrideConfiguration.builder().endpointProvider(selected)
                .credentialsProvider(credentials.orElseGet(() -> resources().defaults())).build();
    }

    S3Client client() {
        return resources().client();
    }

    <T> T operation(Supplier<T> operation) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("S3 client is closed");
            return operation.get();
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            if (resources == null) return;
            try {
                resources.client().close();
            } finally {
                try {
                    resources.http().close();
                } finally {
                    resources.defaults().close();
                }
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
    private record Resources(S3Client client, SdkHttpClient http, DefaultCredentialsProvider defaults) {}
}
