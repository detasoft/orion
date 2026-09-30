package pro.deta.orion.git.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;
import pro.deta.orion.util.Result;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(120)
class S3NativeGitRepositoryProviderIT {
    @Test
    void persistsMetadataAcrossProvidersWithoutInitializingTheGitDataPlane() throws Exception {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-s3-" + UUID.randomUUID());
             S3NativeGitRepositoryProvider first = provider(server, "repos");
             S3NativeGitRepositoryProvider second = provider(server, "repos");
             S3NativeGitRepositoryProvider isolated = provider(server, "repos/nested")) {
            assertThat(first.repositoryNames()).isEmpty();
            assertThat(first.exists("team/repo")).isFalse();
            assertFailure(first.find("team/repo"), Result.FailureCode.NOT_FOUND);
            NativeGitRepository created = first.create("team%2Frepo").valueOrFailure("create");
            assertThat(created.name()).isEqualTo("team/repo");
            assertThat(created.defaultHead()).isEqualTo("refs/heads/main");
            assertThatThrownBy(created::refs).hasMessageContaining("not implemented");
            created.close();
            assertFailure(second.create("team/repo"), Result.FailureCode.FILE_ALREADY_EXISTS);
            assertThat(second.exists("team/repo")).isTrue();
            try (NativeGitRepository reopened = second.find("team/repo").valueOrFailure("reopen")) {
                assertThat(reopened.name()).isEqualTo("team/repo");
                assertThat(reopened.defaultHead()).isEqualTo("refs/heads/main");
            }
            first.create("alpha").valueOrFailure("create alpha").close();
            assertThat(second.repositoryNames()).containsExactly("alpha", "team/repo");
            assertThat(isolated.repositoryNames()).isEmpty();
            assertThat(isolated.exists("team/repo")).isFalse();
            isolated.create("team/repo").valueOrFailure("isolated create").close();
            assertThat(isolated.repositoryNames()).containsExactly("team/repo");
            assertThat(first.repositoryNames()).containsExactly("alpha", "team/repo");
            first.close();
            first.close();
            assertThatThrownBy(first::repositoryNames).isInstanceOf(IllegalStateException.class);
            assertThat(second.exists("alpha")).isTrue();
            try (S3NativeGitRepositoryProvider reopened = provider(server, "repos/")) {
                assertThat(reopened.repositoryNames()).containsExactly("alpha", "team/repo");
            }
        }
    }

    @Test
    void competingProvidersCreateExactlyOneRepository() throws Exception {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-s3-" + UUID.randomUUID());
             S3NativeGitRepositoryProvider first = provider(server, "repos");
             S3NativeGitRepositoryProvider second = provider(server, "repos");
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Result<NativeGitRepository>> one = executor.submit(() -> {
                ready.countDown();
                start.await();
                return first.create("same");
            });
            Future<Result<NativeGitRepository>> two = executor.submit(() -> {
                ready.countDown();
                start.await();
                return second.create("same");
            });
            ready.await();
            start.countDown();
            List<Result<NativeGitRepository>> results = List.of(one.get(), two.get());
            int successes = 0;
            for (Result<NativeGitRepository> result : results) {
                if (result instanceof Result.Success<NativeGitRepository> success) {
                    successes++;
                    success.value().close();
                } else {
                    assertFailure(result, Result.FailureCode.FILE_ALREADY_EXISTS);
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(second.repositoryNames()).containsExactly("same");
        }
    }

    @Test
    void missingBucketAndDeniedAccessAreFailuresNotMissingRepositories() {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-s3-" + UUID.randomUUID());
             S3NativeGitRepositoryProvider missingBucket = new S3NativeGitRepositoryProvider(
                     "s3://missing-" + UUID.randomUUID() + "/repos", server.endpoint(),
                     auth(server), environment(server));
             S3NativeGitRepositoryProvider denied = new S3NativeGitRepositoryProvider(
                     "s3://" + server.bucketName() + "/repos", server.endpoint(), auth(server),
                     Map.of("SECRET", "wrong-secret"))) {
            for (S3NativeGitRepositoryProvider provider : List.of(missingBucket, denied)) {
                assertFailure(provider.find("repo"), Result.FailureCode.GENERAL);
                assertFailure(provider.create("repo"), Result.FailureCode.GENERAL);
                assertThatThrownBy(() -> provider.exists("repo")).isInstanceOf(UncheckedIOException.class);
                assertThatThrownBy(provider::repositoryNames).isInstanceOf(UncheckedIOException.class);
            }
        }
    }

    @Test
    void listsAllPagesAndRejectsCorruptOrMisplacedMetadata() throws Exception {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-s3-" + UUID.randomUUID());
             S3NativeGitRepositoryProvider provider = provider(server, "repos")) {
            for (int index = 0; index < 1001; index++) {
                server.putObject("repos/000-foreign-" + index, new byte[0]);
            }
            provider.create("last-page").valueOrFailure("create").close();
            assertThat(provider.repositoryNames()).containsExactly("last-page");
            String key = "repos/" + HexFormat.of().formatHex(GitHashAlgorithm.SHA256.newDigest()
                    .digest("last-page".getBytes(StandardCharsets.UTF_8)))
                    + "/orion-native-repository.properties";
            for (String invalid : List.of("name=other\ndefaultHead=refs/heads/main\n",
                    "name=last-page\n", "name=../bad\ndefaultHead=refs/heads/main\n", "x".repeat(8193))) {
                server.putObject(key, invalid.getBytes(StandardCharsets.UTF_8));
                assertFailure(provider.find("last-page"), Result.FailureCode.GENERAL);
                assertThatThrownBy(() -> provider.exists("last-page"))
                        .isInstanceOf(UncheckedIOException.class);
                assertThatThrownBy(provider::repositoryNames).isInstanceOf(UncheckedIOException.class);
                assertFailure(provider.create("last-page"), Result.FailureCode.FILE_ALREADY_EXISTS);
            }
        }
    }

    @Test
    void xmlBindingsShareTransportAcrossBucketsAndUseUpdatedEncryptedCredentialsWithoutLocalFallback() throws Exception {
        try (MinioS3TestServer server = MinioS3TestServer.start("orion-s3-" + UUID.randomUUID());
             S3ConfigurationFixture fixture = new S3ConfigurationFixture();
             software.amazon.awssdk.services.s3.S3Client setup = software.amazon.awssdk.services.s3.S3Client.builder()
                     .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                     .endpointOverride(java.net.URI.create(server.endpoint())).forcePathStyle(true)
                     .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                             software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                     server.accessKeyId(), server.secretAccessKey()))).build()) {
            String secondBucket = "orion-s3-" + UUID.randomUUID();
            setup.createBucket(request -> request.bucket(secondBucket));
            try {
                fixture.connection(false, "archive", server.endpoint(), "us-east-1", server.accessKeyId(),
                        server.secretAccessKey(), null);
                fixture.bind("first", false, "archive", "s3://" + server.bucketName() + "/first");
                fixture.bind("second", false, "archive", "s3://" + secondBucket + "/second");
                assertThat(fixture.provider.repositoryNames()).isEmpty();
                fixture.provider.create("acme/dev/first").valueOrFailure("first bucket").close();
                fixture.provider.create("acme/dev/second").valueOrFailure("second bucket").close();
                assertThat(fixture.provider.repositoryNames()).containsExactly("acme/dev/first", "acme/dev/second");
                try (S3Transport reopened = new S3Transport()) {
                    ConfiguredNativeGitRepositoryProvider reader =
                            new ConfiguredNativeGitRepositoryProvider(fixture.local, reopened);
                    reader.activate(fixture.current::get, fixture.secrets, ignored -> false);
                    reader.find("acme/dev/first").valueOrFailure("reopen persisted metadata").close();
                }
                fixture.connection(true, "archive", server.endpoint(), "us-east-1", server.accessKeyId(),
                        "invalid-secret", null);
                fixture.bind("denied", true, "archive", "s3://" + server.bucketName() + "/denied");
                fixture.local.create("acme/dev/denied").valueOrFailure("same-name local data").close();
                assertThatThrownBy(() -> fixture.provider.exists("acme/dev/denied"))
                        .isInstanceOf(UncheckedIOException.class);
                assertFailure(fixture.provider.find("acme/dev/denied"), Result.FailureCode.GENERAL);
                assertThat(fixture.provider.exists("acme/dev/first")).isTrue();
                fixture.connection(true, "archive", server.endpoint(), "us-east-1", server.accessKeyId(),
                        server.secretAccessKey(), null);
                assertThat(fixture.provider.exists("acme/dev/denied")).isFalse();
                fixture.provider.create("acme/dev/denied").valueOrFailure("rotated credentials").close();
                assertThat(fixture.provider.exists("acme/dev/denied")).isTrue();
                fixture.bind("first", false, "archive", "s3://" + server.bucketName() + "/different");
                assertThat(fixture.provider.exists("acme/dev/first")).isFalse();
                assertThat(fixture.provider.exists("acme/dev/second")).isTrue();
            } finally {
                for (var page : setup.listObjectsV2Paginator(request -> request.bucket(secondBucket))) {
                    for (var object : page.contents()) {
                        setup.deleteObject(request -> request.bucket(secondBucket).key(object.key()));
                    }
                }
                setup.deleteBucket(request -> request.bucket(secondBucket));
            }
        }
    }

    private static S3NativeGitRepositoryProvider provider(MinioS3TestServer server, String prefix) {
        return new S3NativeGitRepositoryProvider("s3://" + server.bucketName() + "/" + prefix,
                server.endpoint(), auth(server), environment(server));
    }

    private static Map<String, String> auth(MinioS3TestServer server) {
        return Map.of("region", "us-east-1",
                "accessKeyId", server.accessKeyId(), "secretAccessKey", "env:SECRET");
    }

    private static Map<String, String> environment(MinioS3TestServer server) {
        return Map.of("SECRET", server.secretAccessKey());
    }

    private static void assertFailure(Result<?> result, Result.FailureCode code) {
        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code()).isEqualTo(code);
    }
}
