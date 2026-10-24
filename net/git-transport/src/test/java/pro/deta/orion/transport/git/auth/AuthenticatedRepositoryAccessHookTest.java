package pro.deta.orion.transport.git.auth;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.schema.orion.v2.GrantAddress;
import pro.deta.orion.schema.orion.v2.GrantId;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.RepositoryPolicy;
import pro.deta.orion.schema.orion.v2.RoleId;
import pro.deta.orion.schema.orion.v2.ScopedGrant;
import pro.deta.orion.schema.orion.v2.ScopedRole;
import pro.deta.orion.schema.orion.v2.TeamId;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.parser.wire.GitWireBootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedRepositoryAccessHookTest {
    @Test
    void forceUpdateCannotBorrowWritableBranchesFromAnotherGrant() {
        Grant write = new Grant("writer", List.of(
                new GrantExpression(GrantKey.REPOSITORY, "project"),
                new GrantExpression(GrantKey.READ_WRITE, "true"),
                new GrantExpression(GrantKey.BRANCH, "*")));
        Grant force = new Grant("force-dev", List.of(
                new GrantExpression(GrantKey.REPOSITORY, "project"),
                new GrantExpression(GrantKey.FORCE, "true"),
                new GrantExpression(GrantKey.BRANCH, "dev")));
        AuthenticatedRepositoryAccessHook hook = new AuthenticatedRepositoryAccessHook(
                SecurityContext.createContext().withUserIdentity(
                        new InternalUserImpl("writer", List.of(write, force))));

        assertThatCode(() -> hook.beforeUpdate("project", "refs/heads/dev", true)).doesNotThrowAnyException();
        assertThatCode(() -> hook.beforeUpdate("project", "refs/heads/main", false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> hook.beforeUpdate("project", "refs/heads/main", true))
                .isInstanceOf(GitNativeRepositoryAccessHook.AccessDeniedException.class);
    }

    @Test
    void branchForceDenyDoesNotPreventAnOrdinaryOrganizationPush() {
        ScopedGrant allow = new ScopedGrant(new GrantId("allow"), ScopedGrant.Effect.ALLOW,
                List.of(new GrantExpression(GrantKey.READ_WRITE, "true"),
                        new GrantExpression(GrantKey.FORCE, "true")));
        ScopedGrant deny = new ScopedGrant(new GrantId("deny"), ScopedGrant.Effect.DENY,
                List.of(new GrantExpression(GrantKey.FORCE, "true"),
                        new GrantExpression(GrantKey.BRANCH, "main")));
        AuthenticatedRepositoryAccessHook hook = new AuthenticatedRepositoryAccessHook(
                organizationContext(List.of(allow, deny)));

        assertThatCode(() -> hook.beforeUpdate("acme/team/project", "refs/heads/main", false))
                .doesNotThrowAnyException();
        assertThatCode(() -> hook.beforeUpdate("acme/team/project", "refs/heads/dev", true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> hook.beforeUpdate("acme/team/project", "refs/heads/main", true))
                .isInstanceOf(GitNativeRepositoryAccessHook.AccessDeniedException.class);
    }

    @Test
    void unrestrictedForceStillRequiresWriteAccessToTheTargetBranch() {
        Grant force = new Grant("force", List.of(
                new GrantExpression(GrantKey.REPOSITORY, "project"),
                new GrantExpression(GrantKey.FORCE, "true")));
        List<Grant> grants = new ArrayList<>(branchSecurityContext("project", "dev", false)
                .getUserIdentity().getGrants());
        grants.add(force);
        AuthenticatedRepositoryAccessHook hook = new AuthenticatedRepositoryAccessHook(
                SecurityContext.createContext().withUserIdentity(new InternalUserImpl("writer", grants)));

        assertThatCode(() -> hook.beforeUpdate("project", "refs/heads/dev", true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> hook.beforeUpdate("project", "refs/heads/main", true))
                .isInstanceOf(GitNativeRepositoryAccessHook.AccessDeniedException.class);
    }

    @Test
    void tagRewritesKeepTheirRepositoryForceCheck() {
        AuthenticatedRepositoryAccessHook allowed = new AuthenticatedRepositoryAccessHook(
                branchSecurityContext("project", "dev", true));
        AuthenticatedRepositoryAccessHook denied = new AuthenticatedRepositoryAccessHook(
                branchSecurityContext("project", "dev", false));

        assertThatCode(() -> allowed.beforeUpdate("project", "refs/tags/v1", true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> denied.beforeUpdate("project", "refs/tags/v1", true))
                .isInstanceOf(GitNativeRepositoryAccessHook.AccessDeniedException.class);
    }

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

    private static SecurityContext organizationContext(List<ScopedGrant> grants) {
        List<GrantAddress> references = new ArrayList<>();
        for (ScopedGrant grant : grants) references.add(GrantAddress.parse("acme/" + grant.id()));
        ScopedRole role = new ScopedRole(new RoleId("writer"), List.of(), references);
        User user = new User("writer", null, null, null, List.of(), List.of("acme/writer"), List.of());
        OrionDocument.Repository repository = new OrionDocument.Repository("project", "",
                OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(),
                List.of(), List.of(), List.of(), List.of(), Optional.empty());
        OrionDocument.Team team = new OrionDocument.Team(new TeamId("team"), "", List.of(), List.of(),
                List.of(repository));
        OrionDocument.Organization organization = new OrionDocument.Organization(new OrganizationId("acme"), "",
                List.of(user), grants, List.of(role), List.of(team),
                List.of(), List.of(), List.of(), List.of());
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()),
                List.of(organization));
        return SecurityContext.createContext().withUserIdentity(
                new InternalUserImpl("writer", organization.id(), () -> document));
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
