package pro.deta.orion.git.auth;

import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.NestedResource;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.RootResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

class GitFetchAccessRulesTest {
    private static final GitObjectId MASTER_COMMIT = GitObjectId.of("a971b22fe44d0a59636d70248c71872250e3687e");
    private static final GitObjectId FEATURE_COMMIT = GitObjectId.of("a9646354f2c01add76096d798125d21904f7e7d6");

    @Test
    void gitFetchResourceIsNestedUnderMatchingRepository() {
        GitFetchAccessRequest fetchRequest = fetchRequest(
                "project",
                Map.of(MASTER_COMMIT, "master"),
                MASTER_COMMIT);
        GitFetchResource fetchResource = GitFetchResource.of(fetchRequest);

        assertThat(fetchResource)
                .isInstanceOf(NestedResource.class)
                .isNotInstanceOf(RootResource.class);
        assertThat(fetchResource.parentResource()).isEqualTo(RepositoryResource.of("project"));
        assertThatThrownBy(() -> new GitFetchResource(RepositoryResource.of("other"), fetchRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void fetchAccessAllowsRepositoryGrantWithoutBranchRestriction() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project"))));

        assertThatCode(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(MASTER_COMMIT, "master"),
                MASTER_COMMIT)))
                .doesNotThrowAnyException();
    }

    @Test
    void fetchAccessRequiresParentRepositoryGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("other", "master"))));

        assertThatThrownBy(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(MASTER_COMMIT, "master"),
                MASTER_COMMIT)))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("parent repository read denied");
    }

    @Test
    void fetchAccessAllowsWildcardBranchGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", "*"))));

        assertThatCode(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(FEATURE_COMMIT, "feature"),
                FEATURE_COMMIT)))
                .doesNotThrowAnyException();
    }

    @Test
    void fetchAccessEvaluatesBranchRestrictionInsideWildcardRepositoryGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("*", "master"))));

        assertThatCode(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(MASTER_COMMIT, "master"),
                MASTER_COMMIT)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(FEATURE_COMMIT, "feature"),
                FEATURE_COMMIT)))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void fetchAccessDeniesBranchOutsideGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", "master"))));

        assertThatThrownBy(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(FEATURE_COMMIT, "feature"),
                FEATURE_COMMIT)))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void fetchAccessDeniesMissingWantedObject() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", "master"))));

        assertThatThrownBy(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(),
                MASTER_COMMIT)))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void fetchAccessDeniesMixedWantsWhenAnyWantedBranchIsOutsideGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", "master"))));

        assertThatThrownBy(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(
                        MASTER_COMMIT, "master",
                        FEATURE_COMMIT, "feature"),
                MASTER_COMMIT,
                FEATURE_COMMIT)))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void fetchAccessAllowsMixedWantsWhenEveryWantedBranchMatchesGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(
                repositoryGrant("project", "master"),
                repositoryGrant("project", "feature"))));

        assertThatCode(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(
                        MASTER_COMMIT, "master",
                        FEATURE_COMMIT, "feature"),
                MASTER_COMMIT,
                FEATURE_COMMIT)))
                .doesNotThrowAnyException();
    }

    private static void requireRepositoryFetch(SecurityContext securityContext, GitFetchAccessRequest request) throws OrionSecurityException {
        accessEnforcer().require(securityContext, GitFetchResource.of(request), GitFetchAccessRules.everyWantedObjectAllowed());
    }

    private static AccessControl.Grant repositoryGrant(String repositoryName) {
        return new AccessControl.Grant("repository", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName);
    }

    private static AccessControl.Grant repositoryGrant(String repositoryName, String branchName) {
        return repositoryGrant(repositoryName)
                .addKey(AccessControl.GrantKey.BRANCH, branchName);
    }

    private static GitFetchAccessRequest fetchRequest(String repositoryName, Map<GitObjectId, String> resolvedBranches, GitObjectId... wants) {
        return new GitFetchAccessRequest(repositoryName, List.of(wants), ignored -> resolvedBranches);
    }

    private static SecurityContext securityContext(InternalUserImpl userIdentity) {
        return SecurityContext.createContext().withUserIdentity(userIdentity);
    }
}
