package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.StorageManagement;
import pro.deta.orion.schema.orion.v2.OrganizationId;

import java.io.IOException;
import java.util.Optional;

/** Exposes scoped S3 connections with write-only credentials and revision-checked updates. */
public final class OrionStorageConnectionsRoute extends AbstractOrionHttpRoute {
    private final StorageManagement management;
    private final ObjectMapper mapper;

    @Inject
    public OrionStorageConnectionsRoute(StorageManagement management, ObjectMapper mapper) {
        super("/api/storage/connections", OrionHttpRouteDefinition.Authorization.AUTHENTICATED,
                OrionHttpRouteDefinition.Method.GET, OrionHttpRouteDefinition.Method.POST);
        this.management = management;
        this.mapper = mapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        return response(management.connections(context(request), owner(request)));
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest request) throws IOException {
        ConnectionRequest input = mapper.readValue(request.getInputStream(), ConnectionRequest.class);
        if (input == null || input.connection() == null) {
            throw new HttpRequestValidationException("Connection request is required");
        }
        return response(management.saveConnection(context(request), owner(request), input.revision(),
                input.create(), input.connection()));
    }

    private static Optional<OrganizationId> owner(HttpServletRequest request) {
        String value = request.getParameter("organization");
        try {
            return value == null ? Optional.empty() : Optional.of(new OrganizationId(value));
        } catch (IllegalArgumentException invalid) {
            throw new HttpRequestValidationException("Invalid connection organization");
        }
    }

    private static SecurityContext context(HttpServletRequest request) {
        Object context = request.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        return context instanceof SecurityContext security ? security : SecurityContext.createContext();
    }

    private static OrionHttpResponse response(StorageManagement.Outcome<StorageManagement.Connections> result) {
        return result instanceof StorageManagement.Success<StorageManagement.Connections> success
                ? OrionHttpResponse.ok(success.value())
                : OrionAdminCreateRepositoryRoute.storageFailure((StorageManagement.Failure<?>) result);
    }

    public record ConnectionRequest(String revision, boolean create, StorageManagement.S3Input connection) {
        @Override
        public String toString() { return "ConnectionRequest[credentials=redacted]"; }
    }
}
