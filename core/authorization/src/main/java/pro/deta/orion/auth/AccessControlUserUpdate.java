package pro.deta.orion.auth;

import java.util.List;

public record AccessControlUserUpdate(
        String id,
        String email,
        List<AccessControlCredentialUpdate> credentials,
        List<AccessControlRepositoryGrantUpdate> repositories) {
    public AccessControlUserUpdate {
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }
}
