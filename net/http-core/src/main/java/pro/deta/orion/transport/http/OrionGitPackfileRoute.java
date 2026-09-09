package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.pack.PublishedPackContent;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.GIT;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.GET;

public final class OrionGitPackfileRoute implements OrionHttpRoute {
    public static final String URL_PATTERN = "/r/*/objects/pack/*.pack";
    public static final String PACK_CONTENT_TYPE =
            "application/x-git-packed-objects";

    private static final OrionHttpRouteDefinition DEFINITION =
            new OrionHttpRouteDefinition(URL_PATTERN, GIT, GET);
    private static final String ROUTE_PREFIX = "/r/";
    private static final String PACK_PATH = "/objects/pack/";
    private static final String PACK_SUFFIX = ".pack";

    private final NativeGitRepositoryProvider repositoryProvider;

    @Inject
    public OrionGitPackfileRoute(
            NativeGitRepositoryProvider repositoryProvider) {
        this.repositoryProvider = Objects.requireNonNull(
                repositoryProvider,
                "repositoryProvider");
    }

    @Override
    public OrionHttpRouteDefinition definition() {
        return DEFINITION;
    }

    @Override
    public void handle(
            OrionHttpExchange exchange) throws IOException {
        HttpServletRequest req = exchange.request();
        Optional<RouteMatch> match = match(routePath(req));
        if (match.isEmpty()) {
            exchange.sendError(SC_BAD_REQUEST);
            return;
        }
        if (!canRead(req, match.get().repositoryName())) {
            exchange.sendError(SC_FORBIDDEN);
            return;
        }
        Optional<NativeGitRepository> repository =
                repository(match.get().repositoryName());
        if (repository.isEmpty()) {
            exchange.sendError(SC_NOT_FOUND);
            return;
        }
        Optional<PublishedPackContent> pack =
                repository.get().openPublishedPack(match.get().packId());
        if (pack.isEmpty()) {
            exchange.sendError(SC_NOT_FOUND);
            return;
        }
        try (PublishedPackContent content = pack.get()) {
            OrionHttpResponse metadata = OrionHttpResponse.stream(SC_OK, PACK_CONTENT_TYPE)
                    .withHeader("Cache-Control", "no-cache")
                    .withContentLength(content.manifest().packBytes());
            content.input().transferTo(exchange.openResponseBody(metadata));
        }
    }

    private Optional<NativeGitRepository> repository(
            String repositoryName) {
        return switch (repositoryProvider.openForRead(repositoryName)) {
            case Result.Success(NativeGitRepository repository) ->
                    Optional.of(repository);
            case Result.Failure<NativeGitRepository> ignored ->
                    Optional.empty();
        };
    }

    private static boolean canRead(
            HttpServletRequest req,
            String repositoryName) {
        try {
            SecurityContext securityContext = securityContextFrom(req);
            accessEnforcer().require(
                    securityContext,
                    SubjectAccessRules.authenticated());
            accessEnforcer().require(
                    securityContext,
                    RepositoryResource.of(repositoryName),
                    RepositoryAccessRules.read());
            return true;
        } catch (OrionSecurityException error) {
            return false;
        }
    }

    private static SecurityContext securityContextFrom(
            HttpServletRequest req) {
        Object attribute = req.getAttribute(
                OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        if (attribute instanceof SecurityContext securityContext) {
            return securityContext;
        }
        return SecurityContext.createContext().withRequestId(req.toString());
    }

    private static Optional<RouteMatch> match(String path) {
        if (path == null || !path.startsWith(ROUTE_PREFIX)) {
            return Optional.empty();
        }
        int packPath = path.lastIndexOf(PACK_PATH);
        if (packPath <= ROUTE_PREFIX.length()) {
            return Optional.empty();
        }
        String repositoryPath = path.substring(
                ROUTE_PREFIX.length(),
                packPath);
        String fileName = path.substring(packPath + PACK_PATH.length());
        if (!fileName.endsWith(PACK_SUFFIX)) {
            return Optional.empty();
        }
        String packId = fileName.substring(
                0,
                fileName.length() - PACK_SUFFIX.length());
        if (!isLowercaseSha1(packId)) {
            return Optional.empty();
        }
        try {
            String repositoryName = RepositoryName.fromGitPath(repositoryPath).value();
            return Optional.of(new RouteMatch(repositoryName, packId));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static String routePath(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path != null && !path.isBlank()) {
            return path;
        }
        path = req.getRequestURI();
        String contextPath = req.getContextPath();
        if (path != null
                && contextPath != null
                && !contextPath.isBlank()
                && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path != null && !path.isBlank()) {
            return path;
        }
        return "/";
    }

    private static boolean isLowercaseSha1(String value) {
        if (value == null || value.length() != 40) {
            return false;
        }
        try {
            HexFormat.of().parseHex(value);
            return value.equals(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private record RouteMatch(String repositoryName, String packId) {
    }
}
