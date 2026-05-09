package pro.deta.orion.transport.http;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrionAdminServletTest {
    private static final String BASIC_USER = "root";
    private static final String BASIC_PASSWORD = "root-password";
    private static final String ISSUED_TOKEN = "issued-jwt";
    private static final long TOKEN_EXPIRES_AT = 1_800_000_000L;

    @Test
    void createsManagedUser() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionAdminServlet servlet = new OrionAdminServlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/users", null, """
                        {
                          "id": "client",
                          "email": "client@example.test",
                          "publicKey": "ssh-rsa AAAATEST client@example.test",
                          "repositories": [
                            {
                              "repository": "project",
                              "read": true,
                              "write": true,
                              "create": true,
                              "branch": "*"
                            }
                          ]
                        }
                        """),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_CREATED);
        assertThat(response.contentType).isEqualTo("application/json");
        assertThat(response.body.toString()).isEqualTo("{\"status\":\"ok\"}");

        AccessControlUserUpdate update = accessControlService.lastUpdate;
        assertThat(update.id()).isEqualTo("client");
        assertThat(update.email()).isEqualTo("client@example.test");
        assertThat(update.credentials()).hasSize(1);
        assertThat(update.credentials().getFirst().type()).isEqualTo(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY);
        assertThat(update.credentials().getFirst().value()).isEqualTo("ssh-rsa AAAATEST client@example.test");
        assertThat(update.repositories()).hasSize(1);
        assertThat(update.repositories().getFirst().repository()).isEqualTo("project");
        assertThat(update.repositories().getFirst().read()).isTrue();
        assertThat(update.repositories().getFirst().write()).isTrue();
        assertThat(update.repositories().getFirst().create()).isTrue();
        assertThat(update.repositories().getFirst().branch()).isEqualTo("*");
    }

    @Test
    void createsRepository() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionAdminServlet servlet = new OrionAdminServlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/repositories", null, """
                        {"name":"project"}
                        """),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_CREATED);
        assertThat(gitRepositoryProvider.lastCreatedRepository).isEqualTo("project");
        assertThat(accessControlService.lastUpdate).isNull();
    }

    @Test
    void issuesBearerTokenWithBasicCredentials() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionAdminServlet servlet = new OrionAdminServlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/token", basicAuth(BASIC_USER, BASIC_PASSWORD), """
                        {"expiresInSeconds":600}
                        """),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/json");
        assertThat(response.body.toString())
                .isEqualTo("{\"token\":\"issued-jwt\",\"tokenType\":\"Bearer\",\"expiresInSeconds\":600,\"expiresAtEpochSecond\":1800000000}");
        assertThat(accessControlService.lastTokenUserName).isEqualTo(BASIC_USER);
        assertThat(new String(accessControlService.lastTokenCredential, StandardCharsets.UTF_8)).isEqualTo(BASIC_PASSWORD);
        assertThat(accessControlService.lastTokenExpiresInSeconds).isEqualTo(600);
    }

    @Test
    void rejectsTokenRequestWithoutBasicCredentials() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionAdminServlet servlet = new OrionAdminServlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/token", null, ""),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.headers).containsEntry("WWW-Authenticate", "Basic realm=\"orion-admin\"");
        assertThat(accessControlService.lastTokenUserName).isNull();
    }

    private static String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServletRequest request(String method, String pathInfo, String authorization, String body) {
        return stub(HttpServletRequest.class, (proxy, invokedMethod, args) -> switch (invokedMethod.getName()) {
            case "getMethod" -> method;
            case "getPathInfo" -> pathInfo;
            case "getHeader" -> switch ((String) args[0]) {
                case "Authorization" -> authorization;
                default -> null;
            };
            case "getInputStream" -> new ByteArrayServletInputStream(body.getBytes(StandardCharsets.UTF_8));
            case "toString" -> "HttpServletRequest[pathInfo=" + pathInfo + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(invokedMethod.toString());
        });
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class RecordingAccessControlService implements OrionAccessControlService {
        private AccessControlUserUpdate lastUpdate;
        private String lastTokenUserName;
        private byte[] lastTokenCredential;
        private long lastTokenExpiresInSeconds;

        @Override
        public void addKeyToUser(String username, String publicKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
            lastUpdate = userUpdate;
        }

        @Override
        public AuthenticationResult authenticateUser(String userName, byte[] credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticationResult authenticateToken(byte[] token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenIssueResult authenticateUserAndIssueToken(String userName, byte[] credential, long expiresInSeconds) {
            lastTokenUserName = userName;
            lastTokenCredential = credential;
            lastTokenExpiresInSeconds = expiresInSeconds;
            if (!BASIC_USER.equals(userName)
                    || !BASIC_PASSWORD.equals(new String(credential, StandardCharsets.UTF_8))) {
                return TokenIssueResult.failure("authentication failed");
            }
            return TokenIssueResult.success(ISSUED_TOKEN, TOKEN_EXPIRES_AT);
        }
    }

    private static final class RecordingGitRepositoryProvider implements GitRepositoryProvider {
        private String lastCreatedRepository;

        @Override
        public boolean exists(String repositoryName) {
            return false;
        }

        @Override
        public Result<GitRepository> find(String repositoryName) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        }

        @Override
        public Result<GitRepository> findOrCreate(String repositoryName) {
            lastCreatedRepository = repositoryName;
            return new Result.Success<>(null);
        }
    }

    private static final class ResponseRecorder {
        private int status;
        private String contentType;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final StringWriter body = new StringWriter();

        private HttpServletResponse proxy() {
            return stub(HttpServletResponse.class, (proxy, method, args) -> switch (method.getName()) {
                case "setStatus" -> {
                    status = (int) args[0];
                    yield null;
                }
                case "sendError" -> {
                    status = (int) args[0];
                    yield null;
                }
                case "setHeader" -> {
                    headers.put((String) args[0], (String) args[1]);
                    yield null;
                }
                case "setContentType" -> {
                    contentType = (String) args[0];
                    yield null;
                }
                case "getWriter" -> new PrintWriter(body);
                case "toString" -> "HttpServletResponseRecorder";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.toString());
            });
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
