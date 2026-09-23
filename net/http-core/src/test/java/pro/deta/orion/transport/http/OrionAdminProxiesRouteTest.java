package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.orion.ConfigurationSecret;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RemoteAlias;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrionAdminProxiesRouteTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OrionDesiredState desired = new OrionDesiredState();
    private final InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
    private final ProxyAwareNativeGitRepositoryProvider provider = new ProxyAwareNativeGitRepositoryProvider(backend);
    private final OrionAdminProxiesRoute route = new OrionAdminProxiesRoute(
            desired, provider, null, null, null, null, mapper);
    private final OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
            new OrionHttpRouteRegistry(Set.of(route)), new OrionHttpResponseWriter(mapper));

    @Test
    void returnsOnlySafeSystemAliasFieldsThroughTheAuthorizedServletWithoutOpeningUpstreams() throws Exception {
        var binding = new GitProxyBinding(new RemoteAlias("configuration"),
                URI.create("ssh://private-user@git.example:2222/config.git"), "main",
                GitCredentialKind.PRIVATE_KEY, Optional.of("private-key-id"),
                Optional.empty(), Set.of("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB"));
        desired.publish(document(List.of(binding)), Optional.of("configuration-revision"));

        var response = get(context(grant(AccessControl.GrantKey.ADMIN)));

        assertThat(response.status).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body.toString());
        assertThat(body.get("aliases").size()).isEqualTo(1);
        assertThat(body.get("aliases").get(0)).isEqualTo(mapper.readTree("""
                {"scope":"system","alias":"configuration","upstream":"ssh://git.example:2222/config.git",
                 "transport":"ssh","ref":"refs/heads/main","endpoint":"/r/proxy/system/configuration.git",
                 "status":"not-checked","observedAt":null}
                """));
        assertThat(response.body.toString()).doesNotContain("private-", "ssh-ed25519", "ciphertext", "cache");
        assertThat(backend.repositoryNames()).isEmpty();
    }

    @Test
    void readsTheCurrentConfigurationAndKeepsAliasesSeparateFromOrdinaryRepositories() throws Exception {
        backend.create("team/repository").valueOrFailure("repository");
        desired.publish(document(List.of()), Optional.empty());
        assertThat(mapper.readTree(get(context(grant(AccessControl.GrantKey.ADMIN))).body.toString())
                .get("aliases").isEmpty()).isTrue();
        var binding = new GitProxyBinding(new RemoteAlias("archive"), URI.create("file:///upstream.git"), "main",
                GitCredentialKind.NONE, Optional.empty(), Optional.empty(), Set.of());
        desired.publish(document(List.of(binding)), Optional.of("new-revision"));

        JsonNode body = mapper.readTree(get(context(grant(AccessControl.GrantKey.ADMIN))).body.toString());
        assertThat(body.get("aliases").get(0).get("alias").asText()).isEqualTo("archive");
        assertThat(provider.repositoryNames()).containsExactly("team/repository");
    }

    @Test
    void rejectsAnonymousReadOnlyAndRepositoryScopedUsersBeforeReadingConfiguration() throws Exception {
        for (SecurityContext context : List.of(SecurityContext.createContext(), context(),
                context(grant(AccessControl.GrantKey.READ)),
                context(new AccessControlDraft.Grant("repository-access", new ArrayList<>())
                        .addKey(AccessControl.GrantKey.REPOSITORY, "team/repository")
                        .addKey(AccessControl.GrantKey.READ, "true")
                        .addKey(AccessControl.GrantKey.READ_WRITE, "true").toAccessControl()))) {
            var response = get(context);
            assertThat(response.status).isEqualTo(403);
            assertThat(response.body.toString()).isEmpty();
        }
    }

    private static OrionDocument document(List<GitProxyBinding> bindings) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty(),
                List.of(new ConfigurationSecret("private-key-id", "private-ciphertext")), bindings), List.of());
    }

    private Response get(SecurityContext context) throws Exception {
        HttpServletRequest request = stub(HttpServletRequest.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMethod" -> "GET";
            case "getPathInfo" -> "/api/admin/proxies";
            case "getAttribute" -> OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE.equals(args[0]) ? context : null;
            case "toString" -> "proxy list request";
            default -> throw new UnsupportedOperationException(method.toString());
        });
        Response response = new Response();
        servlet.service(request, response.proxy());
        return response;
    }

    private static AccessControl.Grant grant(AccessControl.GrantKey key) {
        return new AccessControlDraft.Grant("access", new ArrayList<>()).addKey(key, "true").toAccessControl();
    }

    private static SecurityContext context(AccessControl.Grant... grants) {
        return SecurityContext.createContext().withUserIdentity(new InternalUserImpl("operator", List.of(grants)));
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Response {
        private int status;
        private final StringWriter body = new StringWriter();

        HttpServletResponse proxy() {
            return stub(HttpServletResponse.class, (proxy, method, args) -> switch (method.getName()) {
                case "setStatus", "sendError" -> { status = (int) args[0]; yield null; }
                case "setHeader", "setContentType" -> null;
                case "getWriter" -> new PrintWriter(body);
                default -> throw new UnsupportedOperationException(method.toString());
            });
        }
    }
}
