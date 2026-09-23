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
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.TokenAuthenticationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Singleton
public class OrionAuthorizationFilter implements Filter {
    public static final String SECURITY_CONTEXT_ATTRIBUTE = OrionAuthorizationFilter.class.getName() + ".securityContext";
    private static final String BEARER_PREFIX = "Bearer ";

    private final OrionAccessControlService accessControlService;
    private final OrionOidcRoute oidc;

    @Inject
    public OrionAuthorizationFilter(OrionAccessControlService accessControlService, OrionOidcRoute oidc) {
        this.accessControlService = accessControlService;
        this.oidc = oidc;
    }

    public String filterPath() {
        return "/*";
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            SecurityContext context = securityContextFor(httpRequest);
            httpRequest.setAttribute(SECURITY_CONTEXT_ATTRIBUTE, context);
            if (context.getUserIdentity().getOrganizationId().isPresent()
                    && response instanceof HttpServletResponse httpResponse) {
                String cookie = oidc.recordActivity(httpRequest, context);
                if (cookie != null) {
                    httpResponse.addHeader("Set-Cookie", cookie);
                    httpResponse.setHeader("Cache-Control", "no-store");
                }
            }
        }
        chain.doFilter(request, response);
    }

    private SecurityContext securityContextFor(HttpServletRequest req) {
        SecurityContext securityContext = SecurityContext.createContext().withRequestId(req.toString());
        String bearerToken = bearerTokenFrom(req);
        if (bearerToken == null) {
            return securityContext;
        }

        TokenAuthenticationResult authentication = accessControlService.verifyToken(
                bearerToken.getBytes(StandardCharsets.UTF_8));
        if (authentication instanceof TokenAuthenticationResult.Success(var userIdentity, var tokenIdentity)) {
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
