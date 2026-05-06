package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContextHolder;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.acl.schema.AccessControl.TRUE_STRING;
import static pro.deta.orion.auth.SecurityContextHolder.getSc;
import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;
import static pro.deta.orion.auth.check.PermissionChecks.permissionChecker;

public class PermissionChecksTest {
    private static final GitObjectId MASTER_COMMIT = GitObjectId.of("a971b22fe44d0a59636d70248c71872250e3687e");
    private static final GitObjectId FEATURE_COMMIT = GitObjectId.of("a9646354f2c01add76096d798125d21904f7e7d6");

    @Test
    public void matchInternalAsteriskSyntax() {
        // '*/orion', 'orion/*', '*/*', 'pre*/some'
        Assertions.assertTrue(matchExpressionValue("orion", "orion"));
        Assertions.assertTrue(matchExpressionValue("or*on", "orion"));
    }

    @Test
    public void writeAccessRequiresRepositoryWriteGrant() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            AccessControl.Grant repositoryGrant = new AccessControl.Grant("repository-only", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.REPOSITORY, "project");
            getSc().setUserIdentity(new InternalUserImpl("reader", List.of(repositoryGrant)));
            assertThatThrownBy(() -> permissionChecker().ALLOW_WRITE_ACCESS.assertThat("project"))
                    .isInstanceOf(OrionSecurityException.class);

            AccessControl.Grant writeGrant = new AccessControl.Grant("write", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.REPOSITORY, "project")
                    .addKey(AccessControl.GrantKey.WRITE, TRUE_STRING);
            getSc().setUserIdentity(new InternalUserImpl("writer", List.of(writeGrant)));

            assertThatCode(() -> permissionChecker().ALLOW_WRITE_ACCESS.assertThat("project"))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> permissionChecker().ALLOW_WRITE_ACCESS.assertThat("other"))
                    .isInstanceOf(OrionSecurityException.class);
        }
    }

    @Test
    public void shutdownRequiresShutdownGrant() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            getSc().setUserIdentity(new InternalUserImpl("regular", List.of()));
            assertThatThrownBy(() -> permissionChecker().ALLOW_TO_SHUTDOWN.assertThat("shutdown"))
                    .isInstanceOf(OrionSecurityException.class);

            AccessControl.Grant shutdownGrant = new AccessControl.Grant("shutdown", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.SHUTDOWN, TRUE_STRING);
            getSc().setUserIdentity(new InternalUserImpl("operator", List.of(shutdownGrant)));

            assertThatCode(() -> permissionChecker().ALLOW_TO_SHUTDOWN.assertThat("shutdown"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void fetchAccessAllowsRepositoryGrantWithoutBranchRestriction() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            getSc().setUserIdentity(new InternalUserImpl("reader", List.of(repositoryGrant("project"))));

            assertThatCode(() -> permissionChecker().ALLOW_TO_FETCH_REPO.assertThat(fetchRequest(
                    "project",
                    Map.of(MASTER_COMMIT, "master"),
                    MASTER_COMMIT)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void fetchAccessAllowsWildcardBranchGrant() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            getSc().setUserIdentity(new InternalUserImpl("reader", List.of(branchGrant("project", "*"))));

            assertThatCode(() -> permissionChecker().ALLOW_TO_FETCH_REPO.assertThat(fetchRequest(
                    "project",
                    Map.of(FEATURE_COMMIT, "feature"),
                    FEATURE_COMMIT)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void fetchAccessDeniesBranchOutsideGrant() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            getSc().setUserIdentity(new InternalUserImpl("reader", List.of(branchGrant("project", "master"))));

            assertThatThrownBy(() -> permissionChecker().ALLOW_TO_FETCH_REPO.assertThat(fetchRequest(
                    "project",
                    Map.of(FEATURE_COMMIT, "feature"),
                    FEATURE_COMMIT)))
                    .isInstanceOf(OrionSecurityException.class);
        }
    }

    @Test
    void fetchAccessDeniesMissingWantedObject() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            getSc().setUserIdentity(new InternalUserImpl("reader", List.of(branchGrant("project", "master"))));

            assertThatThrownBy(() -> permissionChecker().ALLOW_TO_FETCH_REPO.assertThat(fetchRequest(
                    "project",
                    Map.of(),
                    MASTER_COMMIT)))
                    .isInstanceOf(OrionSecurityException.class);
        }
    }

    @Test
    void fetchAccessDeniesMixedWantsWhenAnyWantedBranchIsOutsideGrant() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            getSc().setUserIdentity(new InternalUserImpl("reader", List.of(branchGrant("project", "master"))));

            assertThatThrownBy(() -> permissionChecker().ALLOW_TO_FETCH_REPO.assertThat(fetchRequest(
                    "project",
                    Map.of(
                            MASTER_COMMIT, "master",
                            FEATURE_COMMIT, "feature"),
                    MASTER_COMMIT,
                    FEATURE_COMMIT)))
                    .isInstanceOf(OrionSecurityException.class);
        }
    }

    @Test
    void fetchAccessAllowsMixedWantsWhenEveryWantedBranchMatchesGrant() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            getSc().setUserIdentity(new InternalUserImpl("reader", List.of(
                    branchGrant("project", "master"),
                    branchGrant("project", "feature"))));

            assertThatCode(() -> permissionChecker().ALLOW_TO_FETCH_REPO.assertThat(fetchRequest(
                    "project",
                    Map.of(
                            MASTER_COMMIT, "master",
                            FEATURE_COMMIT, "feature"),
                    MASTER_COMMIT,
                    FEATURE_COMMIT)))
                    .doesNotThrowAnyException();
        }
    }

    private static AccessControl.Grant repositoryGrant(String repositoryName) {
        return new AccessControl.Grant("repository", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName);
    }

    private static AccessControl.Grant branchGrant(String repositoryName, String branchName) {
        return repositoryGrant(repositoryName)
                .addKey(AccessControl.GrantKey.BRANCH, branchName);
    }

    private static GitFetchAccessRequest fetchRequest(String repositoryName, Map<GitObjectId, String> resolvedBranches, GitObjectId... wants) {
        return new GitFetchAccessRequest(repositoryName, List.of(wants), ignored -> resolvedBranches);
    }
}
