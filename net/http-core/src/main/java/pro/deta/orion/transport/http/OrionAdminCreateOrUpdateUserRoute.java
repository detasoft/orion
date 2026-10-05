package pro.deta.orion.transport.http;

import pro.deta.orion.schema.acl.CredentialType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.config.OrionConfigurationEdit;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.auth.AccessControlValidationException;
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
    private final OrionConfigurationEditor editor;

    @Inject
    public OrionAdminCreateOrUpdateUserRoute(OrionAccessControlService accessControlService,
            OrionConfigurationEditor editor, ObjectMapper objectMapper) {
        super(OrionAdminPaths.USERS, OrionHttpRouteDefinition.Method.POST);
        this.editor = editor;
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        AdminUserRequest request = objectMapper.readValue(req.getInputStream(), AdminUserRequest.class);
        if (request == null) {
            throw new HttpRequestValidationException("User request is required");
        }
        try (OrionConfigurationEdit edit = editor.edit()) {
            accessControlService.createOrUpdateUser(edit, request.toUserUpdate());
            edit.apply("createOrUpdateUser() " + request.id(), new UserEmail(request.id(), request.email()));
        } catch (AccessControlValidationException failure) {
            throw new HttpRequestValidationException(failure.getMessage());
        }
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
                credentials.add(new AccessControlCredentialUpdate(CredentialType.OPENSSH_PUBLIC_KEY, publicKey));
            }

            List<AccessControlRepositoryGrantUpdate> grants = new ArrayList<>();
            if (repositories != null) {
                for (RepositoryGrantRequest repository : repositories) {
                    if (repository == null) {
                        throw new HttpRequestValidationException("Repository grant is required");
                    }
                    grants.add(repository.toGrantUpdate());
                }
            }
            return new AccessControlUserUpdate(id, email, credentials, grants);
        }
    }

    public record RepositoryGrantRequest(
            String repository,
            boolean read,
            boolean readWrite,
            boolean create,
            boolean force,
            String branch) {
        private AccessControlRepositoryGrantUpdate toGrantUpdate() {
            return new AccessControlRepositoryGrantUpdate(repository, read, readWrite, create, force, branch);
        }
    }
}
