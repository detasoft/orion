package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.GrantAddress;
import pro.deta.orion.schema.orion.GrantId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.RoleAddress;
import pro.deta.orion.schema.orion.RoleId;
import pro.deta.orion.schema.orion.ScopedGrant;
import pro.deta.orion.schema.orion.ScopedRole;
import pro.deta.orion.schema.orion.TeamId;
import pro.deta.orion.schema.orion.UserId;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedAccessTest {
    private static final ConfigurationScope TEAM = ConfigurationScope.parse("acme/team");
    private static final UserId USER = new UserId("alice");

    @Test
    void inheritedDenyWinsOverLocalAllowRegardlessOfAssignmentOrder() {
        for (List<String> assignments : List.of(List.of("acme/deny", "acme/team/allow"),
                List.of("acme/team/allow", "acme/deny"))) {
            OrionDocument.Organization organization = organization(assignments, ScopedGrant.Effect.DENY);
            assertThat(allows(organization, TEAM)).isFalse();
        }
    }

    @Test
    void ancestorAllowIsInheritedAndUnknownOrForeignScopesAreDenied() {
        OrionDocument.Organization organization = organization(List.of("acme/allow"), ScopedGrant.Effect.ALLOW);
        assertThat(allows(organization, ConfigurationScope.parse("acme"))).isTrue();
        assertThat(allows(organization, TEAM)).isTrue();
        assertThat(allows(organization, ConfigurationScope.parse("other/team"))).isFalse();
        assertThat(allows(organization, ConfigurationScope.parse("acme/missing"))).isFalse();
        assertThat(ScopedAccess.allows(organization, new UserId("missing"), TEAM, expressions -> true)).isFalse();
    }

    @Test
    void localRoleReferencingAncestorDoesNotGrantAccessToParent() {
        OrionDocument.Organization organization = organization(List.of("acme/team/inherited"),
                ScopedGrant.Effect.ALLOW);
        assertThat(allows(organization, TEAM)).isTrue();
        assertThat(allows(organization, ConfigurationScope.parse("acme"))).isFalse();
    }

    @Test
    void unassignedDenyDoesNotAffectUserAndNoMatchingGrantDeniesAccess() {
        OrionDocument.Organization organization = organization(List.of("acme/team/allow"),
                ScopedGrant.Effect.DENY);
        assertThat(allows(organization, TEAM)).isTrue();
        assertThat(ScopedAccess.allows(organization, USER, TEAM, expressions -> false)).isFalse();
        assertThat(allows(organization(List.of(), ScopedGrant.Effect.ALLOW), TEAM)).isFalse();
    }

    @Test
    void repositoryRoleStaysLocalAndInheritsAncestorDeny() {
        ConfigurationScope target = ConfigurationScope.parse("acme/team/repo");
        for (ScopedGrant.Effect effect : ScopedGrant.Effect.values()) {
            OrionDocument.Organization base = organization(List.of(), effect);
            ScopedRole role = new ScopedRole(new RoleId("local"),
                    List.of(new RoleAddress(ConfigurationScope.parse("acme"), base.roles().getFirst().id())),
                    List.of(new GrantAddress(target, new GrantId("allow"))));
            ScopedGrant allow = new ScopedGrant(new GrantId("allow"), ScopedGrant.Effect.ALLOW,
                    base.grants().getFirst().expressions());
            OrionDocument.Repository repository = new OrionDocument.Repository(new RepositoryId("repo"), "",
                    OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(), List.of(),
                    List.of(allow), List.of(role), List.of());
            AccessControl.User user = new AccessControl.User("alice", null, null, null, List.of(),
                    List.of("acme/team/repo/local"), List.of());
            OrionDocument.Organization organization = new OrionDocument.Organization(base.id(), "",
                    List.of(user), base.grants(), base.roles(),
                    List.of(new OrionDocument.Team(new TeamId("team"), "", List.of(), List.of(),
                            List.of(repository))), List.of(), List.of(), List.of());
            new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()), List.of(organization));
            assertThat(allows(organization, target)).isEqualTo(effect == ScopedGrant.Effect.ALLOW);
            assertThat(allows(organization, TEAM)).isFalse();
            assertThat(allows(organization, ConfigurationScope.parse("acme/team/missing"))).isFalse();
        }
    }

    private static boolean allows(OrionDocument.Organization organization, ConfigurationScope scope) {
        return ScopedAccess.allows(organization, USER, scope,
                expressions -> GrantMatcher.of(AccessControl.GrantKey.ADMIN).matchesAny(expressions));
    }

    private static OrionDocument.Organization organization(List<String> assignments, ScopedGrant.Effect effect) {
        ScopedGrant grant = new ScopedGrant(new GrantId("admin"), effect,
                List.of(new AccessControl.GrantExpression(AccessControl.GrantKey.ADMIN, "true")));
        ConfigurationScope org = ConfigurationScope.parse("acme");
        ScopedRole ancestor = new ScopedRole(new RoleId(effect == ScopedGrant.Effect.ALLOW ? "allow" : "deny"),
                List.of(), List.of(new GrantAddress(org, grant.id())));
        ScopedRole local = new ScopedRole(new RoleId("allow"), List.of(),
                List.of(new GrantAddress(TEAM, grant.id())));
        ScopedRole inherited = new ScopedRole(new RoleId("inherited"),
                List.of(new RoleAddress(org, ancestor.id())), List.of());
        OrionDocument.Organization organization = new OrionDocument.Organization(new OrganizationId("acme"),
                "Acme", List.of(new AccessControl.User("alice", null, null, null, List.of(), assignments, List.of())),
                List.of(grant), List.of(ancestor),
                List.of(new OrionDocument.Team(new TeamId("team"), "Team",
                        List.of(new ScopedGrant(grant.id(), ScopedGrant.Effect.ALLOW, grant.expressions())),
                        List.of(local, inherited), List.of())), List.of(), List.of(), List.of());
        new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()), List.of(organization));
        return organization;
    }
}
