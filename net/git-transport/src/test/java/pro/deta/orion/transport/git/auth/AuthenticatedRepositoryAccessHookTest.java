package pro.deta.orion.transport.git.auth;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedRepositoryAccessHookTest {
    @Test
    void receiveRejectsAnonymousUser() {
        AuthenticatedRepositoryAccessHook hook =
                new AuthenticatedRepositoryAccessHook(
                        SecurityContext.createContext());

        assertThatThrownBy(() -> hook.beforeReceive("project"))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("authenticated user");
    }

    @Test
    void receiveAllowsAuthenticatedUserWithoutRepositoryGrants() {
        AuthenticatedRepositoryAccessHook hook =
                new AuthenticatedRepositoryAccessHook(
                        authenticatedWithoutGrants());

        assertThatCode(() -> hook.beforeReceive("project"))
                .doesNotThrowAnyException();
    }

    @Test
    void readRequiresRepositoryGrant() {
        AuthenticatedRepositoryAccessHook denied =
                new AuthenticatedRepositoryAccessHook(
                        authenticatedWithoutGrants());
        AuthenticatedRepositoryAccessHook allowed =
                new AuthenticatedRepositoryAccessHook(
                        repositorySecurityContext(
                                "project",
                                false,
                                false));

        assertThatThrownBy(() -> denied.beforeRead("project"))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("repository read");
        assertThatCode(() -> allowed.beforeRead("project"))
                .doesNotThrowAnyException();
    }

    @Test
    void writeRequiresRepositoryWriteGrant() {
        AuthenticatedRepositoryAccessHook denied =
                new AuthenticatedRepositoryAccessHook(
                        repositorySecurityContext(
                                "project",
                                false,
                                true));
        AuthenticatedRepositoryAccessHook allowed =
                new AuthenticatedRepositoryAccessHook(
                        repositorySecurityContext(
                                "project",
                                true,
                                false));

        assertThatThrownBy(() -> denied.beforeWrite("project"))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("repository write");
        assertThatCode(() -> allowed.beforeWrite("project"))
                .doesNotThrowAnyException();
    }

    @Test
    void createRequiresRepositoryCreateGrant() {
        AuthenticatedRepositoryAccessHook denied =
                new AuthenticatedRepositoryAccessHook(
                        repositorySecurityContext(
                                "project",
                                true,
                                false));
        AuthenticatedRepositoryAccessHook allowed =
                new AuthenticatedRepositoryAccessHook(
                        repositorySecurityContext(
                                "project",
                                false,
                                true));

        assertThatThrownBy(() -> denied.beforeCreate("project"))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("repository create");
        assertThatCode(() -> allowed.beforeCreate("project"))
                .doesNotThrowAnyException();
    }

    @Test
    void repositoryResourceIgnoresGitSuffix() {
        AuthenticatedRepositoryAccessHook hook =
                new AuthenticatedRepositoryAccessHook(
                        repositorySecurityContext(
                                "team/project",
                                false,
                                true));

        assertThatCode(() -> hook.beforeRead("/team/project.git"))
                .doesNotThrowAnyException();
        assertThatCode(() -> hook.beforeCreate("/team/project.git"))
                .doesNotThrowAnyException();
    }

    @Test
    void strictRepositoryNamesRejectHttpUnsafePaths() {
        AuthenticatedRepositoryAccessHook hook =
                new AuthenticatedRepositoryAccessHook(
                        repositorySecurityContext(
                                "team/project",
                                false,
                                true),
                        true);

        assertThatThrownBy(() -> hook.beforeRead("team/../project.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Git repository path");
        assertThatThrownBy(() -> hook.beforeRead("team\\project.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Git repository path");
        assertThatThrownBy(() -> hook.beforeRead("team/project.git\0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Git repository path");
    }

    private static SecurityContext authenticatedWithoutGrants() {
        return SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl(
                        "git-user",
                        List.of()));
    }

    private static SecurityContext repositorySecurityContext(
            String repositoryName,
            boolean write,
            boolean create) {
        AccessControlDraft.Grant grant =
                new AccessControlDraft.Grant(
                        "repository",
                        new ArrayList<>())
                        .addKey(
                                AccessControl.GrantKey.REPOSITORY,
                                repositoryName);
        if (write) {
            grant.addKey(
                    AccessControl.GrantKey.WRITE,
                    AccessControl.TRUE_STRING);
        }
        if (create) {
            grant.addKey(
                    AccessControl.GrantKey.CREATE,
                    AccessControl.TRUE_STRING);
        }
        return SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl(
                        "git-user",
                        List.of(grant.toAccessControl())));
    }
}
