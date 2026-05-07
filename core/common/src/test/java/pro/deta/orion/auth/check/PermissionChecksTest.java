package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.acl.schema.AccessControl.TRUE_STRING;
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
    public void readAccessRequiresRepositoryGrant() {
        SecurityContext anonymous = SecurityContext.createContext();
        assertThatThrownBy(() -> permissionChecker().requireRepositoryRead(anonymous, "project"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("repository read");

        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project"))));

        assertThatCode(() -> permissionChecker().requireRepositoryRead(reader, "project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> permissionChecker().requireRepositoryRead(reader, "other"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("repository read");
    }

    @Test
    public void readAccessAllowsRepositoryPatternGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("team/*"))));

        assertThatCode(() -> permissionChecker().requireRepositoryRead(reader, "team/project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> permissionChecker().requireRepositoryRead(reader, "other/project"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    public void createAccessRequiresRepositoryCreateGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project"))));
        assertThatThrownBy(() -> permissionChecker().requireRepositoryCreate(reader, "project"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("repository create");

        AccessControl.Grant createGrant = repositoryGrant("project")
                .addKey(AccessControl.GrantKey.CREATE, TRUE_STRING);
        SecurityContext creator = securityContext(new InternalUserImpl("creator", List.of(createGrant)));

        assertThatCode(() -> permissionChecker().requireRepositoryCreate(creator, "project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> permissionChecker().requireRepositoryCreate(creator, "other"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("repository create");
    }

    @Test
    public void createAccessAllowsRepositoryPatternGrant() {
        AccessControl.Grant createGrant = repositoryGrant("team/*")
                .addKey(AccessControl.GrantKey.CREATE, TRUE_STRING);
        SecurityContext creator = securityContext(new InternalUserImpl("creator", List.of(createGrant)));

        assertThatCode(() -> permissionChecker().requireRepositoryCreate(creator, "team/project"))
                .doesNotThrowAnyException();
    }

    @Test
    public void writeAccessRequiresRepositoryWriteGrant() {
        AccessControl.Grant repositoryGrant = new AccessControl.Grant("repository-only", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, "project");
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant)));
        assertThatThrownBy(() -> permissionChecker().requireRepositoryWrite(reader, "project"))
                .isInstanceOf(OrionSecurityException.class);

        AccessControl.Grant writeGrant = new AccessControl.Grant("write", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, "project")
                .addKey(AccessControl.GrantKey.WRITE, TRUE_STRING);
        SecurityContext writer = securityContext(new InternalUserImpl("writer", List.of(writeGrant)));

        assertThatCode(() -> permissionChecker().requireRepositoryWrite(writer, "project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> permissionChecker().requireRepositoryWrite(writer, "other"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    public void shutdownRequiresShutdownGrant() {
        SecurityContext regular = securityContext(new InternalUserImpl("regular", List.of()));
        assertThatThrownBy(() -> permissionChecker().requireApplicationShutdown(regular))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("application shutdown");

        AccessControl.Grant shutdownGrant = new AccessControl.Grant("shutdown", new ArrayList<>())
                .addKey(AccessControl.GrantKey.SHUTDOWN, TRUE_STRING);
        SecurityContext operator = securityContext(new InternalUserImpl("operator", List.of(shutdownGrant)));

        assertThatCode(() -> permissionChecker().requireApplicationShutdown(operator))
                .doesNotThrowAnyException();
    }

    @Test
    void authenticatedUserCheckDeniesAnonymousContext() {
        assertThatThrownBy(() -> permissionChecker().requireAuthenticatedUser(SecurityContext.createContext()))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("authenticated user");
    }

    @Test
    void authenticatedUserCheckDeniesAnonymousInternalUser() {
        SecurityContext context = securityContext(new InternalUserImpl(null, List.of()));

        assertThatThrownBy(() -> permissionChecker().requireAuthenticatedUser(context))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("authenticated user");
    }

    @Test
    void authenticatedUserCheckAllowsNamedUserWithoutGrants() {
        SecurityContext context = securityContext(new InternalUserImpl("reader", List.of()));

        assertThatCode(() -> permissionChecker().requireAuthenticatedUser(context))
                .doesNotThrowAnyException();
    }

    @Test
    void localConnectionCheckAllowsLoopbackAddresses() {
        SecurityContext context = SecurityContext.createContext();

        assertThatCode(() -> permissionChecker().requireLocalConnection(context, new InetSocketAddress("127.0.0.1", 22)))
                .doesNotThrowAnyException();
        assertThatCode(() -> permissionChecker().requireLocalConnection(context, new InetSocketAddress("::1", 22)))
                .doesNotThrowAnyException();
    }

    @Test
    void localConnectionCheckDeniesRemoteUnresolvedAndNonInetAddresses() {
        SecurityContext context = SecurityContext.createContext();

        assertThatThrownBy(() -> permissionChecker().requireLocalConnection(context, new InetSocketAddress("8.8.8.8", 22)))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("local connection");
        assertThatThrownBy(() -> permissionChecker().requireLocalConnection(context, InetSocketAddress.createUnresolved("localhost", 22)))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("local connection");
        assertThatThrownBy(() -> permissionChecker().requireLocalConnection(context, new TestSocketAddress()))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("local connection");
    }

    @Test
    void fetchAccessAllowsRepositoryGrantWithoutBranchRestriction() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project"))));

        assertThatCode(() -> permissionChecker().requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(MASTER_COMMIT, "master"),
                MASTER_COMMIT)))
                .doesNotThrowAnyException();
    }

    @Test
    void fetchAccessAllowsWildcardBranchGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(branchGrant("project", "*"))));

        assertThatCode(() -> permissionChecker().requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(FEATURE_COMMIT, "feature"),
                FEATURE_COMMIT)))
                .doesNotThrowAnyException();
    }

    @Test
    void fetchAccessDeniesBranchOutsideGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(branchGrant("project", "master"))));

        assertThatThrownBy(() -> permissionChecker().requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(FEATURE_COMMIT, "feature"),
                FEATURE_COMMIT)))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void fetchAccessDeniesMissingWantedObject() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(branchGrant("project", "master"))));

        assertThatThrownBy(() -> permissionChecker().requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(),
                MASTER_COMMIT)))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void fetchAccessDeniesMixedWantsWhenAnyWantedBranchIsOutsideGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(branchGrant("project", "master"))));

        assertThatThrownBy(() -> permissionChecker().requireRepositoryFetch(reader, fetchRequest(
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
                branchGrant("project", "master"),
                branchGrant("project", "feature"))));

        assertThatCode(() -> permissionChecker().requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(
                        MASTER_COMMIT, "master",
                        FEATURE_COMMIT, "feature"),
                MASTER_COMMIT,
                FEATURE_COMMIT)))
                .doesNotThrowAnyException();
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

    private static SecurityContext securityContext(InternalUserImpl userIdentity) {
        return SecurityContext.createContext().withUserIdentity(userIdentity);
    }

    private static final class TestSocketAddress extends SocketAddress {
    }
}
