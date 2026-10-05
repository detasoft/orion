package pro.deta.orion.transport.git.auth;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.parser.wire.GitWireBootstrap;

import java.util.ArrayList;
import java.util.List;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;

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
    void fetchAllowsAnyReachableGrantedBranch() {
        AuthenticatedRepositoryAccessHook hook =
                new AuthenticatedRepositoryAccessHook(
                        branchSecurityContext("project", "main", false));

        assertThatCode(() -> hook.beforeFetch(
                "project",
                List.of("feature", "main")))
                .doesNotThrowAnyException();
    }

    @Test
    void fetchRejectsDeniedAndUnresolvedWants() {
        AuthenticatedRepositoryAccessHook hook =
                new AuthenticatedRepositoryAccessHook(
                        branchSecurityContext("project", "main", false));

        assertThatThrownBy(() -> hook.beforeFetch(
                "project",
                List.of("feature")))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("branch fetch");
        assertThatThrownBy(() -> hook.beforeFetch("project", List.of()))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("reachable branch");
    }

    @Test
    void updateEnforcesBranchAndForceGrants() {
        AuthenticatedRepositoryAccessHook hook =
                new AuthenticatedRepositoryAccessHook(
                        branchSecurityContext("project", "main", false));

        assertThatCode(() -> hook.beforeUpdate(
                "project",
                "refs/heads/main",
                false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> hook.beforeUpdate(
                "project",
                "refs/heads/feature",
                false))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("branch push");
        assertThatThrownBy(() -> hook.beforeUpdate(
                "project",
                "refs/heads/main",
                true))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("repository force");
    }

    @Test
    void updateCannotBorrowBranchFromReadOnlyGrant() {
        Grant readWrite = new Grant("writer", List.of(
                new GrantExpression(GrantKey.REPOSITORY, "project"),
                new GrantExpression(GrantKey.READ_WRITE, "true"),
                new GrantExpression(GrantKey.BRANCH, "dev")));
        Grant read = new Grant("reader", List.of(
                new GrantExpression(GrantKey.REPOSITORY, "project"),
                new GrantExpression(GrantKey.BRANCH, "main")));
        AuthenticatedRepositoryAccessHook hook = new AuthenticatedRepositoryAccessHook(
                SecurityContext.createContext().withUserIdentity(
                        new InternalUserImpl("developer", List.of(readWrite, read))));

        assertThatCode(() -> hook.beforeUpdate("project", "refs/heads/dev", false))
                .doesNotThrowAnyException();
        assertThatCode(() -> hook.beforeFetch("project", List.of("main")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> hook.beforeUpdate("project", "refs/heads/main", false))
                .isInstanceOf(GitNativeRepositoryAccessHook.AccessDeniedException.class);
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
    void wireCanonicalNameUsesTheExactRepositoryGrant() {
        AuthenticatedRepositoryAccessHook hook =
                new AuthenticatedRepositoryAccessHook(
                        repositorySecurityContext(
                                "team/repo",
                                false,
                                true));
        String repositoryName = GitWireBootstrap.sshCommandData(
                        "git-upload-pack '/team%2Frepo.git'",
                        null)
                .repositoryPath();

        assertThatCode(() -> hook.beforeRead(repositoryName))
                .doesNotThrowAnyException();
        assertThatCode(() -> hook.beforeCreate(repositoryName))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidCanonicalRepositoryNames() {
        AuthenticatedRepositoryAccessHook hook =
                new AuthenticatedRepositoryAccessHook(
                        repositorySecurityContext(
                                "team/project",
                                false,
                                true));

        assertThatThrownBy(() -> hook.beforeRead("team/../project.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hook.beforeRead("team\\project.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hook.beforeRead("team/project.git\0"))
                .isInstanceOf(IllegalArgumentException.class);
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
        List<GrantExpression> expressions = new ArrayList<>();
        expressions.add(new GrantExpression(GrantKey.REPOSITORY, repositoryName));
        if (write) {
            expressions.add(new GrantExpression(
                    GrantKey.READ_WRITE, "true"));
        }
        if (create) {
            expressions.add(new GrantExpression(
                    GrantKey.CREATE, "true"));
        }
        return SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl(
                        "git-user",
                        List.of(new Grant("repository", expressions))));
    }

    private static SecurityContext branchSecurityContext(
            String repositoryName,
            String branchName,
            boolean force) {
        List<GrantExpression> expressions = new ArrayList<>();
        expressions.add(new GrantExpression(GrantKey.REPOSITORY, repositoryName));
        expressions.add(new GrantExpression(GrantKey.BRANCH, branchName));
        expressions.add(new GrantExpression(
                GrantKey.READ_WRITE, "true"));
        if (force) {
            expressions.add(new GrantExpression(
                    GrantKey.FORCE, "true"));
        }
        return SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl(
                        "git-user",
                        List.of(new Grant("repository", expressions))));
    }
}
