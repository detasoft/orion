package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.schema.orion.RepositoryName;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.StorageManagement;
import pro.deta.orion.schema.orion.ConnectionReference;
import pro.deta.orion.schema.orion.S3StorageBinding;
import java.net.URI;
import java.util.Optional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OrionAdminCreateRepositoryRoute extends AbstractOrionHttpRoute {
    private final ObjectMapper objectMapper;
    private final NativeGitRepositoryProvider gitRepositoryProvider;
    private final StorageManagement storageManagement;

    @Inject
    public OrionAdminCreateRepositoryRoute(
            NativeGitRepositoryProvider gitRepositoryProvider,
            StorageManagement storageManagement,
            ObjectMapper objectMapper) {
        super(
                OrionAdminPaths.REPOSITORIES,
                OrionHttpRouteDefinition.Authorization.AUTHENTICATED,
                OrionHttpRouteDefinition.Method.GET,
                OrionHttpRouteDefinition.Method.POST);
        this.gitRepositoryProvider = gitRepositoryProvider;
        this.storageManagement = storageManagement;
        this.objectMapper = objectMapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        SecurityContext context = context(req);
        boolean admin = isAdmin(context);
        if (!admin && context.getUserIdentity().getOrganizationId().isEmpty()) {
            return OrionHttpResponse.empty(403);
        }
        List<RepositoryResponse> repositories = new ArrayList<>();
        for (String name : gitRepositoryProvider.repositoryNames()) {
            if (admin || RepositoryAccessRules.read().evaluate(context, RepositoryResource.of(name)).allowed()) {
                repositories.add(new RepositoryResponse(name));
            }
        }
        return OrionHttpResponse.ok(Map.of("repositories", repositories));
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        SecurityContext context = context(req);
        boolean admin = isAdmin(context);
        if (!admin && context.getUserIdentity().getOrganizationId().isEmpty()) {
            return OrionHttpResponse.empty(403);
        }
        AdminRepositoryRequest request = objectMapper.readValue(
                req.getInputStream(),
                AdminRepositoryRequest.class);
        if (request == null) {
            throw new HttpRequestValidationException("Repository request is required");
        }
        String repositoryName;
        try {
            repositoryName = RepositoryName.parse(request.name()).value();
        } catch (IllegalArgumentException failure) {
            throw new HttpRequestValidationException("Invalid repository name");
        }
        Optional<S3StorageBinding> storage = Optional.empty();
        if (request.connection() != null || request.location() != null || request.connectionScope() != null) {
            try {
                storage = Optional.of(new S3StorageBinding(new ConnectionReference(
                        ConnectionReference.Scope.valueOf(request.connectionScope().toUpperCase(java.util.Locale.ROOT)),
                        request.connection()), URI.create(request.location())));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new HttpRequestValidationException("Invalid S3 storage binding");
            }
        }
        StorageManagement.Outcome<StorageManagement.Created> result =
                storageManagement.createRepository(context, repositoryName, storage);
        if (result instanceof StorageManagement.Failure<StorageManagement.Created> failure) {
            return storageFailure(failure);
        }
        boolean created = ((StorageManagement.Success<StorageManagement.Created>) result).value().created();
        Map<String, Object> body = Map.of("status", "ok", "created", created);
        return created ? OrionHttpResponse.created(body) : OrionHttpResponse.ok(body);
    }

    static OrionHttpResponse storageFailure(StorageManagement.Failure<?> failure) {
        int status = switch (failure.code()) {
            case DENIED -> 403;
            case INVALID -> 400;
            case CONFLICT -> 409;
            case UNAVAILABLE -> 500;
            case STORAGE_RETRY -> 503;
        };
        return OrionHttpResponse.json(status, Map.of("error", failure.message(),
                "retryable", failure.code() == StorageManagement.FailureCode.STORAGE_RETRY));
    }

    private static SecurityContext context(HttpServletRequest request) {
        Object attribute = request.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        return attribute instanceof SecurityContext context ? context : SecurityContext.createContext();
    }

    private static boolean isAdmin(SecurityContext context) {
        return ApplicationAccessRules.admin().evaluate(context, ApplicationAdminResource.applicationAdmin()).allowed();
    }

    public record AdminRepositoryRequest(String name, String connectionScope, String connection, String location) {
    }

    public record RepositoryResponse(String name) {
    }
}
