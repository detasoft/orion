package pro.deta.orion.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.TokenIssueResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_CREATED;
import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

@Singleton
public class OrionAdminServlet implements MapToUrlServlet {
    private static final String USERS_PATH = "/api/admin/users";
    private static final String REPOSITORIES_PATH = "/api/admin/repositories";
    private static final String TOKEN_PATH = "/api/admin/token";
    private static final String BASIC_PREFIX = "Basic ";
    private static final String BASIC_REALM = "orion-admin";
    private static final long DEFAULT_TOKEN_EXPIRES_IN_SECONDS = 900;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrionAccessControlService accessControlService;
    private final GitRepositoryProvider gitRepositoryProvider;

    @Inject
    public OrionAdminServlet(OrionAccessControlService accessControlService, GitRepositoryProvider gitRepositoryProvider) {
        this.accessControlService = accessControlService;
        this.gitRepositoryProvider = gitRepositoryProvider;
    }

    @Override
    public String servletPath() {
        return "/api/admin/*";
    }

    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        try {
            String pathInfo = req.getPathInfo();
            if (TOKEN_PATH.equals(pathInfo)) {
                issueToken(req, resp);
                return;
            }

            if (!"POST".equalsIgnoreCase(req.getMethod())) {
                resp.setStatus(SC_METHOD_NOT_ALLOWED);
                resp.setHeader("Allow", "POST");
                return;
            }
            if (pathInfo == null) {
                resp.sendError(SC_NOT_FOUND);
                return;
            }
            switch (pathInfo) {
                case USERS_PATH -> createOrUpdateUser(req, resp);
                case REPOSITORIES_PATH -> createRepository(req, resp);
                default -> resp.sendError(SC_NOT_FOUND);
            }
        } catch (JsonProcessingException e) {
            resp.sendError(SC_BAD_REQUEST, "Invalid JSON request");
        } catch (IllegalArgumentException | IllegalStateException e) {
            resp.sendError(SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void issueToken(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(SC_METHOD_NOT_ALLOWED);
            resp.setHeader("Allow", "POST");
            return;
        }

        BasicCredentials credentials = basicCredentialsFrom(req);
        if (credentials == null) {
            sendBasicAuthenticationRequired(resp);
            return;
        }

        AdminTokenRequest request = tokenRequest(req);
        long expiresInSeconds = request.expiresInSecondsOrDefault();
        TokenIssueResult token = accessControlService.authenticateUserAndIssueToken(
                credentials.username(),
                credentials.password().getBytes(StandardCharsets.UTF_8),
                expiresInSeconds);
        switch (token) {
            case TokenIssueResult.Failure ignored -> sendBasicAuthenticationRequired(resp);
            case TokenIssueResult.Success(var value, var expiresAtEpochSecond) -> {
                resp.setStatus(SC_OK);
                resp.setContentType("application/json");
                objectMapper.writeValue(
                        resp.getWriter(),
                        new AdminTokenResponse(value, "Bearer", expiresInSeconds, expiresAtEpochSecond));
            }
        }
    }

    private void createOrUpdateUser(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AdminUserRequest request = objectMapper.readValue(req.getInputStream(), AdminUserRequest.class);
        accessControlService.createOrUpdateUser(request.toUserUpdate());
        writeOk(resp);
    }

    private void createRepository(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AdminRepositoryRequest request = objectMapper.readValue(req.getInputStream(), AdminRepositoryRequest.class);
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Repository name is required");
        }
        gitRepositoryProvider.findOrCreate(request.name()).valueOrFailure("Cannot create repository " + request.name());
        writeOk(resp);
    }

    private void writeOk(HttpServletResponse resp) throws IOException {
        resp.setStatus(SC_CREATED);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"status\":\"ok\"}");
    }

    private BasicCredentials basicCredentialsFrom(HttpServletRequest req) {
        String authorization = req.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
            return null;
        }
        String encodedCredentials = authorization.substring(BASIC_PREFIX.length());
        if (encodedCredentials.isBlank()) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encodedCredentials), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int separator = decoded.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        String username = decoded.substring(0, separator);
        String password = decoded.substring(separator + 1);
        if (username.isBlank() || password.isEmpty()) {
            return null;
        }
        return new BasicCredentials(username, password);
    }

    private AdminTokenRequest tokenRequest(HttpServletRequest req) throws IOException {
        byte[] body = req.getInputStream().readAllBytes();
        if (body.length == 0) {
            return AdminTokenRequest.DEFAULT;
        }
        return objectMapper.readValue(body, AdminTokenRequest.class);
    }

    private void sendBasicAuthenticationRequired(HttpServletResponse resp) throws IOException {
        resp.setHeader("WWW-Authenticate", "Basic realm=\"" + BASIC_REALM + "\"");
        resp.sendError(SC_UNAUTHORIZED);
    }

    private record BasicCredentials(String username, String password) {
    }

    public record AdminTokenRequest(Long expiresInSeconds) {
        private static final AdminTokenRequest DEFAULT = new AdminTokenRequest(null);

        private long expiresInSecondsOrDefault() {
            if (expiresInSeconds == null) {
                return DEFAULT_TOKEN_EXPIRES_IN_SECONDS;
            }
            if (expiresInSeconds <= 0) {
                throw new IllegalArgumentException("Token expiration must be positive");
            }
            return expiresInSeconds;
        }
    }

    public record AdminTokenResponse(
            String token,
            String tokenType,
            long expiresInSeconds,
            long expiresAtEpochSecond) {
    }

    public record AdminUserRequest(
            String id,
            String email,
            String publicKey,
            List<RepositoryGrantRequest> repositories) {
        private AccessControlUserUpdate toUserUpdate() {
            List<AccessControlCredentialUpdate> credentials = new ArrayList<>();
            if (publicKey != null && !publicKey.isBlank()) {
                credentials.add(new AccessControlCredentialUpdate(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, publicKey));
            }

            List<AccessControlRepositoryGrantUpdate> grants = new ArrayList<>();
            if (repositories != null) {
                for (RepositoryGrantRequest repository : repositories) {
                    grants.add(repository.toGrantUpdate());
                }
            }
            return new AccessControlUserUpdate(id, email, credentials, grants);
        }
    }

    public record RepositoryGrantRequest(
            String repository,
            boolean read,
            boolean write,
            boolean create,
            boolean force,
            String branch) {
        private AccessControlRepositoryGrantUpdate toGrantUpdate() {
            return new AccessControlRepositoryGrantUpdate(repository, read, write, create, force, branch);
        }
    }

    public record AdminRepositoryRequest(String name) {
    }
}
