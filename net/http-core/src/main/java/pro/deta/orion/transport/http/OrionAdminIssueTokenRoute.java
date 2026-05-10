package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.TokenIssueResult;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

public class OrionAdminIssueTokenRoute extends AbstractOrionHttpRoute {
    private static final String BASIC_PREFIX = "Basic ";
    private static final String BASIC_REALM = "orion-admin";
    private static final long DEFAULT_TOKEN_EXPIRES_IN_SECONDS = 900;

    private final ObjectMapper objectMapper;
    private final OrionAccessControlService accessControlService;

    @Inject
    public OrionAdminIssueTokenRoute(OrionAccessControlService accessControlService, ObjectMapper objectMapper) {
        super(OrionAdminPaths.TOKEN, "POST");
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        BasicCredentials credentials = basicCredentialsFrom(req);
        if (credentials == null) {
            return basicAuthenticationRequired();
        }

        AdminTokenRequest request = tokenRequest(req);
        long expiresInSeconds = request.expiresInSecondsOrDefault();
        TokenIssueResult token = accessControlService.authenticateUserAndIssueToken(
                credentials.username(),
                credentials.password().getBytes(StandardCharsets.UTF_8),
                expiresInSeconds);
        return switch (token) {
            case TokenIssueResult.Failure ignored -> basicAuthenticationRequired();
            case TokenIssueResult.Success(var value, var expiresAtEpochSecond) -> OrionHttpResponse.ok(
                    new AdminTokenResponse(value, "Bearer", expiresInSeconds, expiresAtEpochSecond));
        };
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

    private OrionHttpResponse basicAuthenticationRequired() {
        return OrionHttpResponse.empty(SC_UNAUTHORIZED)
                .withHeader("WWW-Authenticate", "Basic realm=\"" + BASIC_REALM + "\"");
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
}
