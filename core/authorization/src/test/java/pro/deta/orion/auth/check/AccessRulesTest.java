package pro.deta.orion.auth.check;

import java.util.Optional;
import pro.deta.orion.schema.orion.OrganizationId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.resource.ApplicationShutdownResource;
import pro.deta.orion.auth.check.resource.BranchResource;
import pro.deta.orion.auth.check.resource.ClientConnectionResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.BranchAccessRules;
import pro.deta.orion.auth.check.rule.ConnectionAccessRules;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.schema.acl.AccessControl.TRUE_STRING;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;
import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;

public class AccessRulesTest {
    @Test
    void organizationMembershipDoesNotReplaceRepositoryGrants() {
        SecurityContext scoped = securityContext(new InternalUserImpl("reader", List.of(),
                Optional.of(new OrganizationId("acme"))));
        assertThat(RepositoryAccessRules.read()
                .evaluate(scoped, RepositoryResource.of("acme/team/repo")).allowed()).isFalse();
        assertThat(RepositoryAccessRules.create()
                .evaluate(scoped, RepositoryResource.of("acme/team/repo")).allowed()).isFalse();
    }

    @Test
    void confinesOrganizationUsersEvenWithWildcardAndSystemGrants() {
        AccessControl acl = ACLUtil.generateDefaultAccessControl("unused");
        SecurityContext scoped = securityContext(new InternalUserImpl("root", acl.getGrants(),
                Optional.of(new OrganizationId("acme"))));
        for (AccessRule<RepositoryResource> rule : List.of(RepositoryAccessRules.read(),
                RepositoryAccessRules.write(), RepositoryAccessRules.create(), RepositoryAccessRules.force())) {
            assertThat(rule.evaluate(scoped, RepositoryResource.of("acme/team/repo")).allowed()).isTrue();
            for (String name : List.of("other/team/repo", "acme-other/team/repo", "acme/repo", "orion")) {
                assertThat(rule.evaluate(scoped, RepositoryResource.of(name)).allowed()).as(name).isFalse();
            }
        }
        assertThat(BranchAccessRules.fetch().evaluate(scoped,
                BranchResource.of(RepositoryResource.of("other/team/repo"), "main")).allowed()).isFalse();
        assertThat(ApplicationAccessRules.admin().evaluate(scoped,
                ApplicationAdminResource.applicationAdmin()).allowed()).isFalse();
        assertThat(ApplicationAccessRules.shutdown().evaluate(scoped,
                ApplicationShutdownResource.applicationShutdown()).allowed()).isFalse();
    }

    @Test
    public void matchInternalAsteriskSyntax() {
        // '*/orion', 'orion/*', '*/*', 'pre*/some'
        Assertions.assertTrue(matchExpressionValue("orion", "orion"));
        Assertions.assertTrue(matchExpressionValue("or*on", "orion"));
        Assertions.assertTrue(matchExpressionValue("*", "http-read-only-project"));
        Assertions.assertTrue(matchExpressionValue("team/*", "team/service-api"));
        Assertions.assertFalse(matchExpressionValue("team/*", "team/service/api"));
    }

    @Test
    void defaultAclStillAllowsNestedRepositoriesAndBranches() {
        AccessControl acl = ACLUtil.generateDefaultAccessControl("unused-test-hash");
        SecurityContext root = securityContext(new InternalUserImpl("root", acl.getGrants()));
        assertThatCode(() -> requireRepositoryRead(root, "team/sub/api")).doesNotThrowAnyException();
        assertThatCode(() -> requireRepositoryWrite(root, "team/sub/api")).doesNotThrowAnyException();
        assertThatCode(() -> requireBranchFetch(root, "team/sub/api", "feature/nested"))
                .doesNotThrowAnyException();
        assertThatCode(() -> requireBranchPush(root, "team/sub/api", "feature/nested"))
                .doesNotThrowAnyException();
    }

    @Test
    void repositoryStarDoesNotGrantAccessAcrossPathSegments() {
        SecurityContext prefix = securityContext(
                new InternalUserImpl("reader", List.of(repositoryGrant("team*"))));
        SecurityContext child = securityContext(
                new InternalUserImpl("reader", List.of(repositoryGrant("team/*"))));
        assertThatCode(() -> requireRepositoryRead(prefix, "teamone")).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryRead(prefix, "team/one"))
                .isInstanceOf(OrionSecurityException.class);
        assertThatCode(() -> requireRepositoryRead(child, "team/one")).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryRead(child, "team/one/api"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void repositoryGrantPreservesLiteralDots() {
        SecurityContext reader = securityContext(
                new InternalUserImpl("reader", List.of(repositoryGrant("team/api.v1"))));

        assertThatCode(() -> requireRepositoryRead(reader, "team/api.v1"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryRead(reader, "team/api_v1"))
                .isInstanceOf(OrionSecurityException.class);
        assertThatThrownBy(() -> requireRepositoryRead(reader, "team/apixv1"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void repositoryWildcardIncludesNestedPathsWithoutBypassingOtherGrants() {
        SecurityContext reader = securityContext(
                new InternalUserImpl("reader", List.of(repositoryGrant("team/**", "main"))));

        assertThatCode(() -> requireRepositoryRead(reader, "team/sub/api.v1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> requireBranchFetch(reader, "team/sub/api.v1", "main"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryRead(reader, "other/team/sub/api.v1"))
                .isInstanceOf(OrionSecurityException.class);
        assertThatThrownBy(() -> requireRepositoryWrite(reader, "team/sub/api.v1"))
                .isInstanceOf(OrionSecurityException.class);
        assertThatThrownBy(() -> requireBranchFetch(reader, "team/sub/api.v1", "private"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void wildcardPreservesLiteralPartsAndMatchesZeroOrMoreCharacters() {
        assertThat(matchExpressionValue("**", "team/sub/api.v1")).isTrue();
        assertThat(matchExpressionValue("team/**/api.*", "team/sub/nested/api.v1")).isTrue();
        assertThat(matchExpressionValue("team/api*", "team/api")).isTrue();
        assertThat(matchExpressionValue("team/*/api.v1", "team/sub/api_v1")).isFalse();
        assertThat(matchExpressionValue("team/api[1]", "team/api[1]")).isTrue();
        assertThat(matchExpressionValue("team/api[1]", "team/api1")).isFalse();
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

        AccessControl.Grant createGrant = repositoryGrantDraft("project")
                .addKey(AccessControl.GrantKey.CREATE, TRUE_STRING)
                .toAccessControl();
        SecurityContext creator = securityContext(new InternalUserImpl("creator", List.of(createGrant)));

        assertThatCode(() -> requireRepositoryCreate(creator, "project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryCreate(creator, "other"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("repository create");
    }

    @Test
    public void createAccessAllowsRepositoryPatternGrant() {
        AccessControl.Grant createGrant = repositoryGrantDraft("team/*")
                .addKey(AccessControl.GrantKey.CREATE, TRUE_STRING)
                .toAccessControl();
        SecurityContext creator = securityContext(new InternalUserImpl("creator", List.of(createGrant)));

        assertThatCode(() -> requireRepositoryCreate(creator, "team/project"))
                .doesNotThrowAnyException();
    }

    @Test
    public void writeAccessRequiresRepositoryWriteGrant() {
        AccessControl.Grant repositoryGrant = grantDraft("repository-only")
                .addKey(AccessControl.GrantKey.REPOSITORY, "project")
                .toAccessControl();
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant)));
        assertThatThrownBy(() -> requireRepositoryWrite(reader, "project"))
                .isInstanceOf(OrionSecurityException.class);

        AccessControl.Grant writeGrant = grantDraft("write")
                .addKey(AccessControl.GrantKey.REPOSITORY, "project")
                .addKey(AccessControl.GrantKey.READ_WRITE, TRUE_STRING)
                .toAccessControl();
        SecurityContext writer = securityContext(new InternalUserImpl("writer", List.of(writeGrant)));

        assertThatCode(() -> requireRepositoryWrite(writer, "project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryWrite(writer, "other"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    public void forceAccessRequiresRepositoryForceGrant() {
        AccessControl.Grant writeGrant = grantDraft("write")
                .addKey(AccessControl.GrantKey.REPOSITORY, "project")
                .addKey(AccessControl.GrantKey.READ_WRITE, TRUE_STRING)
                .toAccessControl();
        SecurityContext writer = securityContext(new InternalUserImpl("writer", List.of(writeGrant)));
        assertThatThrownBy(() -> requireRepositoryForce(writer, "project"))
                .isInstanceOf(OrionSecurityException.class);

        AccessControl.Grant forceGrant = grantDraft("force")
                .addKey(AccessControl.GrantKey.REPOSITORY, "project")
                .addKey(AccessControl.GrantKey.READ_WRITE, TRUE_STRING)
                .addKey(AccessControl.GrantKey.FORCE, TRUE_STRING)
                .toAccessControl();
        SecurityContext forceWriter = securityContext(new InternalUserImpl("force-writer", List.of(forceGrant)));

        assertThatCode(() -> requireRepositoryForce(forceWriter, "project"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireRepositoryForce(forceWriter, "other"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    public void shutdownRequiresShutdownGrant() {
        SecurityContext regular = securityContext(new InternalUserImpl("regular", List.of()));
        assertThatThrownBy(() -> requireApplicationShutdown(regular))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("application shutdown");

        AccessControl.Grant shutdownGrant = grantDraft("shutdown")
                .addKey(AccessControl.GrantKey.SHUTDOWN, TRUE_STRING)
                .toAccessControl();
        SecurityContext operator = securityContext(new InternalUserImpl("operator", List.of(shutdownGrant)));

        assertThatCode(() -> requireApplicationShutdown(operator))
                .doesNotThrowAnyException();
    }

    @Test
    public void adminRequiresAdminGrant() {
        SecurityContext regular = securityContext(new InternalUserImpl("regular", List.of()));
        assertThatThrownBy(() -> requireApplicationAdmin(regular))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("application admin");

        AccessControl.Grant adminGrant = grantDraft("admin")
                .addKey(AccessControl.GrantKey.ADMIN, TRUE_STRING)
                .toAccessControl();
        SecurityContext admin = securityContext(new InternalUserImpl("admin", List.of(adminGrant)));

        assertThatCode(() -> requireApplicationAdmin(admin))
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
        BranchResource branchResource = BranchResource.of(repository, "master");

        assertThat(repository)
                .isInstanceOf(RootResource.class)
                .isNotInstanceOf(NestedResource.class);
        assertThat(ApplicationShutdownResource.applicationShutdown())
                .isInstanceOf(RootResource.class)
                .isNotInstanceOf(NestedResource.class);
        assertThat(ApplicationAdminResource.applicationAdmin())
                .isInstanceOf(RootResource.class)
                .isNotInstanceOf(NestedResource.class);
        assertThat(ClientConnectionResource.of(new TestSocketAddress()))
                .isInstanceOf(RootResource.class)
                .isNotInstanceOf(NestedResource.class);
        assertThat(branchResource)
                .isInstanceOf(NestedResource.class)
                .isNotInstanceOf(RootResource.class);
        assertThat(branchResource.parentResource()).isEqualTo(repository);
    }

    @Test
    void branchAccessAllowsRepositoryGrantWithoutBranchRestriction() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project"))));

        assertThatCode(() -> requireBranchFetch(reader, "project", "master"))
                .doesNotThrowAnyException();
    }

    @Test
    void branchAccessRequiresParentRepositoryGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("other", "master"))));

        assertThatThrownBy(() -> requireBranchFetch(reader, "project", "master"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("parent repository read denied");
    }

    @Test
    void branchAccessAllowsWildcardBranchGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", "*"))));

        assertThatCode(() -> requireBranchFetch(reader, "project", "feature"))
                .doesNotThrowAnyException();
    }

    @Test
    void branchAccessEvaluatesBranchRestrictionInsideWildcardRepositoryGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("**", "master"))));

        assertThatCode(() -> requireBranchFetch(reader, "project", "master"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireBranchFetch(reader, "project", "feature"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void branchAccessDeniesBranchOutsideGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", "master"))));

        assertThatThrownBy(() -> requireBranchFetch(reader, "project", "feature"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void branchPushRequiresParentRepositoryWriteGrant() {
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("project", "master"))));

        assertThatThrownBy(() -> requireBranchPush(reader, "project", "master"))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("parent repository write denied");
    }

    @Test
    void branchPushAllowsGrantedBranchAndDeniesOtherBranches() {
        AccessControl.Grant grant = repositoryGrantDraft("project")
                .addKey(AccessControl.GrantKey.READ_WRITE, TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, "master")
                .toAccessControl();
        SecurityContext writer = securityContext(new InternalUserImpl("writer", List.of(grant)));

        assertThatCode(() -> requireBranchPush(writer, "project", "master"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requireBranchPush(writer, "project", "feature"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void readOnlyBranchGrantDoesNotExpandPushBranches() {
        AccessControl.Grant write = repositoryGrantDraft("project")
                .addKey(AccessControl.GrantKey.READ_WRITE, TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, "dev")
                .toAccessControl();
        SecurityContext user = securityContext(new InternalUserImpl(
                "developer", List.of(write, repositoryGrant("project", "main"))));

        assertThatCode(() -> requireBranchPush(user, "project", "dev")).doesNotThrowAnyException();
        assertThatCode(() -> requireBranchFetch(user, "project", "main")).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireBranchPush(user, "project", "main"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void readOnlyWildcardDoesNotExpandPushBranches() {
        AccessControl.Grant write = repositoryGrantDraft("team/**")
                .addKey(AccessControl.GrantKey.READ_WRITE, TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, "dev")
                .toAccessControl();
        SecurityContext user = securityContext(new InternalUserImpl(
                "developer", List.of(write, repositoryGrant("team/**", "*"))));

        assertThatCode(() -> requireBranchFetch(user, "team/sub/api", "main")).doesNotThrowAnyException();
        assertThatCode(() -> requireBranchPush(user, "team/sub/api", "dev")).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireBranchPush(user, "team/sub/api", "main"))
                .isInstanceOf(OrionSecurityException.class);
    }

    @Test
    void readOnlyBranchRestrictionDoesNotNarrowUnrestrictedWriteGrant() {
        AccessControl.Grant write = repositoryGrantDraft("project")
                .addKey(AccessControl.GrantKey.READ_WRITE, TRUE_STRING)
                .toAccessControl();
        SecurityContext user = securityContext(new InternalUserImpl(
                "developer", List.of(write, repositoryGrant("project", "main"))));

        assertThatCode(() -> requireBranchPush(user, "project", "dev")).doesNotThrowAnyException();
    }

    @Test
    void writeBranchRestrictionsRetainTheirExistingCombinationPolicy() {
        AccessControl.Grant unrestricted = repositoryGrantDraft("project")
                .addKey(AccessControl.GrantKey.READ_WRITE, TRUE_STRING)
                .toAccessControl();
        AccessControl.Grant restricted = repositoryGrantDraft("project")
                .addKey(AccessControl.GrantKey.READ_WRITE, TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, "dev")
                .toAccessControl();
        SecurityContext user = securityContext(new InternalUserImpl(
                "developer", List.of(unrestricted, restricted)));

        assertThatCode(() -> requireBranchPush(user, "project", "dev")).doesNotThrowAnyException();
        assertThatThrownBy(() -> requireBranchPush(user, "project", "main"))
                .isInstanceOf(OrionSecurityException.class);
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

    private static void requireRepositoryForce(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        accessEnforcer().require(securityContext, RepositoryResource.of(repositoryName), RepositoryAccessRules.force());
    }

    private static void requireBranchFetch(SecurityContext securityContext, String repositoryName, String branchName) throws OrionSecurityException {
        RepositoryResource repository = RepositoryResource.of(repositoryName);
        accessEnforcer().require(securityContext, BranchResource.of(repository, branchName), BranchAccessRules.fetch());
    }

    private static void requireBranchPush(SecurityContext securityContext, String repositoryName, String branchName) throws OrionSecurityException {
        RepositoryResource repository = RepositoryResource.of(repositoryName);
        accessEnforcer().require(securityContext, BranchResource.of(repository, branchName), BranchAccessRules.push());
    }

    private static void requireApplicationShutdown(SecurityContext securityContext) throws OrionSecurityException {
        accessEnforcer().require(securityContext, ApplicationShutdownResource.applicationShutdown(), ApplicationAccessRules.shutdown());
    }

    private static void requireApplicationAdmin(SecurityContext securityContext) throws OrionSecurityException {
        accessEnforcer().require(securityContext, ApplicationAdminResource.applicationAdmin(), ApplicationAccessRules.admin());
    }

    private static void requireAuthenticatedUser(SecurityContext securityContext) throws OrionSecurityException {
        accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());
    }

    private static void requireLocalConnection(SecurityContext securityContext, SocketAddress remoteAddress) throws OrionSecurityException {
        accessEnforcer().require(securityContext, ClientConnectionResource.of(remoteAddress), ConnectionAccessRules.localOnly());
    }

    private static AccessControl.Grant repositoryGrant(String repositoryName) {
        return repositoryGrantDraft(repositoryName).toAccessControl();
    }

    private static AccessControl.Grant repositoryGrant(String repositoryName, String branchName) {
        return repositoryGrantDraft(repositoryName)
                .addKey(AccessControl.GrantKey.BRANCH, branchName)
                .toAccessControl();
    }

    private static AccessControlDraft.Grant repositoryGrantDraft(String repositoryName) {
        return grantDraft("repository")
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName);
    }

    private static AccessControlDraft.Grant grantDraft(String id) {
        return new AccessControlDraft.Grant(id, new java.util.ArrayList<>());
    }

    private static SecurityContext securityContext(InternalUserImpl userIdentity) {
        return SecurityContext.createContext().withUserIdentity(userIdentity);
    }

    private static final class TestSocketAddress extends SocketAddress {
    }

}
