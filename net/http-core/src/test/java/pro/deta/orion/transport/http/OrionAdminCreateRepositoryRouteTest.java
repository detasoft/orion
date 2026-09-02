package pro.deta.orion.transport.http;

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

class OrionAdminCreateRepositoryRouteTest {
    private final InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
    private final OrionAdminCreateRepositoryRoute route = new OrionAdminCreateRepositoryRoute(
            provider,
            new ObjectMapper());

    @Test
    void listsExistingRepositoriesInStableOrder() {
        provider.create("zeta").valueOrFailure("repository");
        provider.create("internal/configuration").valueOrFailure("repository");

        OrionHttpResponse response = route.doGet(null);

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

    private static HttpServletRequest request(String name) {
        byte[] body = ("{\"name\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
        return HttpServletRequest.class.cast(Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
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
