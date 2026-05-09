package pro.deta.orion.transport.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.TokenIssueResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrionAuthorizationFilterTest {
    private static final String ACCESS_TOKEN = "access-token";
    private static final String BASIC_USER = "root";
    private static final String BASIC_PASSWORD = "root-password";

    @Test
    void allowsAdminRequestWithBearerTokenAndAdminGrant() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        OrionAuthorizationFilter filter = new OrionAuthorizationFilter(accessControlService);
        RequestRecorder request = new RequestRecorder("POST", "/api/admin/users", bearerAuth(ACCESS_TOKEN), "");
        ResponseRecorder response = new ResponseRecorder();
        FilterChainRecorder chain = new FilterChainRecorder();

        filter.doFilter(request.proxy(), response.proxy(), chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.status).isZero();
        assertThat(accessControlService.lastToken).isEqualTo(ACCESS_TOKEN);
        SecurityContext securityContext = (SecurityContext) request.attributes.get(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        assertThat(securityContext.getUserIdentity().getUserId()).isEqualTo("token-user");
    }

    @Test
    void allowsTokenEndpointWithoutAdminGrant() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        OrionAuthorizationFilter filter = new OrionAuthorizationFilter(accessControlService);
        RequestRecorder request = new RequestRecorder("POST", "/api/admin/token", basicAuth(BASIC_USER, BASIC_PASSWORD), "");
        ResponseRecorder response = new ResponseRecorder();
        FilterChainRecorder chain = new FilterChainRecorder();

        filter.doFilter(request.proxy(), response.proxy(), chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.status).isZero();
        assertThat(accessControlService.lastToken).isNull();
        SecurityContext securityContext = (SecurityContext) request.attributes.get(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        assertThat(securityContext.getUserIdentity().isAnonymous()).isTrue();
    }

    @Test
    void addsSecurityContextForNonAdminRequestWithBearerToken() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        OrionAuthorizationFilter filter = new OrionAuthorizationFilter(accessControlService);
        RequestRecorder request = new RequestRecorder("GET", "/repositories/project/info/refs", bearerAuth(ACCESS_TOKEN), "");
        ResponseRecorder response = new ResponseRecorder();
        FilterChainRecorder chain = new FilterChainRecorder();

        filter.doFilter(request.proxy(), response.proxy(), chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.status).isZero();
        assertThat(accessControlService.lastToken).isEqualTo(ACCESS_TOKEN);
        SecurityContext securityContext = (SecurityContext) request.attributes.get(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        assertThat(securityContext.getUserIdentity().getUserId()).isEqualTo("token-user");
    }

    @Test
    void rejectsNonPublicRequestWithoutBearerToken() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        OrionAuthorizationFilter filter = new OrionAuthorizationFilter(accessControlService);
        RequestRecorder request = new RequestRecorder("GET", "/repositories/project/info/refs", null, "");
        ResponseRecorder response = new ResponseRecorder();
        FilterChainRecorder chain = new FilterChainRecorder();

        filter.doFilter(request.proxy(), response.proxy(), chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(accessControlService.lastToken).isNull();
        SecurityContext securityContext = (SecurityContext) request.attributes.get(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        assertThat(securityContext.getUserIdentity().isAnonymous()).isTrue();
    }

    @Test
    void allowsPublicChallengeRequestWithoutBearerToken() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        OrionAuthorizationFilter filter = new OrionAuthorizationFilter(accessControlService);
        RequestRecorder request = new RequestRecorder("GET", "/.well-known/acme-challenge/token", null, "");
        ResponseRecorder response = new ResponseRecorder();
        FilterChainRecorder chain = new FilterChainRecorder();

        filter.doFilter(request.proxy(), response.proxy(), chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.status).isZero();
        assertThat(accessControlService.lastToken).isNull();
        SecurityContext securityContext = (SecurityContext) request.attributes.get(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        assertThat(securityContext.getUserIdentity().isAnonymous()).isTrue();
    }

    @Test
    void rejectsAdminRequestWithoutBearerToken() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        OrionAuthorizationFilter filter = new OrionAuthorizationFilter(accessControlService);
        RequestRecorder request = new RequestRecorder("POST", "/api/admin/users", null, "");
        ResponseRecorder response = new ResponseRecorder();
        FilterChainRecorder chain = new FilterChainRecorder();

        filter.doFilter(request.proxy(), response.proxy(), chain);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.invoked).isFalse();
        assertThat(accessControlService.lastToken).isNull();
    }

    @Test
    void rejectsAdminRequestWithInvalidBearerToken() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        OrionAuthorizationFilter filter = new OrionAuthorizationFilter(accessControlService);
        RequestRecorder request = new RequestRecorder("POST", "/api/admin/users", bearerAuth("wrong"), "");
        ResponseRecorder response = new ResponseRecorder();
        FilterChainRecorder chain = new FilterChainRecorder();

        filter.doFilter(request.proxy(), response.proxy(), chain);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.invoked).isFalse();
        assertThat(accessControlService.lastToken).isEqualTo("wrong");
    }

    @Test
    void rejectsBasicCredentialsOnAdminResources() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        OrionAuthorizationFilter filter = new OrionAuthorizationFilter(accessControlService);
        RequestRecorder request = new RequestRecorder("POST", "/api/admin/users", basicAuth(BASIC_USER, BASIC_PASSWORD), "");
        ResponseRecorder response = new ResponseRecorder();
        FilterChainRecorder chain = new FilterChainRecorder();

        filter.doFilter(request.proxy(), response.proxy(), chain);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.invoked).isFalse();
        assertThat(accessControlService.lastToken).isNull();
    }

    @Test
    void rejectsAdminRequestWithoutAdminGrant() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        accessControlService.adminGrant = false;
        OrionAuthorizationFilter filter = new OrionAuthorizationFilter(accessControlService);
        RequestRecorder request = new RequestRecorder("POST", "/api/admin/users", bearerAuth(ACCESS_TOKEN), "");
        ResponseRecorder response = new ResponseRecorder();
        FilterChainRecorder chain = new FilterChainRecorder();

        filter.doFilter(request.proxy(), response.proxy(), chain);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.invoked).isFalse();
    }

    private static String bearerAuth(String token) {
        return "Bearer " + token;
    }

    private static String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class RecordingAccessControlService implements OrionAccessControlService {
        private String lastToken;
        private boolean adminGrant = true;

        @Override
        public void addKeyToUser(String username, String publicKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticationResult authenticateUser(String userName, byte[] credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticationResult authenticateToken(byte[] token) {
            lastToken = new String(token, StandardCharsets.UTF_8);
            if (!ACCESS_TOKEN.equals(lastToken)) {
                return AuthenticationResult.failure("authentication failed");
            }

            List<AccessControl.Grant> grants = new ArrayList<>();
            if (adminGrant) {
                grants.add(new AccessControl.Grant("admin", new ArrayList<>())
                        .addKey(AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING));
            }
            return AuthenticationResult.success(new InternalUserImpl("token-user", grants));
        }

        @Override
        public TokenIssueResult authenticateUserAndIssueToken(String userName, byte[] credential, long expiresInSeconds) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RequestRecorder {
        private final String method;
        private final String path;
        private final String authorization;
        private final byte[] body;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private RequestRecorder(String method, String path, String authorization, String body) {
            this.method = method;
            this.path = path;
            this.authorization = authorization;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        private HttpServletRequest proxy() {
            return stub(HttpServletRequest.class, (proxy, invokedMethod, args) -> switch (invokedMethod.getName()) {
                case "getMethod" -> method;
                case "getRequestURI", "getPathInfo" -> path;
                case "getHeader" -> switch ((String) args[0]) {
                    case "Authorization" -> authorization;
                    default -> null;
                };
                case "getInputStream" -> new ByteArrayServletInputStream(body);
                case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                }
                case "getAttribute" -> attributes.get((String) args[0]);
                case "toString" -> "HttpServletRequest[path=" + path + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(invokedMethod.toString());
            });
        }
    }

    private static final class ResponseRecorder {
        private int status;

        private HttpServletResponse proxy() {
            return stub(HttpServletResponse.class, (proxy, method, args) -> switch (method.getName()) {
                case "sendError" -> {
                    status = (int) args[0];
                    yield null;
                }
                case "toString" -> "HttpServletResponseRecorder";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.toString());
            });
        }
    }

    private static final class FilterChainRecorder implements FilterChain {
        private boolean invoked;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            invoked = true;
        }
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
