package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.TokenIssueResult;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class OrionAdminIssueTokenRouteTest {
    @Test
    void tokenResponseSerializesTokenWithoutExposingItInDiagnostics() throws Exception {
        OrionAdminIssueTokenRoute.AdminTokenResponse response =
                new OrionAdminIssueTokenRoute.AdminTokenResponse("http-token-secret", "Bearer", 900, 1_100);

        assertThat(new ObjectMapper().writeValueAsString(response))
                .contains("\"token\":\"http-token-secret\"");
        assertThat(response.toString()).doesNotContain("http-token-secret");
    }

    @Test
    void acceptsEmptyBodyWithDefaultTtl() throws Exception {
        AtomicLong ttl = new AtomicLong(-1);
        OrionHttpResponse response = route(ttl, true).doPost(request(new CountingBody("", 0)));

        assertThat(response.status()).isEqualTo(200);
        assertThat(ttl.get()).isEqualTo(900);
    }

    @Test
    void acceptsExplicitTtlAtBodyLimit() throws Exception {
        AtomicLong ttl = new AtomicLong(-1);
        CountingBody body = new CountingBody("{\"expiresInSeconds\":60}", 4096);
        OrionHttpResponse response = route(ttl, true).doPost(request(body));

        assertThat(response.status()).isEqualTo(200);
        assertThat(ttl.get()).isEqualTo(60);
        assertThat(body.consumed).isEqualTo(4096);
    }

    @Test
    void rejectsOneByteOverLimitBeforeIssuingToken() throws Exception {
        AtomicLong ttl = new AtomicLong(-1);
        OrionHttpResponse response = route(ttl, true).doPost(request(new CountingBody("{}", 4097)));

        assertThat(response.status()).isEqualTo(413);
        assertThat(ttl.get()).isEqualTo(-1);
    }

    @Test
    void boundsConsumptionOfLargeBodyWithoutContentLength() throws Exception {
        AtomicLong ttl = new AtomicLong(-1);
        CountingBody body = new CountingBody("{}", 100_000);
        OrionHttpResponse response = route(ttl, false).doPost(request(body));

        assertThat(response.status()).isEqualTo(413);
        assertThat(body.consumed).isEqualTo(4097);
        assertThat(ttl.get()).isEqualTo(-1);
    }

    @Test
    void preservesUnauthorizedResponseForRejectedCredentials() throws Exception {
        AtomicLong ttl = new AtomicLong(-1);
        OrionHttpResponse response = route(ttl, false).doPost(request(new CountingBody("{}", 2)));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.headers()).containsEntry("WWW-Authenticate", "Basic realm=\"orion-admin\"");
        assertThat(ttl.get()).isEqualTo(900);
    }

    private static OrionAdminIssueTokenRoute route(AtomicLong ttl, boolean authenticated) {
        OrionAccessControlService service = (OrionAccessControlService) Proxy.newProxyInstance(
                OrionAccessControlService.class.getClassLoader(), new Class<?>[]{OrionAccessControlService.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("authenticateUserAndIssueToken")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    ttl.set((Long) args[2]);
                    return authenticated ? TokenIssueResult.success("token", 2000)
                            : TokenIssueResult.failure("Invalid credentials");
                });
        return new OrionAdminIssueTokenRoute(service, new ObjectMapper());
    }

    private static HttpServletRequest request(CountingBody body) {
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                "alice:password".getBytes(StandardCharsets.UTF_8));
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> "Authorization".equals(args[0]) ? authorization : null;
                    case "getInputStream" -> body;
                    case "getContentLength" -> -1;
                    case "getContentLengthLong" -> -1L;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class CountingBody extends ServletInputStream {
        private final byte[] prefix;
        private final int length;
        private int consumed;

        private CountingBody(String prefix, int length) {
            this.prefix = prefix.getBytes(StandardCharsets.UTF_8);
            this.length = length;
        }

        @Override
        public int read() {
            if (consumed == length) return -1;
            int index = consumed++;
            return index < prefix.length ? Byte.toUnsignedInt(prefix[index]) : ' ';
        }

        @Override
        public boolean isFinished() { return consumed == length; }

        @Override
        public boolean isReady() { return true; }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("Asynchronous reads are not used");
        }
    }
}
