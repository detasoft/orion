package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.transport.git.DefaultGitNativeRepositoryService;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionGitRouteStreamingFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesFailureBeforeAndAfterResponseCommitment(boolean commit) throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            Exception failure = switch (kind) {
                case 0 -> new GitNativeRepositoryAccessHook.AccessDeniedException("test denial", null);
                case 1 -> new InvalidContentEncodingException(new IOException("test encoding"));
                default -> new IOException("Native repository does not exist: team/project");
            };
            int expectedStatus = switch (kind) {
                case 0 -> 403;
                case 1 -> 400;
                default -> 404;
            };
            verifyFailure(commit, failure, expectedStatus);
        }
    }

    private static void verifyFailure(boolean commit, Exception failure, int expectedStatus) throws Exception {
        AtomicReference<HttpServletResponse> activeResponse = new AtomicReference<>();
        CompletableFuture<Throwable> escaped = new CompletableFuture<>();
        NativeGitRepositoryProvider provider = (NativeGitRepositoryProvider) Proxy.newProxyInstance(
                NativeGitRepositoryProvider.class.getClassLoader(),
                new Class<?>[]{NativeGitRepositoryProvider.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("isPublicRepositoryName")) {
                        return true;
                    }
                    if (method.getName().equals("openForRead")) {
                        if (commit) {
                            activeResponse.get().flushBuffer();
                        }
                        assertThat(activeResponse.get().isCommitted()).isEqualTo(commit);
                        return Result.Failure.generalFailure(failure);
                    }
                    throw new AssertionError("Unexpected provider call: " + method.getName());
                });
        OrionGitRoute route = new OrionGitRoute(new DefaultGitNativeRepositoryService(provider),
                new GitTransportConfig(), provider);
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(route)), new OrionHttpResponseWriter(new ObjectMapper())) {
            @Override
            public void service(HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException {
                AccessControl.Grant grant = new AccessControlDraft.Grant("repository", new ArrayList<>())
                        .addKey(AccessControl.GrantKey.REPOSITORY, "team/project").toAccessControl();
                request.setAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE,
                        SecurityContext.createContext().withUserIdentity(
                                new InternalUserImpl("git-user", List.of(grant))));
                activeResponse.set(response);
                try {
                    super.service(request, response);
                    escaped.complete(null);
                } catch (IOException | ServletException | RuntimeException error) {
                    escaped.complete(error);
                    throw error;
                }
            }
        };
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(servlet), "/*");
        server.setHandler(context);
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + connector.getLocalPort() + "/r/team/project.git/info/refs?service=git-upload-pack"))
                    .timeout(Duration.ofSeconds(5)).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (commit) {
                    assertThat(escaped.get(5, TimeUnit.SECONDS))
                            .isInstanceOf(IOException.class).hasCause(failure);
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThatThrownBy(body::readAllBytes).isInstanceOf(IOException.class);
                } else {
                    assertThat(escaped.get(5, TimeUnit.SECONDS)).isNull();
                    assertThat(response.statusCode()).isEqualTo(expectedStatus);
                    assertThat(new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("# service=git-upload-pack");
                }
            }
        } finally {
            server.stop();
        }
    }
}
