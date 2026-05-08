package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.schema.AccessControl;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.acl.schema.AccessControl.TRUE_STRING;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;
import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;

public class AccessRulesTest {
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
    public void adminRequiresAdminGrant() {
        SecurityContext regular = securityContext(new InternalUserImpl("regular", List.of()));
        assertThatThrownBy(() -> requireApplicationAdmin(regular))
                .isInstanceOf(OrionSecurityException.class)
                .hasMessageContaining("application admin");

        AccessControl.Grant adminGrant = new AccessControl.Grant("admin", new ArrayList<>())
                .addKey(AccessControl.GrantKey.ADMIN, TRUE_STRING);
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
        SecurityContext reader = securityContext(new InternalUserImpl("reader", List.of(repositoryGrant("*", "master"))));

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

    private static void requireRepositoryCreate(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        accessEnforcer().require(securityContext, RepositoryResource.of(repositoryName), RepositoryAccessRules.create());
    }

    private static void requireRepositoryRead(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        accessEnforcer().require(securityContext, RepositoryResource.of(repositoryName), RepositoryAccessRules.read());
    }

    private static void requireRepositoryWrite(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        accessEnforcer().require(securityContext, RepositoryResource.of(repositoryName), RepositoryAccessRules.write());
    }

    private static void requireBranchFetch(SecurityContext securityContext, String repositoryName, String branchName) throws OrionSecurityException {
        RepositoryResource repository = RepositoryResource.of(repositoryName);
        accessEnforcer().require(securityContext, BranchResource.of(repository, branchName), BranchAccessRules.fetch());
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
        return new AccessControl.Grant("repository", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName);
    }

    private static AccessControl.Grant repositoryGrant(String repositoryName, String branchName) {
        return repositoryGrant(repositoryName)
                .addKey(AccessControl.GrantKey.BRANCH, branchName);
    }

    private static SecurityContext securityContext(InternalUserImpl userIdentity) {
        return SecurityContext.createContext().withUserIdentity(userIdentity);
    }

    private static final class TestSocketAddress extends SocketAddress {
    }

}
