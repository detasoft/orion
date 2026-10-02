package pro.deta.orion.transport.http;

import pro.deta.orion.config.ConfigurationFile;

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
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrionAdminAccessControlRouteTest {
    @ParameterizedTest
    @ValueSource(strings = {"alice", "José-東京-Ирина"})
    void exportsOriginalXmlBytesAndRejectsUploads(String userId) throws Exception {
        User user = new User(userId, null, null, "user@example.test",
                List.of(), List.of(), List.of());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AccessControl acl = new AccessControl(List.of(user), List.of(), List.of());
        OrionXml.write(OrionDocument.withAccessControl(acl), output);
        byte[] content = output.toByteArray();
        OrionAccessControlService accessControl = (OrionAccessControlService) Proxy.newProxyInstance(
                OrionAccessControlService.class.getClassLoader(), new Class<?>[]{OrionAccessControlService.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("accessControlConfigurationFile")) {
                        return new ConfigurationFile(content, Optional.of("revision-1"));
                    }
                    throw new AssertionError("Unexpected ACL call: " + method.getName());
                });
        Grant grant = new Grant("admin", List.of(
                new GrantExpression(AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING)));
        SecurityContext admin = SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl("admin", List.of(grant)));
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(new OrionAdminAccessControlRoute(accessControl))),
                new OrionHttpResponseWriter(new ObjectMapper())) {
            @Override
            public void service(HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException {
                request.setAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE,
                        "true".equals(request.getHeader("X-Test-Admin")) ? admin : SecurityContext.createContext());
                super.service(request, response);
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
            URI uri = URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/api/admin/acl");
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
            assertThat(client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(403);
            HttpResponse<byte[]> exported = client.send(request.header("X-Test-Admin", "true").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(exported.statusCode()).isEqualTo(200);
            assertThat(exported.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("application/xml");
            assertThat(exported.headers().firstValue("ETag")).contains("\"revision-1\"");
            assertThat(exported.body()).isEqualTo(content);
            assertThat(OrionXml.read(new ByteArrayInputStream(exported.body()))
                    .system().accessControl().users().getFirst().id()).isEqualTo(userId);

            HttpRequest upload = request.header("If-Match", exported.headers().firstValue("ETag").orElseThrow())
                    .header("Content-Type", "application/xml")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(exported.body())).build();
            assertThat(client.send(upload, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(405);
            assertThat(client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body())
                    .isEqualTo(content);
        } finally {
            server.stop();
        }
    }
}
