package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.acl.OrganizationAccounts;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OidcProvider;
import pro.deta.orion.schema.orion.OrganizationId;

import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns bounded browser-bound login attempts; persistent accounts and invitation consumption belong to the ACL. */
@Singleton
public final class OrionOidcRoute extends AbstractOrionHttpRoute {
    private static final String COOKIE = "__Host-orion-oidc";
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Map<String, Verified> verified = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final OrganizationAccounts accounts;
    private final OrionAccessControlServiceImpl acl;
    private final OrionDesiredState desired;
    private final ConfigurationSecrets secrets;
    private final ObjectMapper mapper;
    private final OidcClient client;

    @Inject
    public OrionOidcRoute(OrganizationAccounts accounts, OrionAccessControlServiceImpl acl,
            OrionDesiredState desired, ConfigurationSecrets secrets, ObjectMapper mapper) {
        super("/api/auth/**", OrionHttpRouteDefinition.Method.GET, OrionHttpRouteDefinition.Method.POST);
        this.accounts = accounts;
        this.acl = acl;
        this.desired = desired;
        this.secrets = secrets;
        this.mapper = mapper;
        this.client = new OidcClient(mapper);
    }

    @Override
    public void handle(OrionHttpExchange exchange) throws IOException {
        OrionHttpResponse response;
        try {
            HttpServletRequest request = exchange.request();
            response = switch (exchange.path()) {
                case "/api/auth/me" -> exchange.method() == OrionHttpRouteDefinition.Method.GET
                        ? me(request) : OrionHttpResponse.empty(405);
                case "/api/auth/providers" -> exchange.method() == OrionHttpRouteDefinition.Method.GET
                        ? providers(request) : OrionHttpResponse.empty(405);
                case "/api/auth/oidc/callback" -> exchange.method() == OrionHttpRouteDefinition.Method.GET
                        ? callback(request) : OrionHttpResponse.empty(405);
                case "/api/auth/oidc/start", "/api/auth/oidc/profile", "/api/auth/oidc/complete" -> {
                    if (exchange.method() != OrionHttpRouteDefinition.Method.POST) {
                        yield OrionHttpResponse.empty(405);
                    }
                    requireOrigin(request);
                    byte[] bytes = request.getInputStream().readNBytes(16385);
                    if (bytes.length > 16384) {
                        throw new IllegalArgumentException("Request is too large");
                    }
                    JsonNode body = mapper.readTree(bytes);
                    if (body == null || !body.isObject()) {
                        throw new IllegalArgumentException("JSON object is required");
                    }
                    yield switch (exchange.path()) {
                        case "/api/auth/oidc/start" -> start(body);
                        case "/api/auth/oidc/profile" -> profile(request, body);
                        default -> complete(request, body);
                    };
                }
                default -> OrionHttpResponse.empty(404);
            };
        } catch (AccessControlConcurrentUpdateException conflict) {
            response = OrionHttpResponse.text(409, "Configuration changed. Please retry.");
        } catch (Exception failure) {
            response = OrionHttpResponse.text(400, "Sign-in is unavailable. Check your invitation or start again.");
        }
        exchange.send(response.withHeader("Cache-Control", "no-store").withHeader("Referrer-Policy", "no-referrer"));
    }

    private OrionHttpResponse me(HttpServletRequest request) {
        Object value = request.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        if (!(value instanceof SecurityContext context) || context.getUserIdentity().isAnonymous()) {
            return OrionHttpResponse.empty(401);
        }
        return OrionHttpResponse.ok(Map.of("userId", context.getUserIdentity().getUserId(),
                "organization", context.getUserIdentity().getOrganizationId().map(OrganizationId::value).orElse(""),
                "admin", ApplicationAccessRules.admin()
                        .evaluate(context, ApplicationAdminResource.applicationAdmin()).allowed()));
    }

    private OrionHttpResponse providers(HttpServletRequest request) {
        OrganizationId organization = new OrganizationId(request.getParameter("organization"));
        List<String> providers = new ArrayList<>();
        for (OidcProvider provider : accounts.organization(organization).oidcProviders()) {
            providers.add(provider.id());
        }
        return OrionHttpResponse.ok(Map.of("providers", providers));
    }

    private OrionHttpResponse start(JsonNode body) throws Exception {
        OrganizationId organization = new OrganizationId(body.path("organization").asText());
        OidcProvider provider = provider(organization, body.path("provider").asText());
        String invitation = body.path("invitation").asText("");
        if (!invitation.isEmpty()) {
            accounts.invitation(organization, invitation);
        }
        OidcClient.Metadata metadata = client.discover(provider);
        String state = randomToken();
        String browser = randomToken();
        Attempt attempt = new Attempt(organization, provider, metadata, invitation, browser,
                randomToken(), randomToken(), publicOrigin().resolve("/api/auth/oidc/callback"),
                Instant.now().plusSeconds(600).getEpochSecond());
        synchronized (this) {
            cleanup();
            if (attempts.size() + verified.size() >= 1024) {
                throw new IllegalStateException("Too many sign-in attempts");
            }
            attempts.put(state, attempt);
        }
        URI authorization = client.authorize(provider, metadata, attempt.callback(), state,
                attempt.nonce(), attempt.verifier());
        return OrionHttpResponse.ok(Map.of("url", authorization.toString()))
                .withHeader("Set-Cookie", cookie(browser, 600));
    }

    private OrionHttpResponse callback(HttpServletRequest request) throws Exception {
        Attempt attempt;
        synchronized (this) {
            cleanup();
            String state = request.getParameter("state");
            attempt = attempts.get(state);
            if (attempt == null || !attempt.browser().equals(browser(request))) {
                throw new IllegalArgumentException("Invalid login state");
            }
            attempts.remove(state);
        }
        requireCurrent(attempt);
        String code = request.getParameter("code");
        if (request.getParameter("error") != null || code == null || code.isBlank() || code.length() > 8192) {
            throw new IllegalArgumentException("Authorization code is required");
        }
        String issuer = request.getParameter("iss");
        if (issuer != null && !issuer.equals(attempt.provider().issuer().toString())) {
            throw new IllegalArgumentException("Authorization issuer mismatch");
        }
        char[] secret = secrets.resolveOrganization(desired.current().document(), attempt.organization(),
                attempt.provider().secret());
        OidcClient.Identity identity;
        try {
            identity = client.exchange(attempt.provider(), attempt.metadata(), attempt.callback(), code,
                    attempt.nonce(), attempt.verifier(), secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
        requireCurrent(attempt);
        AccessControl.User linked = accounts.linkedUser(attempt.organization(),
                attempt.provider().issuer().toString(), identity.subject());
        if (linked == null && (attempt.invitation().isEmpty()
                || !accounts.invitation(attempt.organization(), attempt.invitation()).email().equals(identity.email()))) {
            throw new IllegalArgumentException("An invitation for this email is required");
        }
        String ticket = randomToken();
        synchronized (this) {
            cleanup();
            if (attempt.expiresAt() <= Instant.now().getEpochSecond() || verified.size() + attempts.size() >= 1024) {
                throw new IllegalArgumentException("Login expired");
            }
            verified.put(ticket, new Verified(attempt, identity));
        }
        return OrionHttpResponse.empty(303)
                .withHeader("Location", publicOrigin().resolve("/") + "#onboarding=" + ticket);
    }

    private synchronized OrionHttpResponse profile(HttpServletRequest request, JsonNode body) {
        Verified login = verified(request, body);
        AccessControl.User user = accounts.linkedUser(login.attempt().organization(),
                login.attempt().provider().issuer().toString(), login.identity().subject());
        return OrionHttpResponse.ok(Map.of("first", user == null ? login.identity().first() : safe(user.getFirst()),
                "last", user == null ? login.identity().last() : safe(user.getLast()),
                "email", login.identity().email(), "setup", user == null));
    }

    private synchronized OrionHttpResponse complete(HttpServletRequest request, JsonNode body) {
        Verified login = verified(request, body);
        Attempt attempt = login.attempt();
        String issuer = attempt.provider().issuer().toString();
        AccessControl.User linked = accounts.linkedUser(attempt.organization(), issuer, login.identity().subject());
        String userId = linked == null
                ? accounts.accept(attempt.organization(), attempt.invitation(), login.identity().email(), attempt.provider(),
                        login.identity().subject(), body.path("first").asText(), body.path("last").asText())
                : linked.getId();
        TokenIssueResult result = acl.issueOrganizationToken(attempt.organization(), userId,
                issuer, login.identity().subject());
        if (!(result instanceof TokenIssueResult.Success token)) {
            throw new IllegalStateException("Account is unavailable");
        }
        verified.remove(body.path("ticket").asText());
        return OrionHttpResponse.ok(Map.of("token", token.token(), "expiresAt", token.expiresAtEpochSecond(),
                        "organization", attempt.organization().value()))
                .withHeader("Set-Cookie", cookie("", 0));
    }

    private Verified verified(HttpServletRequest request, JsonNode body) {
        cleanup();
        Verified login = verified.get(body.path("ticket").asText());
        if (login == null || !login.attempt().browser().equals(browser(request))) {
            throw new IllegalArgumentException("Login is unavailable");
        }
        requireCurrent(login.attempt());
        return login;
    }

    private void requireCurrent(Attempt attempt) {
        if (!provider(attempt.organization(), attempt.provider().id()).equals(attempt.provider())
                || !publicOrigin().resolve("/api/auth/oidc/callback").equals(attempt.callback())) {
            throw new IllegalArgumentException("OIDC configuration changed");
        }
    }

    private OidcProvider provider(OrganizationId organization, String id) {
        for (OidcProvider provider : accounts.organization(organization).oidcProviders()) {
            if (provider.id().equals(id)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Provider is unavailable");
    }

    URI publicOrigin() {
        URI uri = desired.current().document().system().https().orElseThrow().publicUrl();
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalStateException("Configure the public HTTPS origin first");
        }
        return uri.resolve("/");
    }

    private void requireOrigin(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.split(";", 2)[0].equalsIgnoreCase("application/json")) {
            throw new IllegalArgumentException("JSON is required");
        }
        String origin = request.getHeader("Origin");
        if (origin == null || !publicOrigin().equals(URI.create(origin).resolve("/"))) {
            throw new IllegalArgumentException("Same-origin request is required");
        }
    }

    private void cleanup() {
        long now = Instant.now().getEpochSecond();
        attempts.values().removeIf(attempt -> attempt.expiresAt() <= now);
        verified.values().removeIf(login -> login.attempt().expiresAt() <= now);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String browser(HttpServletRequest request) {
        String value = "";
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals(COOKIE)) {
                    if (!value.isEmpty()) {
                        return "";
                    }
                    value = cookie.getValue();
                }
            }
        }
        return value;
    }

    private static String cookie(String value, int seconds) {
        return COOKIE + "=" + value + "; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=" + seconds;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record Attempt(OrganizationId organization, OidcProvider provider, OidcClient.Metadata metadata,
            String invitation, String browser, String nonce, String verifier, URI callback, long expiresAt) { }
    private record Verified(Attempt attempt, OidcClient.Identity identity) { }
}
