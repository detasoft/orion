package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OrionAdminCreateOrUpdateUserRoute extends BaseAdminRoute {
    private final ObjectMapper objectMapper;
    private final OrionAccessControlService accessControlService;

    @Inject
    public OrionAdminCreateOrUpdateUserRoute(OrionAccessControlService accessControlService, ObjectMapper objectMapper) {
        super(OrionAdminPaths.USERS, "POST");
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        AdminUserRequest request = objectMapper.readValue(req.getInputStream(), AdminUserRequest.class);
        accessControlService.createOrUpdateUser(request.toUserUpdate());
        return OrionHttpResponse.created(Map.of("status", "ok"));
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
}
