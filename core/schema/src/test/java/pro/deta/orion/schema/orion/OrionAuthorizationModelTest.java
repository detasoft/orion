package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionAuthorizationModelTest {
    @Test
    void scopedDefinitionsDefensivelyCopyTheirOrderedReferencesAndExpressions() {
        AccessControl.GrantExpression readExpression = expression(AccessControl.GrantKey.READ, "true");
        List<AccessControl.GrantExpression> expressions = new ArrayList<>(List.of(readExpression));
        ScopedGrant grant = new ScopedGrant(new GrantId("read"), ScopedGrant.Effect.ALLOW, expressions);
        List<RoleAddress> roleReferences = new ArrayList<>(List.of(role("acme/base")));
        List<GrantAddress> grantReferences = new ArrayList<>(List.of(grant("acme/read")));
        ScopedRole scopedRole = new ScopedRole(new RoleId("developer"), roleReferences, grantReferences);

        expressions.clear();
        roleReferences.clear();
        grantReferences.clear();

        assertThat(grant.expressions()).containsExactly(readExpression);
        assertThat(scopedRole.roleReferences()).containsExactly(role("acme/base"));
        assertThat(scopedRole.grantReferences()).containsExactly(grant("acme/read"));
        assertThatThrownBy(() -> grant.expressions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scopedRole.roleReferences().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scopedDefinitionsRejectNullsAndDuplicateReferences() {
        RoleAddress base = role("acme/base");
        GrantAddress read = grant("acme/read");

        assertThatThrownBy(() -> new ScopedGrant(null, ScopedGrant.Effect.ALLOW, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedGrant(new GrantId("read"), null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedGrant(
                new GrantId("read"), ScopedGrant.Effect.ALLOW, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedGrant(
                new GrantId("read"),
                ScopedGrant.Effect.ALLOW,
                Collections.singletonList(null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedRole(null, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedRole(new RoleId("developer"), null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedRole(new RoleId("developer"), List.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedRole(
                new RoleId("developer"), Collections.singletonList(null), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedRole(
                new RoleId("developer"), List.of(), Collections.singletonList(null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedRole(
                new RoleId("developer"),
                List.of(base, base),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate role reference");
        assertThatThrownBy(() -> new ScopedRole(
                new RoleId("developer"),
                List.of(),
                List.of(read, read)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate grant reference");
    }

    @Test
    void ownersCopyDefinitionsAndRejectDuplicateLocalIdentifiers() {
        List<OrganizationUser> users = new ArrayList<>(List.of(user("alice", List.of(), List.of())));
        List<ScopedGrant> grants = new ArrayList<>(List.of(allow("read")));
        List<ScopedRole> roles = new ArrayList<>(List.of(roleDefinition("developer")));
        OrionDocument.Organization organization = organization("acme", users, grants, roles, List.of());

        users.clear();
        grants.clear();
        roles.clear();

        assertThat(organization.users()).hasSize(1);
        assertThat(organization.grants()).hasSize(1);
        assertThat(organization.roles()).hasSize(1);
        assertThatThrownBy(() -> organization(
                "acme",
                List.of(user("alice", List.of(), List.of()), user("alice", List.of(), List.of())),
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate user id: alice");
        assertThatThrownBy(() -> team(
                "platform",
                List.of(allow("read"), allow("read")),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate grant id: read");
        assertThatThrownBy(() -> repository(
                "api",
                List.of(),
                List.of(roleDefinition("developer"), roleDefinition("developer"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate role id: developer");
    }

    @Test
    void localIdentifiersCanRepeatInDifferentScopesAndOrganizationsOwnUserIdentity() {
        OrganizationUser alice = user("alice", List.of(), List.of());
        OrionDocument document = document(
                organization(
                        "acme",
                        List.of(alice),
                        List.of(allow("read")),
                        List.of(roleDefinition("developer")),
                        List.of(team(
                                "platform",
                                List.of(allow("read")),
                                List.of(roleDefinition("developer")),
                                List.of(repository(
                                        "api",
                                        List.of(allow("read")),
                                        List.of(roleDefinition("developer"))))))),
                organization(
                        "other",
                        List.of(alice),
                        List.of(allow("read")),
                        List.of(roleDefinition("developer")),
                        List.of()));

        assertThat(document.organizations()).hasSize(2);
        assertThat(document.organizations().get(0).users().getFirst())
                .isEqualTo(document.organizations().get(1).users().getFirst());
    }

    @Test
    void rejectsMissingTeamMembership() {
        OrionDocument.Organization organization = organization(
                "acme",
                List.of(user("alice", List.of(new TeamId("missing")), List.of())),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> document(organization))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing team membership: acme/missing");
    }

    @Test
    void rejectsUserRoleAssignmentsOutsideTheirOrganization() {
        OrionDocument.Organization acme = organization(
                "acme",
                List.of(user("alice", List.of(), List.of(role("other/developer")))),
                List.of(),
                List.of(),
                List.of());
        OrionDocument.Organization other = organization(
                "other", List.of(), List.of(), List.of(roleDefinition("developer")), List.of());

        assertThatThrownBy(() -> document(acme, other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role assignment outside organization: other/developer");
    }

    @Test
    void rejectsMissingAssignedRoles() {
        OrionDocument.Organization organization = organization(
                "acme",
                List.of(user("alice", List.of(), List.of(role("acme/missing")))),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> document(organization))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing assigned role: acme/missing");
    }

    @Test
    void rolesCanReferenceDefinitionsInTheirOwnScopeAndAncestors() {
        ScopedRole organizationRole = new ScopedRole(
                new RoleId("base"), List.of(), List.of(grant("acme/read")));
        ScopedRole teamRole = new ScopedRole(
                new RoleId("developer"),
                List.of(role("acme/base")),
                List.of(grant("acme/read"), grant("acme/platform/write")));
        ScopedRole repositoryRole = new ScopedRole(
                new RoleId("maintainer"),
                List.of(role("acme/base"), role("acme/platform/developer")),
                List.of(grant("acme/platform/api/admin")));

        OrionDocument document = document(organization(
                "acme",
                List.of(user(
                        "alice",
                        List.of(),
                        List.of(role("acme/platform/api/maintainer")))),
                List.of(allow("read")),
                List.of(organizationRole),
                List.of(team(
                        "platform",
                        List.of(allow("write")),
                        List.of(teamRole),
                        List.of(repository("api", List.of(allow("admin")), List.of(repositoryRole)))))));

        assertThat(document.organizations().getFirst().teams().getFirst().roles())
                .containsExactly(teamRole);
    }

    @Test
    void rejectsDescendantRoleAndGrantReferences() {
        ScopedRole descendantRole = new ScopedRole(
                new RoleId("base"),
                List.of(role("acme/platform/developer")),
                List.of());
        ScopedRole descendantGrant = new ScopedRole(
                new RoleId("reader"),
                List.of(),
                List.of(grant("acme/platform/write")));
        OrionDocument.Team platform = team(
                "platform",
                List.of(allow("write")),
                List.of(roleDefinition("developer")),
                List.of());

        assertThatThrownBy(() -> document(organization(
                "acme",
                List.of(),
                List.of(),
                List.of(descendantRole),
                List.of(platform))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role reference outside scope: acme/platform/developer");
        assertThatThrownBy(() -> document(organization(
                "acme",
                List.of(),
                List.of(),
                List.of(descendantGrant),
                List.of(platform))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grant reference outside scope: acme/platform/write");
    }

    @Test
    void rejectsCrossOrganizationRoleAndGrantReferences() {
        ScopedRole crossOrganizationRole = new ScopedRole(
                new RoleId("developer"),
                List.of(role("other/base")),
                List.of());
        ScopedRole crossOrganizationGrant = new ScopedRole(
                new RoleId("reader"),
                List.of(),
                List.of(grant("other/read")));
        OrionDocument.Organization other = organization(
                "other", List.of(), List.of(allow("read")), List.of(roleDefinition("base")), List.of());

        assertThatThrownBy(() -> document(
                organization("acme", List.of(), List.of(), List.of(crossOrganizationRole), List.of()),
                other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role reference outside scope: other/base");
        assertThatThrownBy(() -> document(
                organization("acme", List.of(), List.of(), List.of(crossOrganizationGrant), List.of()),
                other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grant reference outside scope: other/read");
    }

    @Test
    void rejectsMissingRoleAndGrantReferences() {
        ScopedRole missingRole = new ScopedRole(
                new RoleId("developer"), List.of(role("acme/missing")), List.of());
        ScopedRole missingGrant = new ScopedRole(
                new RoleId("reader"), List.of(), List.of(grant("acme/missing")));

        assertThatThrownBy(() -> document(organization(
                "acme", List.of(), List.of(), List.of(missingRole), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing role reference: acme/missing");
        assertThatThrownBy(() -> document(organization(
                "acme", List.of(), List.of(), List.of(missingGrant), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing grant reference: acme/missing");
    }

    @Test
    void rejectsDirectAndMultiRoleCycles() {
        ScopedRole direct = new ScopedRole(
                new RoleId("direct"), List.of(role("acme/direct")), List.of());
        ScopedRole first = new ScopedRole(
                new RoleId("first"), List.of(role("acme/second")), List.of());
        ScopedRole second = new ScopedRole(
                new RoleId("second"), List.of(role("acme/third")), List.of());
        ScopedRole third = new ScopedRole(
                new RoleId("third"), List.of(role("acme/first")), List.of());

        assertThatThrownBy(() -> document(organization(
                "acme", List.of(), List.of(), List.of(direct), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role cycle closes at: acme/direct");
        assertThatThrownBy(() -> document(organization(
                "acme", List.of(), List.of(), List.of(first, second, third), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role cycle closes at: acme/first");
    }

    @Test
    void validatesDeepAcyclicRoleChainsWithoutUsingTheCallStack() {
        int roleCount = 20_000;
        List<ScopedRole> roles = new ArrayList<>(roleCount);
        for (int index = 0; index < roleCount; index++) {
            List<RoleAddress> references = index == roleCount - 1
                    ? List.of()
                    : List.of(role("acme/role-" + (index + 1)));
            roles.add(new ScopedRole(new RoleId("role-" + index), references, List.of()));
        }

        OrionDocument document = document(organization(
                "acme", List.of(), List.of(), roles, List.of()));

        assertThat(document.organizations().getFirst().roles()).hasSize(roleCount);
    }

    @Test
    void membershipDoesNotAssignPermissionsAndAssignmentsDoNotRequireMembership() {
        OrionDocument.Team platform = team(
                "platform",
                List.of(),
                List.of(roleDefinition("developer")),
                List.of());
        OrganizationUser memberWithoutRole = user("member", List.of(new TeamId("platform")), List.of());
        OrganizationUser assignedWithoutMembership = user(
                "assigned", List.of(), List.of(role("acme/platform/developer")));

        OrionDocument document = document(organization(
                "acme",
                List.of(memberWithoutRole, assignedWithoutMembership),
                List.of(),
                List.of(),
                List.of(platform)));

        assertThat(document.organizations().getFirst().users())
                .containsExactly(memberWithoutRole, assignedWithoutMembership);
    }

    @Test
    void allowAndDenyGrantsCanCoexistWithoutBeingEvaluated() {
        ScopedGrant allow = allow("read");
        ScopedGrant deny = new ScopedGrant(
                new GrantId("deny-read"),
                ScopedGrant.Effect.DENY,
                List.of(expression(AccessControl.GrantKey.READ, "true")));

        OrionDocument document = document(organization(
                "acme", List.of(), List.of(allow, deny), List.of(), List.of()));

        assertThat(document.organizations().getFirst().grants())
                .extracting(ScopedGrant::effect)
                .containsExactly(ScopedGrant.Effect.ALLOW, ScopedGrant.Effect.DENY);
    }

    private static OrionDocument document(OrionDocument.Organization... organizations) {
        return new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl()),
                List.of(organizations));
    }

    private static OrionDocument.Organization organization(
            String id,
            List<OrganizationUser> users,
            List<ScopedGrant> grants,
            List<ScopedRole> roles,
            List<OrionDocument.Team> teams) {
        return new OrionDocument.Organization(new OrganizationId(id), null, users, grants, roles, teams);
    }

    private static OrionDocument.Team team(
            String id,
            List<ScopedGrant> grants,
            List<ScopedRole> roles,
            List<OrionDocument.Repository> repositories) {
        return new OrionDocument.Team(new TeamId(id), null, grants, roles, repositories);
    }

    private static OrionDocument.Repository repository(
            String id, List<ScopedGrant> grants, List<ScopedRole> roles) {
        return new OrionDocument.Repository(
                new RepositoryId(id),
                null,
                OrionDocument.Repository.DEFAULT_BRANCH,
                RepositoryPolicy.safeDefaults(),
                List.of(),
                grants,
                roles);
    }

    private static OrganizationUser user(
            String id, List<TeamId> memberships, List<RoleAddress> assignments) {
        return new OrganizationUser(
                new UserId(id), null, null, null, true, List.of(), memberships, assignments);
    }

    private static ScopedGrant allow(String id) {
        return new ScopedGrant(
                new GrantId(id),
                ScopedGrant.Effect.ALLOW,
                List.of(expression(AccessControl.GrantKey.READ, "true")));
    }

    private static ScopedRole roleDefinition(String id) {
        return new ScopedRole(new RoleId(id), List.of(), List.of());
    }

    private static RoleAddress role(String address) {
        return RoleAddress.parse(address);
    }

    private static GrantAddress grant(String address) {
        return GrantAddress.parse(address);
    }

    private static AccessControl.GrantExpression expression(AccessControl.GrantKey key, String value) {
        return new AccessControl.GrantExpression(key, value);
    }
}
