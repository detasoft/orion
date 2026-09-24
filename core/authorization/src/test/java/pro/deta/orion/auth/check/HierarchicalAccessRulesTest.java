package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.resource.BranchResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.BranchAccessRules;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.GrantAddress;
import pro.deta.orion.schema.orion.GrantId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.schema.orion.RoleId;
import pro.deta.orion.schema.orion.RoleAddress;
import pro.deta.orion.schema.orion.ScopedGrant;
import pro.deta.orion.schema.orion.ScopedRole;
import pro.deta.orion.schema.orion.TeamId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.schema.acl.AccessControl.GrantKey.*;

class HierarchicalAccessRulesTest {
    private static final RepositoryResource REPOSITORY = RepositoryResource.of("acme/team/repo");

    @Test
    void inheritedRoleAuthorizesRepositoryOperationsAndAbsentRepositoryCreation() {
        SecurityContext context = context(document(List.of("acme/developer"), List.of(),
                List.of(grant("write", ScopedGrant.Effect.ALLOW, READ_WRITE, CREATE, FORCE))));
        assertThat(RepositoryAccessRules.read().evaluate(context, REPOSITORY).allowed()).isTrue();
        assertThat(RepositoryAccessRules.write().evaluate(context, REPOSITORY).allowed()).isTrue();
        assertThat(RepositoryAccessRules.force().evaluate(context, REPOSITORY).allowed()).isTrue();
        assertThat(RepositoryAccessRules.create().evaluate(context,
                RepositoryResource.of("acme/team/new")).allowed()).isTrue();
        assertThat(RepositoryAccessRules.read().evaluate(context,
                RepositoryResource.of("acme/team/new")).allowed()).isFalse();
        assertThat(RepositoryAccessRules.create().evaluate(context,
                RepositoryResource.of("acme/missing/new")).allowed()).isFalse();
        assertThat(RepositoryAccessRules.read().evaluate(context,
                RepositoryResource.of("other/team/repo")).allowed()).isFalse();
    }

    @Test
    void branchDenyDoesNotBlockOtherBranchesAndReadGrantsCannotExpandPush() {
        ScopedGrant deny = new ScopedGrant(new GrantId("deny"), ScopedGrant.Effect.DENY,
                List.of(expression(READ_WRITE, "true"), expression(BRANCH, "blocked")));
        ScopedGrant write = new ScopedGrant(new GrantId("write"), ScopedGrant.Effect.ALLOW,
                List.of(expression(READ_WRITE, "true"), expression(BRANCH, "dev"),
                        expression(BRANCH, "blocked")));
        ScopedGrant read = new ScopedGrant(new GrantId("read"), ScopedGrant.Effect.ALLOW,
                List.of(expression(READ, "true"), expression(BRANCH, "main")));
        SecurityContext context = context(document(List.of("acme/developer"), List.of(),
                List.of(deny, write, read)));
        assertThat(RepositoryAccessRules.write().evaluate(context, REPOSITORY).allowed()).isTrue();
        assertThat(push(context, "dev")).isTrue();
        assertThat(push(context, "blocked")).isFalse();
        assertThat(push(context, "main")).isFalse();
        assertThat(fetch(context, "main")).isTrue();
        assertThat(fetch(context, "other")).isFalse();
    }

    @Test
    void directAndRoleBranchRestrictionsRetainTheirCombinationPolicy() {
        AccessControl.Grant direct = new AccessControl.Grant("direct", List.of(
                expression(AccessControl.GrantKey.REPOSITORY, "acme/**"), expression(READ_WRITE, "true")));
        ScopedGrant restricted = new ScopedGrant(new GrantId("write"), ScopedGrant.Effect.ALLOW,
                List.of(expression(READ_WRITE, "true"), expression(BRANCH, "dev")));
        SecurityContext context = context(document(List.of("acme/developer"), List.of(direct),
                List.of(restricted)));
        assertThat(push(context, "dev")).isTrue();
        assertThat(push(context, "main")).isFalse();
    }

    @Test
    void oneBranchSnapshotAndSubsequentChecksObserveRoleRevocationAndUserRemoval() {
        OrionDocument permitted = document(List.of("acme/developer"), List.of(),
                List.of(grant("write", ScopedGrant.Effect.ALLOW, READ_WRITE)));
        OrionDocument revoked = document(List.of(), List.of(),
                List.of(grant("write", ScopedGrant.Effect.ALLOW, READ_WRITE)));
        AtomicReference<OrionDocument> current = new AtomicReference<>(permitted);
        AtomicInteger reads = new AtomicInteger();
        SecurityContext context = SecurityContext.createContext().withUserIdentity(new InternalUserImpl(
                "alice", new OrganizationId("acme"), () -> {
                    reads.incrementAndGet();
                    return current.getAndSet(revoked);
                }));
        assertThat(push(context, "main")).isTrue();
        assertThat(reads).hasValue(1);
        assertThat(push(context, "main")).isFalse();
        assertThat(reads).hasValue(2);
        current.set(permitted);
        assertThat(RepositoryAccessRules.read().evaluate(context, REPOSITORY).allowed()).isTrue();
        OrionDocument.Organization organization = permitted.organizations().getFirst();
        current.set(new OrionDocument(permitted.system(), List.of(new OrionDocument.Organization(
                organization.id(), "", List.of(), organization.grants(), organization.roles(),
                organization.teams(), List.of(), List.of(), List.of()))));
        assertThat(RepositoryAccessRules.read().evaluate(context, REPOSITORY).allowed()).isFalse();
    }

    @Test
    void directRepositoryGrantStillAllowsReadButAdministrationAloneDoesNot() {
        AccessControl.Grant direct = new AccessControl.Grant("direct",
                List.of(expression(AccessControl.GrantKey.REPOSITORY, "acme/**")));
        SecurityContext reader = context(document(List.of(), List.of(direct), List.of()));
        assertThat(fetch(reader, "main")).isTrue();
        assertThat(push(reader, "main")).isFalse();
        SecurityContext admin = context(document(List.of("acme/developer"), List.of(),
                List.of(grant("admin", ScopedGrant.Effect.ALLOW, ADMIN))));
        assertThat(RepositoryAccessRules.read().evaluate(admin, REPOSITORY).allowed()).isFalse();
    }

    @Test
    void sameNameUsersAreResolvedOnlyInTheirOwnOrganization() {
        OrionDocument base = document(List.of(), List.of(), List.of());
        OrionDocument.Organization acme = base.organizations().getFirst();
        AccessControl.User otherUser = new AccessControl.User("alice", null, null, null, List.of(), List.of(),
                List.of(new AccessControl.Grant("read", List.of(expression(READ, "true")))));
        OrionDocument.Organization other = new OrionDocument.Organization(new OrganizationId("other"), "",
                List.of(otherUser), List.of(), List.of(), acme.teams(), List.of(), List.of(), List.of());
        OrionDocument document = new OrionDocument(base.system(), List.of(acme, other));
        SecurityContext acmeContext = context(document);
        SecurityContext otherContext = SecurityContext.createContext().withUserIdentity(
                new InternalUserImpl("alice", other.id(), () -> document));
        RepositoryResource otherRepository = RepositoryResource.of("other/team/repo");
        assertThat(RepositoryAccessRules.read().evaluate(acmeContext, REPOSITORY).allowed()).isFalse();
        assertThat(RepositoryAccessRules.read().evaluate(acmeContext, otherRepository).allowed()).isFalse();
        assertThat(RepositoryAccessRules.read().evaluate(otherContext, otherRepository).allowed()).isTrue();
        assertThat(RepositoryAccessRules.read().evaluate(otherContext, REPOSITORY).allowed()).isFalse();
    }

    @Test
    void repositoryLocalRoleCanImportAncestorWithoutGrantingSiblingAccess() {
        OrionDocument base = document(List.of(), List.of(),
                List.of(grant("write", ScopedGrant.Effect.ALLOW, READ_WRITE)));
        OrionDocument.Organization organization = base.organizations().getFirst();
        ScopedGrant force = grant("force", ScopedGrant.Effect.ALLOW, FORCE);
        ScopedRole local = new ScopedRole(new RoleId("local"), List.of(RoleAddress.parse("acme/developer")),
                List.of(GrantAddress.parse("acme/team/repo/force")));
        OrionDocument.Repository repository = new OrionDocument.Repository(new RepositoryId("repo"), "",
                OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(),
                List.of(), List.of(force), List.of(local), List.of());
        OrionDocument.Repository sibling = new OrionDocument.Repository(new RepositoryId("sibling"), "",
                OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(),
                List.of(), List.of(), List.of(), List.of());
        AccessControl.User user = new AccessControl.User("alice", null, null, null, List.of(),
                List.of("acme/team/repo/local"), List.of());
        OrionDocument.Team team = new OrionDocument.Team(new TeamId("team"), "", List.of(), List.of(),
                List.of(repository, sibling));
        SecurityContext context = context(new OrionDocument(base.system(), List.of(new OrionDocument.Organization(
                organization.id(), "", List.of(user), organization.grants(), organization.roles(),
                List.of(team), List.of(), List.of(), List.of()))));
        assertThat(RepositoryAccessRules.write().evaluate(context, REPOSITORY).allowed()).isTrue();
        assertThat(RepositoryAccessRules.force().evaluate(context, REPOSITORY).allowed()).isTrue();
        assertThat(push(context, "main")).isTrue();
        assertThat(RepositoryAccessRules.write().evaluate(context,
                RepositoryResource.of("acme/team/sibling")).allowed()).isFalse();
    }

    @Test
    void assignedDenyOverridesDirectAndRoleAllowsRegardlessOfGrantOrder() {
        ScopedGrant allow = grant("allow", ScopedGrant.Effect.ALLOW, READ_WRITE);
        ScopedGrant deny = grant("deny", ScopedGrant.Effect.DENY, READ_WRITE);
        AccessControl.Grant direct = new AccessControl.Grant("direct", List.of(expression(READ_WRITE, "true")));
        for (List<ScopedGrant> grants : List.of(List.of(allow, deny), List.of(deny, allow))) {
            SecurityContext context = context(document(List.of("acme/developer"), List.of(direct), grants));
            assertThat(RepositoryAccessRules.read().evaluate(context, REPOSITORY).allowed()).isFalse();
            assertThat(RepositoryAccessRules.write().evaluate(context, REPOSITORY).allowed()).isFalse();
            assertThat(push(context, "main")).isFalse();
        }
    }

    @Test
    void repositoryQualifierDoesNotTurnOtherActionsIntoReadPermission() {
        for (AccessControl.GrantKey action : List.of(CREATE, FORCE, ADMIN, SHUTDOWN)) {
            ScopedGrant otherAction = new ScopedGrant(new GrantId("other"), ScopedGrant.Effect.ALLOW,
                    List.of(expression(action, "true"), expression(AccessControl.GrantKey.REPOSITORY, "acme/**")));
            SecurityContext context = context(document(List.of("acme/developer"), List.of(), List.of(otherAction)));
            assertThat(RepositoryAccessRules.read().evaluate(context, REPOSITORY).allowed()).as(action.name())
                    .isFalse();
            assertThat(fetch(context, "main")).as(action.name()).isFalse();
        }
    }

    @Test
    void createDenyDoesNotOverrideAnIndependentReadGrant() {
        ScopedGrant deny = new ScopedGrant(new GrantId("deny"), ScopedGrant.Effect.DENY,
                List.of(expression(CREATE, "true"), expression(AccessControl.GrantKey.REPOSITORY, "acme/**")));
        ScopedGrant read = grant("read", ScopedGrant.Effect.ALLOW, READ);
        SecurityContext context = context(document(List.of("acme/developer"), List.of(), List.of(deny, read)));
        assertThat(RepositoryAccessRules.read().evaluate(context, REPOSITORY).allowed()).isTrue();
        assertThat(fetch(context, "main")).isTrue();
        assertThat(RepositoryAccessRules.create().evaluate(context, REPOSITORY).allowed()).isFalse();
    }

    private static boolean push(SecurityContext context, String branch) {
        return BranchAccessRules.push().evaluate(context, BranchResource.of(REPOSITORY, branch)).allowed();
    }

    private static boolean fetch(SecurityContext context, String branch) {
        return BranchAccessRules.fetch().evaluate(context, BranchResource.of(REPOSITORY, branch)).allowed();
    }

    private static SecurityContext context(OrionDocument document) {
        return SecurityContext.createContext().withUserIdentity(
                new InternalUserImpl("alice", new OrganizationId("acme"), () -> document));
    }

    private static ScopedGrant grant(String id, ScopedGrant.Effect effect, AccessControl.GrantKey... keys) {
        List<AccessControl.GrantExpression> expressions = new ArrayList<>();
        for (AccessControl.GrantKey key : keys) expressions.add(expression(key, "true"));
        return new ScopedGrant(new GrantId(id), effect, expressions);
    }

    private static AccessControl.GrantExpression expression(AccessControl.GrantKey key, String value) {
        return new AccessControl.GrantExpression(key, value);
    }

    private static OrionDocument document(List<String> assignments, List<AccessControl.Grant> direct,
            List<ScopedGrant> grants) {
        AccessControl.User user = new AccessControl.User("alice", null, null, null, List.of(), assignments, direct);
        List<GrantAddress> references = new ArrayList<>();
        for (ScopedGrant grant : grants) {
            references.add(new GrantAddress(ConfigurationScope.parse("acme"), grant.id()));
        }
        ScopedRole role = new ScopedRole(new RoleId("developer"), List.of(), references);
        OrionDocument.Repository repository = new OrionDocument.Repository(new RepositoryId("repo"), "",
                OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(),
                List.of(), List.of(), List.of(), List.of());
        OrionDocument.Team team = new OrionDocument.Team(new TeamId("team"), "", List.of(), List.of(),
                List.of(repository));
        OrionDocument.Organization organization = new OrionDocument.Organization(new OrganizationId("acme"),
                "", List.of(user), grants, List.of(role), List.of(team), List.of(), List.of(), List.of());
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()), List.of(organization));
    }
}
