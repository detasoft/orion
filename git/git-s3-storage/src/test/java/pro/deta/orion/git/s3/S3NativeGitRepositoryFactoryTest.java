package pro.deta.orion.git.s3;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryFactory;
import pro.deta.orion.schema.orion.v2.RepositoryName;
import pro.deta.orion.util.Result;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class S3NativeGitRepositoryFactoryTest {
    @Test
    void createsConditionallyAndOpensFreshHandlesWithoutOwningTheConnection() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        ConcurrentHashMap<String, byte[]> stored = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                requests.add(method);
                int status = 200;
                byte[] response;
                if (method.equals("PUT")) {
                    byte[] content = exchange.getRequestBody().readAllBytes();
                    if ("aws-chunked".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                        content = S3GitIndexTest.Wire.chunks(content);
                    }
                    if (!"*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                        status = 400;
                        response = error("InvalidRequest");
                    } else if (stored.putIfAbsent(path, content) != null) {
                        status = 412;
                        response = error("PreconditionFailed");
                    } else {
                        response = new byte[0];
                    }
                } else {
                    response = stored.get(path);
                    if (response == null) {
                        status = 404;
                        response = error("NoSuchKey");
                    }
                }
                exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try (S3Transport transport = new S3Transport()) {
            AwsRequestOverrideConfiguration overrides = transport.overrides(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "us-east-1", true,
                    Optional.of(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))));
            NativeGitRepositoryFactory factory = new S3NativeGitRepositoryFactory("bucket", "prefix/",
                    transport, overrides);
            RepositoryName name = RepositoryName.parse("team/repo");
            Result<NativeGitRepository> missing = factory.open(name);
            assertThat(missing).isInstanceOf(Result.Failure.class);
            assertThat(((Result.Failure<?>) missing).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
            try (NativeGitRepository created = factory.create(name).valueOrFailure("create")) {
                assertThat(stored).hasSize(2);
                Result<NativeGitRepository> duplicate = factory.create(name);
                assertThat(duplicate).isInstanceOf(Result.Failure.class);
                assertThat(((Result.Failure<?>) duplicate).code())
                        .isEqualTo(Result.FailureCode.FILE_ALREADY_EXISTS);
                assertThat(stored).hasSize(2);
                try (NativeGitRepository opened = factory.open(name).valueOrFailure("open")) {
                    assertThat(opened).isNotSameAs(created);
                    assertThat(opened.name()).isEqualTo(name.value());
                    assertThat(opened.index().getHEAD())
                            .isEqualTo(new Head.Symbolic(new RefId("refs/heads/main")));
                }
            }
            factory.close();
            try (S3NativeGitRepositoryProvider other =
                         new S3NativeGitRepositoryProvider("s3://bucket/prefix", transport, overrides)) {
                assertThat(other.exists(name.value())).isTrue();
            }
            assertThat(requests).contains("GET", "PUT");
        } finally {
            server.stop(0);
        }
    }

    private static byte[] error(String code) {
        return ("<Error><Code>" + code + "</Code></Error>").getBytes(StandardCharsets.UTF_8);
    }
}
