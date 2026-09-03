package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionHierarchyIdentityTest {
    @Test
    void acceptsCanonicalHierarchyIdentifiers() {
        assertThat(new OrganizationId("acme").value()).isEqualTo("acme");
        assertThat(new TeamId("platform-tools").value()).isEqualTo("platform-tools");
        assertThat(new RepositoryId("api_v2.1").value()).isEqualTo("api_v2.1");
        assertThat(new UserId("alice.smith").value()).isEqualTo("alice.smith");
    }

    @Test
    void parsesCanonicalRepositoryAddress() {
        RepositoryAddress address = RepositoryAddress.parse("acme/platform/api");

        assertThat(address.organizationId()).isEqualTo(new OrganizationId("acme"));
        assertThat(address.teamId()).isEqualTo(new TeamId("platform"));
        assertThat(address.repositoryId()).isEqualTo(new RepositoryId("api"));
        assertThat(address.toString()).isEqualTo("acme/platform/api");
    }

    @Test
    void parsesSystemAndOrganizationPrincipals() {
        PrincipalAddress root = PrincipalAddress.parse("system/root");
        PrincipalAddress alice = PrincipalAddress.parse("acme/alice");

        assertThat(root.isSystem()).isTrue();
        assertThat(root.userId()).isEqualTo(new UserId("root"));
        assertThat(alice.isSystem()).isFalse();
        assertThat(alice.userId()).isEqualTo(new UserId("alice"));
        assertThat(alice.requireOrganization(new OrganizationId("acme"))).isSameAs(alice);
        assertThat(root.toString()).isEqualTo("system/root");
        assertThat(alice.toString()).isEqualTo("acme/alice");
    }

    @Test
    void rejectsNonCanonicalIdentifiers() {
        List<String> invalid = List.of(
                "", " ", "Acme", " acme", "acme ", ".", "..", "-acme", "acme-", "acme--tools",
                "acme/tools", "acme\\tools", "acme\u0000tools");

        for (String value : invalid) {
            assertThatThrownBy(() -> new TeamId(value))
                    .as("identifier %s", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("team id");
        }
    }

    @Test
    void reservesSystemOrganizationForSystemPrincipals() {
        assertThatThrownBy(() -> new OrganizationId("system"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void rejectsAddressesWithWrongOrEscapingSegments() {
        List<String> invalidRepositories =
                List.of("acme/platform", "acme/platform/api/extra", "acme//api", "../platform/api");
        for (String value : invalidRepositories) {
            assertThatThrownBy(() -> RepositoryAddress.parse(value))
                    .as("repository address %s", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        for (String value : List.of("root", "system/root/extra", "acme//alice", "../alice")) {
            assertThatThrownBy(() -> PrincipalAddress.parse(value))
                    .as("principal address %s", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsCrossOrganizationPrincipalUse() {
        PrincipalAddress principal = PrincipalAddress.parse("acme/alice");

        assertThatThrownBy(() -> principal.requireOrganization(new OrganizationId("other")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to organization other");
    }
}
