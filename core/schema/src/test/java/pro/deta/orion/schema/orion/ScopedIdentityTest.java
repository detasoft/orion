package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedIdentityTest {
    private static final OrganizationId ACME = new OrganizationId("acme");
    private static final TeamId PLATFORM = new TeamId("platform");
    private static final RepositoryId API = new RepositoryId("api");

    @Test
    void acceptsCanonicalRoleAndGrantIdentifiers() {
        assertThat(new RoleId("release-manager").value()).isEqualTo("release-manager");
        assertThat(new GrantId("repository.write").value()).isEqualTo("repository.write");
    }

    @Test
    void rejectsNonCanonicalRoleAndGrantIdentifiers() {
        for (String value : List.of("", "ReleaseManager", "release/manager", "release--manager")) {
            assertThatThrownBy(() -> new RoleId(value))
                    .as("role id %s", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("role id");
            assertThatThrownBy(() -> new GrantId(value))
                    .as("grant id %s", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("grant id");
        }
    }

    @Test
    void representsOrganizationTeamAndRepositoryScopes() {
        ConfigurationScope organization = ConfigurationScope.organization(ACME);
        ConfigurationScope team = ConfigurationScope.team(ACME, PLATFORM);
        ConfigurationScope repository = ConfigurationScope.repository(
                new RepositoryAddress(ACME, PLATFORM, API));

        assertThat(organization.organizationId()).isEqualTo(ACME);
        assertThat(organization.teamId()).isEmpty();
        assertThat(organization.repositoryId()).isEmpty();
        assertThat(team.teamId()).contains(PLATFORM);
        assertThat(team.repositoryId()).isEmpty();
        assertThat(repository.teamId()).contains(PLATFORM);
        assertThat(repository.repositoryId()).contains(API);
        assertThat(organization.toString()).isEqualTo("acme");
        assertThat(team.toString()).isEqualTo("acme/platform");
        assertThat(repository.toString()).isEqualTo("acme/platform/api");
    }

    @Test
    void parsesEveryConfigurationScopeDepth() {
        assertThat(ConfigurationScope.parse("acme"))
                .isEqualTo(ConfigurationScope.organization(ACME));
        assertThat(ConfigurationScope.parse("acme/platform"))
                .isEqualTo(ConfigurationScope.team(ACME, PLATFORM));
        assertThat(ConfigurationScope.parse("acme/platform/api"))
                .isEqualTo(ConfigurationScope.repository(new RepositoryAddress(ACME, PLATFORM, API)));
    }

    @Test
    void rejectsRepositoryScopeWithoutTeam() {
        assertThatThrownBy(() -> new ConfigurationScope(ACME, Optional.empty(), Optional.of(API)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team");
    }

    @Test
    void identifiesSameAndAncestorScopes() {
        ConfigurationScope organization = ConfigurationScope.organization(ACME);
        ConfigurationScope team = ConfigurationScope.team(ACME, PLATFORM);
        ConfigurationScope repository = ConfigurationScope.repository(
                new RepositoryAddress(ACME, PLATFORM, API));

        assertThat(organization.isSameOrAncestorOf(organization)).isTrue();
        assertThat(organization.isSameOrAncestorOf(repository)).isTrue();
        assertThat(team.isSameOrAncestorOf(repository)).isTrue();
        assertThat(repository.isSameOrAncestorOf(team)).isFalse();
        ConfigurationScope other = ConfigurationScope.organization(new OrganizationId("other"));
        assertThat(organization.isSameOrAncestorOf(other))
                .isFalse();
    }

    @Test
    void parsesAndRendersRoleAndGrantAddressesAtEachScopeDepth() {
        ConfigurationScope organization = ConfigurationScope.organization(ACME);
        ConfigurationScope team = ConfigurationScope.team(ACME, PLATFORM);
        ConfigurationScope repository = ConfigurationScope.repository(
                new RepositoryAddress(ACME, PLATFORM, API));

        RoleAddress organizationRole = new RoleAddress(organization, new RoleId("developer"));
        RoleAddress teamRole = RoleAddress.parse("acme/platform/maintainer");
        GrantAddress repositoryGrant = GrantAddress.parse("acme/platform/api/write");

        assertThat(organizationRole.toString()).isEqualTo("acme/developer");
        assertThat(RoleAddress.parse(organizationRole.toString())).isEqualTo(organizationRole);
        assertThat(teamRole.scope()).isEqualTo(team);
        assertThat(teamRole.roleId()).isEqualTo(new RoleId("maintainer"));
        assertThat(teamRole.toString()).isEqualTo("acme/platform/maintainer");
        assertThat(repositoryGrant.scope()).isEqualTo(repository);
        assertThat(repositoryGrant.grantId()).isEqualTo(new GrantId("write"));
        assertThat(repositoryGrant.toString()).isEqualTo("acme/platform/api/write");
    }

    @Test
    void keepsRoleAndGrantAddressesDistinctForTheSameText() {
        RoleAddress role = RoleAddress.parse("acme/platform/maintainer");
        GrantAddress grant = GrantAddress.parse("acme/platform/maintainer");

        assertThat(role.toString()).isEqualTo(grant.toString());
        assertThat(role).isNotEqualTo(grant);
    }

    @Test
    void rejectsMalformedAddressSegmentCounts() {
        for (String value : List.of("developer", "acme/platform/api/security/maintainer")) {
            assertThatThrownBy(() -> RoleAddress.parse(value))
                    .as("role address %s", value)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> GrantAddress.parse(value))
                    .as("grant address %s", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
