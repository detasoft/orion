package pro.deta.orion.transport.http;

import java.util.Optional;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.schema.orion.OrganizationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

import static jakarta.servlet.http.HttpServletResponse.SC_CREATED;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionAdminCreateRepositoryRouteTest {
    private final InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
    private final OrionAdminCreateRepositoryRoute route = new OrionAdminCreateRepositoryRoute(
            provider,
            new ObjectMapper());

    @Test
    void listsExistingRepositoriesInStableOrder() {
        provider.create("zeta").valueOrFailure("repository");
        provider.create("internal/configuration").valueOrFailure("repository");

        OrionHttpResponse response = route.doGet(request(""));

        assertThat(response.status()).isEqualTo(SC_OK);
        assertThat(response.body()).isEqualTo(Map.of(
                "repositories",
                List.of(
                        new OrionAdminCreateRepositoryRoute.RepositoryResponse("internal/configuration"),
                        new OrionAdminCreateRepositoryRoute.RepositoryResponse("zeta"))));
    }

    @Test
    void returnsCreatedOnlyForANewRepository() throws Exception {
        OrionHttpResponse response = route.doPost(request("platform/console"));

        assertThat(response.status()).isEqualTo(SC_CREATED);
        assertThat(response.body()).isEqualTo(Map.of("status", "ok", "created", true));
    }

    @Test
    void returnsOkWhenTheRepositoryAlreadyExists() throws Exception {
        route.doPost(request("platform/console"));

        OrionHttpResponse response = route.doPost(request("platform/console"));

        assertThat(response.status()).isEqualTo(SC_OK);
        assertThat(response.body()).isEqualTo(Map.of("status", "ok", "created", false));
    }

    @Test
    void createsAndListsTheCanonicalRepositoryName() throws Exception {
        OrionHttpResponse response = route.doPost(request("team%2Frepo"));

        assertThat(response.status()).isEqualTo(SC_CREATED);
        assertThat(provider.repositoryNames()).containsExactly("team/repo");
        assertThat(route.doGet(request("")).body()).isEqualTo(Map.of(
                "repositories",
                List.of(new OrionAdminCreateRepositoryRoute.RepositoryResponse("team/repo"))));
    }

    @Test
    void rejectsInvalidNamesBeforeProviderCreation() {
        for (String name : List.of(
                "/repo", "repo.git", "Repo", "../repo", "%2E%2E/repo", "%GG")) {
            assertThatThrownBy(() -> route.doPost(request(name)))
                    .as("repository name %s", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(provider.repositoryNames()).isEmpty();
    }

    @Test
    void filtersOrganizationRepositoriesAndRejectsCrossOrganizationCreation() throws Exception {
        provider.create("acme/team/visible").valueOrFailure("repository");
        provider.create("other/team/hidden").valueOrFailure("repository");
        provider.create("orion").valueOrFailure("repository");
        InternalUserImpl identity = new InternalUserImpl("root",
                ACLUtil.generateDefaultAccessControl("unused").getGrants(),
                Optional.of(new OrganizationId("acme")));
        assertThat(route.doGet(request("", identity)).body()).isEqualTo(Map.of("repositories",
                List.of(new OrionAdminCreateRepositoryRoute.RepositoryResponse("acme/team/visible"))));
        assertThat(route.doPost(request("other/team/new", identity)).status()).isEqualTo(403);
        assertThat(route.doPost(request("acme/team/new", identity)).status()).isEqualTo(201);
        assertThat(provider.repositoryNames()).doesNotContain("other/team/new");
    }

    @Test
    void rejectsSystemNonAdminAndAnonymousRequests() throws Exception {
        for (UserIdentity identity : List.of(SecurityContext.ANONYMOUS,
                new InternalUserImpl("reader", List.of()))) {
            assertThat(route.doGet(request("", identity)).status()).isEqualTo(403);
            assertThat(route.doPost(request("acme/team/new", identity)).status()).isEqualTo(403);
        }
        assertThat(provider.repositoryNames()).isEmpty();
    }

    private static HttpServletRequest request(String name) {
        return request(name, new InternalUserImpl("root",
                ACLUtil.generateDefaultAccessControl("unused").getGrants()));
    }

    private static HttpServletRequest request(String name, UserIdentity identity) {
        byte[] body = ("{\"name\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
        return HttpServletRequest.class.cast(Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> SecurityContext.createContext().withUserIdentity(identity);
                    case "getInputStream" -> new ByteArrayServletInputStream(body);
                    case "toString" -> "repository request";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                }));
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] data) {
            input = new ByteArrayInputStream(data);
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }
    }
}
