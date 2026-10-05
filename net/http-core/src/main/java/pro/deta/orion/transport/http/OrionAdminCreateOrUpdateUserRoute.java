package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.config.OrionConfigurationEdit;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.CredentialType;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OidcProvider;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.auth.AccessControlValidationException;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OrionAdminCreateOrUpdateUserRoute extends BaseAdminRoute {
    private final ObjectMapper objectMapper;
    private final OrionDesiredState desiredState;
    private final OrionAccessControlService accessControlService;
    private final OrionConfigurationEditor editor;

    @Inject
    public OrionAdminCreateOrUpdateUserRoute(OrionAccessControlService accessControlService,
            OrionConfigurationEditor editor, ObjectMapper objectMapper, OrionDesiredState desiredState) {
        super(OrionAdminPaths.USERS, OrionHttpRouteDefinition.Method.GET, OrionHttpRouteDefinition.Method.POST);
        this.desiredState = desiredState;
        this.editor = editor;
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        OrionDesiredState.Snapshot snapshot = desiredState.current();
        List<Map<String, Object>> users = new ArrayList<>();
        for (User user : snapshot.document().system().accessControl().users()) {
            List<Map<String, String>> bindings = new ArrayList<>();
            for (Credential credential : user.credentials()) {
                if (credential.type() == CredentialType.OIDC_SUBJECT) {
                    bindings.add(Map.of("issuer", credential.keyId(), "subject", credential.value()));
                }
            }
            users.add(Map.of("id", user.id(), "bindings", bindings));
        }
        return OrionHttpResponse.ok(Map.of("revision", snapshot.revision().orElse(""), "users", users))
                .withHeader("Cache-Control", "no-store");
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        JsonNode input = objectMapper.readTree(req.getInputStream());
        if (input != null && input.has("action")) {
            if (!"oidc-bindings".equals(input.path("action").asText())) {
                return OrionHttpResponse.text(400, "Unknown user operation");
            }
            return saveOidcBindings(input);
        }
        AdminUserRequest request = objectMapper.treeToValue(input, AdminUserRequest.class);
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

    private OrionHttpResponse saveOidcBindings(JsonNode input) {
        String revision = input.path("revision").asText();
        if (revision.isBlank()) return OrionHttpResponse.text(400, "Configuration revision is required");
        try (OrionConfigurationEdit edit = editor.edit(revision)) {
            edit.update(document -> replaceOidcBindings(document, input));
            edit.apply("Configure system OIDC bindings " + input.path("id").asText(), null);
            return OrionHttpResponse.ok(Map.of("saved", true)).withHeader("Cache-Control", "no-store");
        } catch (OrionConfigurationConcurrentUpdateException conflict) {
            return OrionHttpResponse.text(409, "Configuration changed. Reload users and try again.");
        } catch (IllegalArgumentException invalid) {
            return OrionHttpResponse.text(400, "Check the existing user and unique issuer/subject bindings.");
        }
    }

    private static OrionDocument replaceOidcBindings(OrionDocument document, JsonNode input) {
        AccessControl acl = document.system().accessControl();
        String id = input.path("id").asText();
        User selected = null;
        for (User user : acl.users()) {
            if (user.id().equals(id)) selected = user;
        }
        JsonNode bindings = input.path("bindings");
        if (selected == null || !bindings.isArray() || bindings.size() > 64) {
            throw new IllegalArgumentException("Existing user and bindings are required");
        }
        List<Credential> credentials = new ArrayList<>();
        for (Credential credential : selected.credentials()) {
            if (credential.type() != CredentialType.OIDC_SUBJECT) credentials.add(credential);
        }
        for (JsonNode binding : bindings) {
            String issuer = binding.path("issuer").asText();
            String subject = binding.path("subject").asText();
            new OidcProvider("validation", URI.create(issuer), "validation", "validation", 172800, 0);
            if (subject.isBlank() || subject.length() > 512) throw new IllegalArgumentException("Invalid subject");
            Credential candidate = new Credential(CredentialType.OIDC_SUBJECT, issuer, subject);
            if (credentials.contains(candidate)) throw new IllegalArgumentException("Duplicate binding");
            for (User user : acl.users()) {
                if (!user.id().equals(id) && user.credentials().contains(candidate)) {
                    throw new IllegalArgumentException("Binding already belongs to another user");
                }
            }
            credentials.add(candidate);
        }
        User updated = new User(selected.id(), selected.first(), selected.last(), selected.email(),
                credentials, selected.roles(), selected.grants());
        List<User> users = new ArrayList<>();
        for (User user : acl.users()) users.add(user.id().equals(id) ? updated : user);
        return document.replaceAccessControl(new AccessControl(users, acl.roles(), acl.grants()));
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
