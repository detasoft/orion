package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

@Singleton
public class OrionAuthorizationFilter implements Filter {
    public static final String SECURITY_CONTEXT_ATTRIBUTE = OrionAuthorizationFilter.class.getName() + ".securityContext";
    private static final String ADMIN_PATH = "/api/admin";
    private static final String TOKEN_PATH = "/api/admin/token";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<String> PUBLIC_PATHS = List.of(
            TOKEN_PATH,
            "/.well-known/acme-challenge/*");

    private final OrionAccessControlService accessControlService;

    @Inject
    public OrionAuthorizationFilter(OrionAccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    public String filterPath() {
        return "/*";
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            SecurityContext securityContext = securityContextFor(httpRequest);
            httpRequest.setAttribute(SECURITY_CONTEXT_ATTRIBUTE, securityContext);

            try {
                if (!isPublic(httpRequest)) {
                    accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());
                }
                if (requiresAdminAuthorization(httpRequest)) {
                    accessEnforcer().require(securityContext, ApplicationAdminResource.applicationAdmin(), ApplicationAccessRules.admin());
                }
            } catch (OrionSecurityException e) {
                httpResponse.sendError(SC_FORBIDDEN);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = requestPath(request);
        for (String publicPath : PUBLIC_PATHS) {
            if (WildcardMatcher.matches(publicPath, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresAdminAuthorization(HttpServletRequest request) {
        String path = requestPath(request);
        return path != null
                && (ADMIN_PATH.equals(path) || path.startsWith(ADMIN_PATH + "/"))
                && !TOKEN_PATH.equals(path);
    }

    private String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri != null && !requestUri.isBlank()) {
            return requestUri;
        }
        return request.getPathInfo();
    }

    private SecurityContext securityContextFor(HttpServletRequest req) {
        SecurityContext securityContext = SecurityContext.createContext().withRequestId(req.toString());
        String bearerToken = bearerTokenFrom(req);
        if (bearerToken == null) {
            return securityContext;
        }

        AuthenticationResult authentication = accessControlService.authenticateToken(bearerToken.getBytes(StandardCharsets.UTF_8));
        if (authentication instanceof AuthenticationResult.Success(var userIdentity)) {
            securityContext.withUserIdentity(userIdentity);
        }
        return securityContext;
    }

    private String bearerTokenFrom(HttpServletRequest req) {
        String authorization = req.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        if (token.isBlank()) {
            return null;
        }
        return token;
    }
}
