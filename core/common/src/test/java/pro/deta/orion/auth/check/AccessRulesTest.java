package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.resource.ApplicationShutdownResource;
import pro.deta.orion.auth.check.resource.BranchResource;
import pro.deta.orion.auth.check.resource.ClientConnectionResource;
import pro.deta.orion.auth.check.resource.GitFetchResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.ConnectionAccessRules;
import pro.deta.orion.auth.check.rule.GitFetchAccessRules;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.acl.schema.AccessControl.TRUE_STRING;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;
import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;

public class AccessRulesTest {
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
        assertThatThrownBy(() -> requireRepositoryRead(anonymous, "project"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("repository read");

        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project"))));

        assertThatCode(() -> requireRepositoryRead(reader, "project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryRead(reader, "other"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("repository read");
    }

    @Test
    public void readAccessAllowsRepositoryPatternGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("team/*"))));

        assertThatCode(() -> requireRepositoryRead(reader, "team/project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryRead(reader, "other/project"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    public void createAccessRequiresRepositoryCreateGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project"))));
        assertThatThrownBy(() -> requireRepositoryCreate(reader, "project"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("repository create");

        AccessControl.Grant createGrant = repositoryGrant("project")
                .addKey(AccessControl.GrantKey.CREATE, TRUE_STRING);
        SecurityContext creator = securityContext(new InternalUserImpl("creator", List.of(createGrant)));

        assertThatCode(() -> requireRepositoryCreate(creator, "project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryCreate(creator, "other"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("repository create");
    }

    @Test
    public void createAccessAllowsRepositoryPatternGrant() {
        AccessControl.Grant createGrant = repositoryGrant("team/*")
                .addKey(AccessControl.GrantKey.CREATE, TRUE_STRING);
        SecurityContext creator = securityContext(new InternalUserImpl("creator", List.of(createGrant)));

        assertThatCode(() -> requireRepositoryCreate(creator, "team/project"))
                .doesNotThrowAnyException();
    }

    @Test
    public void writeAccessRequiresRepositoryWriteGrant() {
        AccessControl.Grant repositoryGrant = new AccessControl.Grant("repository-only", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, "project");
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant)));
        assertThatThrownBy(() -> requireRepositoryWrite(reader, "project"))
                .isInstanceOf(OrionSecurityException.class);

        AccessControl.Grant writeGrant = new AccessControl.Grant("write", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, "project")
                .addKey(AccessControl.GrantKey.WRITE, TRUE_STRING);
        SecurityContext writer = securityContext(new InternalUserImpl("writer", List.of(writeGrant)));

        assertThatCode(() -> requireRepositoryWrite(writer, "project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryWrite(writer, "other"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    public void shutdownRequiresShutdownGrant() {
        SecurityContext regular = securityContext(new InternalUserImpl("regular", List.of()));
        assertThatThrownBy(() -> requireApplicationShutdown(regular))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("application shutdown");

        AccessControl.Grant shutdownGrant = new AccessControl.Grant("shutdown", new ArrayList<>())
                .addKey(AccessControl.GrantKey.SHUTDOWN, TRUE_STRING);
        SecurityContext operator = securityContext(new InternalUserImpl("operator", List.of(shutdownGrant)));

        assertThatCode(() -> requireApplicationShutdown(operator))
                .doesNotThrowAnyException();
    }

    @Test
    void authenticatedUserCheckDeniesAnonymousContext() {
        assertThatThrownBy(() -> requireAuthenticatedUser(SecurityContext.createContext()))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("authenticated user");
    }

    @Test
    void authenticatedUserCheckDeniesAnonymousInternalUser() {
        SecurityContext context = securityContext(new InternalUserImpl(null, List.of()));

        assertThatThrownBy(() -> requireAuthenticatedUser(context))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("authenticated user");
    }

    @Test
    void authenticatedUserCheckAllowsNamedUserWithoutGrants() {
        SecurityContext context = securityContext(new InternalUserImpl("reader", List.of()));

        assertThatCode(() -> requireAuthenticatedUser(context))
                .doesNotThrowAnyException();
    }

    @Test
    void localConnectionCheckAllowsLoopbackAddresses() {
        SecurityContext context = SecurityContext.createContext();

        assertThatCode(() -> requireLocalConnection(context, new InetSocketAddress("127.0.0.1", 22)))
                .doesNotThrowAnyException();
        assertThatCode(() -> requireLocalConnection(context, new InetSocketAddress("::1", 22)))
                .doesNotThrowAnyException();
    }

    @Test
    void localConnectionCheckDeniesRemoteUnresolvedAndNonInetAddresses() {
        SecurityContext context = SecurityContext.createContext();

        assertThatThrownBy(() -> requireLocalConnection(context, new InetSocketAddress("8.8.8.8", 22)))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("local connection");
        assertThatThrownBy(() -> requireLocalConnection(context, InetSocketAddress.createUnresolved("localhost", 22)))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("local connection");
        assertThatThrownBy(() -> requireLocalConnection(context, new TestSocketAddress()))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("local connection");
        assertThatThrownBy(() -> requireLocalConnection(context, null))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("local connection");
    }

    @Test
    void resourceHierarchySeparatesRootResourcesFromNestedResources() {
        RepositoryResource repository = RepositoryResource.of("project");
        GitFetchAccessRequest fetchRequest = fetchRequest(
                "project",
                Map.of(MASTER_COMMIT, "master"),
                MASTER_COMMIT);
        GitFetchResource fetchResource = GitFetchResource.of(fetchRequest);
        BranchResource branchResource = BranchResource.of(repository, "master");

        assertThat(repository)
                .isInstanceOf(RootResource.class)
                .isNotInstanceOf(NestedResource.class);
        assertThat(ApplicationShutdownResource.applicationShutdown())
                .isInstanceOf(RootResource.class)
                .isNotInstanceOf(NestedResource.class);
        assertThat(ClientConnectionResource.of(new TestSocketAddress()))
                .isInstanceOf(RootResource.class)
                .isNotInstanceOf(NestedResource.class);
        assertThat(fetchResource)
                .isInstanceOf(NestedResource.class)
                .isNotInstanceOf(RootResource.class);
        assertThat(fetchResource.parentResource()).isEqualTo(repository);
        assertThat(branchResource)
                .isInstanceOf(NestedResource.class)
                .isNotInstanceOf(RootResource.class);
        assertThat(branchResource.parentResource()).isEqualTo(repository);
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
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("other", branchRestriction("master")))));

        assertThatThrownBy(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(MASTER_COMMIT, "master"),
                MASTER_COMMIT)))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("parent repository read denied");
    }

    @Test
    void fetchAccessAllowsWildcardBranchGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", branchRestriction("*")))));

        assertThatCode(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(FEATURE_COMMIT, "feature"),
                FEATURE_COMMIT)))
                .doesNotThrowAnyException();
    }

    @Test
    void fetchAccessEvaluatesBranchRestrictionInsideWildcardRepositoryGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("*", branchRestriction("master")))));

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
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", branchRestriction("master")))));

        assertThatThrownBy(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(FEATURE_COMMIT, "feature"),
                FEATURE_COMMIT)))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void fetchAccessDeniesMissingWantedObject() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", branchRestriction("master")))));

        assertThatThrownBy(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(),
                MASTER_COMMIT)))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void fetchAccessDeniesMixedWantsWhenAnyWantedBranchIsOutsideGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", branchRestriction("master")))));

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
                repositoryGrant("project", branchRestriction("master")),
                repositoryGrant("project", branchRestriction("feature")))));

        assertThatCode(() -> requireRepositoryFetch(reader, fetchRequest(
                "project",
                Map.of(
                        MASTER_COMMIT, "master",
                        FEATURE_COMMIT, "feature"),
                MASTER_COMMIT,
                FEATURE_COMMIT)))
                .doesNotThrowAnyException();
    }

    private static void requireRepositoryCreate(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        accessEnforcer().require(securityContext, RepositoryResource.of(repositoryName), RepositoryAccessRules.create());
    }

    private static void requireRepositoryRead(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        accessEnforcer().require(securityContext, RepositoryResource.of(repositoryName), RepositoryAccessRules.read());
    }

    private static void requireRepositoryWrite(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        accessEnforcer().require(securityContext, RepositoryResource.of(repositoryName), RepositoryAccessRules.write());
    }

    private static void requireRepositoryFetch(SecurityContext securityContext, GitFetchAccessRequest request) throws OrionSecurityException {
        accessEnforcer().require(securityContext, GitFetchResource.of(request), GitFetchAccessRules.everyWantedObjectAllowed());
    }

    private static void requireApplicationShutdown(SecurityContext securityContext) throws OrionSecurityException {
        accessEnforcer().require(securityContext, ApplicationShutdownResource.applicationShutdown(), ApplicationAccessRules.shutdown());
    }

    private static void requireAuthenticatedUser(SecurityContext securityContext) throws OrionSecurityException {
        accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());
    }

    private static void requireLocalConnection(SecurityContext securityContext, SocketAddress remoteAddress) throws OrionSecurityException {
        accessEnforcer().require(securityContext, ClientConnectionResource.of(remoteAddress), ConnectionAccessRules.localOnly());
    }

    private static AccessControl.Grant repositoryGrant(String repositoryName) {
        return new AccessControl.Grant("repository", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName);
    }

    private static AccessControl.Grant repositoryGrant(String repositoryName, BranchRestriction branchRestriction) {
        return repositoryGrant(repositoryName)
                .addKey(AccessControl.GrantKey.BRANCH, branchRestriction.branchName());
    }

    private static BranchRestriction branchRestriction(String branchName) {
        return new BranchRestriction(branchName);
    }

    private static GitFetchAccessRequest fetchRequest(String repositoryName, Map<GitObjectId, String> resolvedBranches, GitObjectId... wants) {
        return new GitFetchAccessRequest(repositoryName, List.of(wants), ignored -> resolvedBranches);
    }

    private static SecurityContext securityContext(InternalUserImpl userIdentity) {
        return SecurityContext.createContext().withUserIdentity(userIdentity);
    }

    private static final class TestSocketAddress extends SocketAddress {
    }

    private record BranchRestriction(String branchName) {
    }
}
