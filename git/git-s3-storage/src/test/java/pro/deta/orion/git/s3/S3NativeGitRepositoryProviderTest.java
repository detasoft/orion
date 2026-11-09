package pro.deta.orion.git.s3;

import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3NativeGitRepositoryProviderTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

    @TempDir
    Path directory;

    @Test
    void freshProvidersRejectCorruptMetadataWithoutReusingAnOpenRepository() throws Exception {
        try (S3GitIndexTest.Wire wire = new S3GitIndexTest.Wire();
             NativeGitRepositoryProvider retained = provider(wire)) {
            NativeGitRepository repository = retained.create("repo").valueOrFailure("create");
            String metadata = wire.contents.keySet().stream()
                    .filter(key -> key.endsWith("/orion-native-repository.properties")).findFirst().orElseThrow();
            for (String invalid : List.of("name=other\ndefaultHead=refs/heads/main\n", "name=repo\n",
                    "name=../bad\ndefaultHead=refs/heads/main\n", "x".repeat(8193))) {
                wire.contents.put(metadata, invalid.getBytes(StandardCharsets.UTF_8));
                assertThat(retained.find("repo").valueOrFailure("retained owner")).isSameAs(repository);
                try (NativeGitRepositoryProvider fresh = provider(wire)) {
                    assertThat(fresh.find("repo")).isInstanceOfSatisfying(Result.Failure.class,
                            failure -> assertThat(failure.code()).isEqualTo(Result.FailureCode.GENERAL));
                    assertThatThrownBy(() -> fresh.exists("repo")).isInstanceOf(java.io.UncheckedIOException.class);
                    assertThatThrownBy(fresh::repositoryNames).isInstanceOf(java.io.UncheckedIOException.class);
                    assertThat(fresh.create("repo")).isInstanceOfSatisfying(Result.Failure.class,
                            failure -> assertThat(failure.code()).isEqualTo(Result.FailureCode.FILE_ALREADY_EXISTS));
                }
            }
        }
    }

    private NativeGitRepositoryProvider provider(S3GitIndexTest.Wire wire) {
        return S3NativeGitRepositoryFactory.repositories("s3://bucket/repos",
                "http://127.0.0.1:" + wire.server.getAddress().getPort(),
                Map.of("accessKeyId", "test", "secretAccessKey", "env:SECRET"),
                Map.of("SECRET", "test"), testRuntime.events());
    }

    @Test
    void boundsTheResponseBodyEvenWhenTheServerKeepsSendingBytes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 80);
                for (int index = 0; index < 80; index++) {
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    Thread.sleep(500);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException disconnected) {
                // The SDK deadline must abort a still-active response.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (NativeGitRepositoryProvider provider =
                S3NativeGitRepositoryFactory.repositories(
                        "s3://bucket/prefix",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        Map.of("accessKeyId", "test", "secretAccessKey", "env:SECRET"),
                        Map.of("SECRET", "test"),
                        testRuntime.events())) {
            long started = System.nanoTime();
            Result<?> result = provider.find("repository");
            assertThat(result).isInstanceOf(Result.Failure.class);
            assertThat(((Result.Failure<?>) result).code()).isEqualTo(Result.FailureCode.GENERAL);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(35));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesNamesBeforeAnyRequestAndClosesIdempotently() {
        NativeGitRepositoryProvider provider = S3NativeGitRepositoryFactory.repositories(
                "s3://bucket/", null, Map.of("accessKeyId", "test", "secretAccessKey", "env:SECRET"),
                Map.of("SECRET", "test"), testRuntime.events());
        try (provider) {
            for (String name : List.of("../escape", "a//b", "repo.git", "/absolute", "bad%XX")) {
                assertThatThrownBy(() -> provider.create(name)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> provider.find(name)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> provider.exists(name)).isInstanceOf(IllegalArgumentException.class);
            }
        }
        provider.close();
        assertThatThrownBy(() -> provider.create("valid")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.find("valid")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.exists("valid")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(provider::repositoryNames).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolvesCredentialFilesAndRejectsMissingOrInlineSecretsWithoutEchoingThem() throws Exception {
        Path secret = directory.resolve("credential");
        Files.writeString(secret, "test-secret\n");
        try (NativeGitRepositoryProvider ignored = S3NativeGitRepositoryFactory.repositories(
                "s3://bucket/prefix", null, Map.of("accessKeyId", "test",
                        "secretAccessKey", secret.toUri().toString(),
                        "sessionToken", "env:TOKEN"), Map.of("TOKEN", "test-token"), testRuntime.events())) {
            // Client construction must accept the same env/file secret conventions as S3 configuration.
        }
        for (String reference : List.of("inline-secret", "env:MISSING", "file:/does-not-exist")) {
            assertThatThrownBy(() -> S3NativeGitRepositoryFactory.repositories("s3://bucket/prefix", null,
                    Map.of("accessKeyId", "test", "secretAccessKey", reference), Map.of(), testRuntime.events()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(reference);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:repos", "s3://", "s3://bucket/../escape", "s3://bucket/a//b", "s3://bucket//",
            "s3://user:password@bucket/path", "s3://bucket/path?secret=value", "s3://bucket/path#fragment"})
    void rejectsInvalidLocations(String location) {
        assertThatThrownBy(
                        () ->
                                S3NativeGitRepositoryFactory.repositories(
                                        location, null, Map.of(), Map.of(), testRuntime.events()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "http://user:password@localhost:9000", "file:/tmp/s3",
            "http://localhost:9000?key=secret", "http://localhost:9000#fragment"})
    void rejectsInvalidEndpoints(String endpoint) {
        assertThatThrownBy(() -> S3NativeGitRepositoryFactory.repositories(
                "s3://bucket/prefix", endpoint, Map.of(), Map.of(), testRuntime.events()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("S3 endpoint");
    }

    @Test
    void rejectsIncompleteCredentialsAndInvalidOptions() {
        for (Map<String, String> auth : List.of(
                Map.of("accessKeyId", "test"), Map.of("secretAccessKey", "env:SECRET"),
                Map.of("sessionToken", "env:TOKEN"), Map.of("region", " "),
                Map.of("pathStyleAccess", "maybe"))) {
            assertThatThrownBy(
                            () ->
                                    S3NativeGitRepositoryFactory.repositories(
                                            "s3://bucket/prefix",
                                            null,
                                            auth,
                                            Map.of(),
                                            testRuntime.events()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
