package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.pack.PublishedPackContent;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

public final class OrionGitPackfileRoute implements OrionHttpRoute {
    public static final String URL_PATTERN = "/r/*/objects/pack/*.pack";
    public static final String PACK_CONTENT_TYPE =
            "application/x-git-packed-objects";

    private static final List<String> ALLOWED_METHODS = List.of("GET");
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
    public String urlPattern() {
        return URL_PATTERN;
    }

    @Override
    public String authorization() {
        return "git";
    }

    @Override
    public List<String> allowedMethods() {
        return ALLOWED_METHODS;
    }

    @Override
    public void handle(
            HttpServletRequest req,
            HttpServletResponse resp,
            OrionHttpResponseWriter responseWriter)
            throws IOException, ServletException {
        if (!"GET".equals(req.getMethod().toUpperCase(Locale.ROOT))) {
            resp.setHeader("Allow", String.join(", ", ALLOWED_METHODS));
            resp.setStatus(SC_METHOD_NOT_ALLOWED);
            return;
        }
        Optional<RouteMatch> match = match(routePath(req));
        if (match.isEmpty()) {
            resp.sendError(SC_BAD_REQUEST);
            return;
        }
        if (!canRead(req, match.get().repositoryName())) {
            resp.sendError(SC_FORBIDDEN);
            return;
        }
        Optional<NativeGitRepository> repository =
                repository(match.get().repositoryName());
        if (repository.isEmpty()) {
            resp.sendError(SC_NOT_FOUND);
            return;
        }
        Optional<PublishedPackContent> pack =
                repository.get().openPublishedPack(match.get().packId());
        if (pack.isEmpty()) {
            resp.sendError(SC_NOT_FOUND);
            return;
        }
        try (PublishedPackContent content = pack.get()) {
            resp.setStatus(SC_OK);
            resp.setContentType(PACK_CONTENT_TYPE);
            resp.setHeader("Cache-Control", "no-cache");
            resp.setContentLengthLong(content.manifest().packBytes());
            content.input().transferTo(resp.getOutputStream());
        }
    }

    private Optional<NativeGitRepository> repository(
            String repositoryName) {
        return switch (repositoryProvider.find(repositoryName)) {
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
                    RepositoryResource.of(repositoryResourceName(repositoryName)),
                    RepositoryAccessRules.read());
            return true;
        } catch (OrionSecurityException error) {
            return false;
        }
    }

    private static String repositoryResourceName(String repositoryName) {
        String normalized = repositoryName;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceFirst("\\.git$", "");
        return normalized;
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
        String repositoryName = path.substring(
                ROUTE_PREFIX.length(),
                packPath);
        String fileName = path.substring(packPath + PACK_PATH.length());
        if (repositoryName.isBlank()
                || !fileName.endsWith(PACK_SUFFIX)) {
            return Optional.empty();
        }
        String packId = fileName.substring(
                0,
                fileName.length() - PACK_SUFFIX.length());
        if (!isLowercaseSha1(packId)) {
            return Optional.empty();
        }
        return Optional.of(new RouteMatch(repositoryName, packId));
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
