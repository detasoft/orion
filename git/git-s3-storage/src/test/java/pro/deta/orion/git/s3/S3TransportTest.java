package pro.deta.orion.git.s3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(20)
class S3TransportTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentConnectionsKeepTheirEndpointSigningRegionCredentialsAndToken(boolean sameEndpoint) throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (Server first = new Server(entered, release); Server second = new Server(entered, release);
             S3Transport transport = new S3Transport();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            S3NativeGitRepositoryProvider one = provider(transport, first, "one", "eu-west-1");
            Server selected = sameEndpoint ? first : second;
            S3NativeGitRepositoryProvider two = provider(transport, selected, "two", "ap-south-1");
            Future<Boolean> a = executor.submit(() -> one.exists("repo"));
            Future<Boolean> b = executor.submit(() -> two.exists("repo"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(a.get()).isFalse();
            assertThat(b.get()).isFalse();
            assertRequest(first.requests.stream().filter(request -> request.token().equals("one-token"))
                    .findFirst().orElseThrow(), "one", "eu-west-1");
            assertRequest(selected.requests.stream().filter(request -> request.token().equals("two-token"))
                    .findFirst().orElseThrow(), "two", "ap-south-1");
            one.close();
            assertThat(two.exists("another")).isFalse();
            assertThat(selected.requests).hasSize(sameEndpoint ? 3 : 2);
        }
    }

    @Test
    void everyPaginatorPageAndPutCarryRequestOverrides() throws Exception {
        try (Server server = new Server(new CountDownLatch(0), new CountDownLatch(0));
             S3Transport transport = new S3Transport()) {
            S3NativeGitRepositoryProvider provider = provider(transport, server, "pager", "eu-central-1");
            provider.create("new").valueOrFailure("create metadata").close();
            assertThat(provider.repositoryNames()).isEmpty();
            assertThat(server.requests).hasSize(3);
            for (Request request : server.requests) assertRequest(request, "pager", "eu-central-1");
            assertThat(server.requests.get(0).method()).isEqualTo("PUT");
            assertThat(server.requests.get(2).query()).contains("continuation-token=next");
        }
    }

    @Test
    void shutdownWaitsForActiveRequestAndPreventsFurtherUse() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Server server = new Server(entered, release); S3Transport transport = new S3Transport();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            S3NativeGitRepositoryProvider provider = provider(transport, server, "owner", "us-east-1");
            Future<Boolean> request = executor.submit(() -> provider.exists("repo"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch closing = new CountDownLatch(1);
            Future<?> close = executor.submit(() -> {
                closing.countDown();
                transport.close();
            });
            closing.await();
            try {
                assertThatThrownBy(() -> close.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(request.get()).isFalse();
            close.get();
            transport.close();
            assertThatThrownBy(() -> provider.exists("repo")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void omittedEndpointUsesTheConnectionsAwsRegion() throws Exception {
        try (S3Transport transport = new S3Transport()) {
            var overrides = transport.overrides(null, "eu-west-1", false, Optional.empty());
            var endpoint = (software.amazon.awssdk.services.s3.endpoints.S3EndpointProvider)
                    overrides.endpointProvider().orElseThrow();
            assertThat(endpoint.resolveEndpoint(parameters -> parameters.bucket("test-bucket")
                    .region(software.amazon.awssdk.regions.Region.US_EAST_1)).get().url().toString())
                    .isEqualTo("https://test-bucket.s3.eu-west-1.amazonaws.com");
        }
    }

    private static S3NativeGitRepositoryProvider provider(S3Transport transport, Server server,
            String id, String region) {
        return transport.repositories("s3://bucket/prefix", server.endpoint(), region, false,
                Optional.of(StaticCredentialsProvider.create(AwsSessionCredentials.create(id, "secret", id + "-token"))));
    }

    private static void assertRequest(Request request, String id, String region) {
        assertThat(request.authorization()).contains("Credential=" + id + "/", "/" + region + "/s3/aws4_request");
        assertThat(request.token()).isEqualTo(id + "-token");
        assertThat(request.path()).startsWith("/bucket");
    }

    record Request(String method, String path, String query, String authorization, String token) {}

    static final class Server implements AutoCloseable {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger pages = new AtomicInteger();

        Server(CountDownLatch entered, CountDownLatch release) throws IOException {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("X-Amz-Security-Token")));
                exchange.getRequestBody().readAllBytes();
                entered.countDown();
                try {
                    if (!release.await(8, TimeUnit.SECONDS)) throw new IOException("Request release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                if (exchange.getRequestMethod().equals("PUT")) {
                    reply(exchange, 200, "");
                } else if (exchange.getRequestURI().getQuery() != null
                        && exchange.getRequestURI().getQuery().contains("list-type=2")) {
                    boolean first = pages.getAndIncrement() == 0;
                    reply(exchange, 200, "<ListBucketResult><Name>bucket</Name><IsTruncated>" + first
                            + "</IsTruncated>" + (first ? "<NextContinuationToken>next</NextContinuationToken>" : "")
                            + "</ListBucketResult>");
                } else {
                    reply(exchange, 404, "<Error><Code>NoSuchKey</Code></Error>");
                }
            });
            server.start();
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override
        public void close() { server.stop(0); executor.close(); }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
