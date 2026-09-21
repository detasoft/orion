package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.id.PackId;
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

final class OrionGitPackfileHandler {
    public static final String PACK_CONTENT_TYPE =
            "application/x-git-packed-objects";

    private static final OrionHttpRouteDefinition DEFINITION =
            new OrionHttpRouteDefinition("objects/pack/*", GIT, GET);
    private static final String PACK_SUFFIX = ".pack";

    private final NativeGitRepositoryProvider repositoryProvider;

    OrionGitPackfileHandler(
            NativeGitRepositoryProvider repositoryProvider) {
        this.repositoryProvider = Objects.requireNonNull(
                repositoryProvider,
                "repositoryProvider");
    }

    void handle(OrionHttpExchange exchange, String repositoryName, String fileName) throws IOException {
        if (!exchange.accepts(DEFINITION)) {
            return;
        }
        HttpServletRequest req = exchange.request();
        if (!fileName.endsWith(PACK_SUFFIX)) {
            exchange.sendError(SC_BAD_REQUEST);
            return;
        }
        String packId = fileName.substring(0, fileName.length() - PACK_SUFFIX.length());
        if (!isLowercaseSha1(packId)) {
            exchange.sendError(SC_BAD_REQUEST);
            return;
        }
        if (!canRead(req, repositoryName)) {
            exchange.sendError(SC_FORBIDDEN);
            return;
        }
        Optional<NativeGitRepository> repository =
                repository(repositoryName);
        if (repository.isEmpty()) {
            exchange.sendError(SC_NOT_FOUND);
            return;
        }
        Optional<Long> sent = repository.get().storage().readPack(new PackId(packId),
                (size, input) -> {
                    OrionHttpResponse metadata = OrionHttpResponse.stream(SC_OK, PACK_CONTENT_TYPE)
                            .withHeader("Cache-Control", "no-cache")
                            .withContentLength(size);
                    return input.newInputStream().transferTo(exchange.openResponseBody(metadata));
                });
        if (sent.isEmpty()) {
            exchange.sendError(SC_NOT_FOUND);
        }
    }

    private Optional<NativeGitRepository> repository(
            String repositoryName) {
        if (!repositoryProvider.isPublicRepositoryName(repositoryName)) {
            return Optional.empty();
        }
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

}
