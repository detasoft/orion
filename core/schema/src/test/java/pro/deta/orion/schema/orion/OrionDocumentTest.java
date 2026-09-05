package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionDocumentTest {
    @Test
    void modelsRepositoriesInsideTheirTeamAndOrganization() {
        AccessControl accessControl = ACLUtil.generateDefaultAccessControl("root-password-hash");
        OrionDocument.Repository repository = repository("api", "API");
        OrionDocument.Team team = new OrionDocument.Team(
                new TeamId("platform"), "Platform", List.of(), List.of(), List.of(repository));
        OrionDocument.Organization organization = new OrionDocument.Organization(
                new OrganizationId("acme"), "Acme", List.of(), List.of(), List.of(), List.of(team));

        OrionDocument document = new OrionDocument(
                new OrionDocument.SystemConfiguration(accessControl),
                List.of(organization));

        OrionDocument.Repository nestedRepository = document.organizations().getFirst()
                .teams().getFirst()
                .repositories().getFirst();
        RepositoryAddress address = new RepositoryAddress(
                organization.id(), team.id(), nestedRepository.id());

        assertThat(document.system().accessControl()).isEqualTo(accessControl);
        assertThat(address.toString()).isEqualTo("acme/platform/api");
    }

    @Test
    void copiesHierarchyCollections() {
        List<OrionDocument.Repository> repositories = new ArrayList<>();
        repositories.add(repository("api", "API"));
        OrionDocument.Team team = new OrionDocument.Team(
                new TeamId("platform"), "Platform", List.of(), List.of(), repositories);
        List<OrionDocument.Team> teams = new ArrayList<>(List.of(team));
        OrionDocument.Organization organization = new OrionDocument.Organization(
                new OrganizationId("acme"), "Acme", List.of(), List.of(), List.of(), teams);
        List<OrionDocument.Organization> organizations = new ArrayList<>(List.of(organization));
        OrionDocument document = new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl()),
                organizations);

        repositories.clear();
        teams.clear();
        organizations.clear();

        assertThat(document.organizations()).hasSize(1);
        assertThat(document.organizations().getFirst().teams()).hasSize(1);
        assertThat(document.organizations().getFirst().teams().getFirst().repositories()).hasSize(1);
        assertThatThrownBy(() -> document.organizations().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsStableIdentitySeparateFromDisplayName() {
        OrionDocument.Repository first = repository("api", "Public API");
        OrionDocument.Repository renamed = repository("api", "Partner API");

        assertThat(first.id()).isEqualTo(renamed.id());
        assertThat(first.displayName()).isNotEqualTo(renamed.displayName());
    }

    @Test
    void supportsAnEmptyHierarchy() {
        OrionDocument document = OrionDocument.withAccessControl(new AccessControl());

        assertThat(document.system().accessControl()).isEqualTo(new AccessControl());
        assertThat(document.organizations()).isEmpty();
    }

    @Test
    void rejectsDuplicateOrganizationIds() {
        OrionDocument.Organization first = organization("acme", List.of());
        OrionDocument.Organization duplicate = organization("acme", List.of());

        assertThatThrownBy(() -> new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl()),
                List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate organization id: acme");
    }

    @Test
    void rejectsDuplicateTeamIdsWithinAnOrganization() {
        OrionDocument.Team first = team("platform", List.of());
        OrionDocument.Team duplicate = team("platform", List.of());

        assertThatThrownBy(() -> organization("acme", List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate team id: platform");
    }

    @Test
    void allowsTheSameTeamIdInDifferentOrganizations() {
        OrionDocument document = new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl()),
                List.of(
                        organization("acme", List.of(team("platform", List.of()))),
                        organization("other", List.of(team("platform", List.of())))));

        assertThat(document.organizations()).hasSize(2);
    }

    @Test
    void rejectsDuplicateRepositoryIdsWithinATeam() {
        OrionDocument.Repository first = repository("api");
        OrionDocument.Repository duplicate = repository("api");

        assertThatThrownBy(() -> team("platform", List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate repository id: api");
    }

    @Test
    void rejectsNullHierarchyNodes() {
        assertThatThrownBy(() -> new OrionDocument(null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrionDocument.Organization(
                new OrganizationId("acme"), null, List.of(), List.of(), List.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrionDocument.Team(
                new TeamId("platform"),
                null,
                List.of(),
                List.of(),
                Collections.singletonList(null)))
                .isInstanceOf(NullPointerException.class);
    }

    private static OrionDocument.Organization organization(String id, List<OrionDocument.Team> teams) {
        return new OrionDocument.Organization(
                new OrganizationId(id), null, List.of(), List.of(), List.of(), teams);
    }

    private static OrionDocument.Team team(String id, List<OrionDocument.Repository> repositories) {
        return new OrionDocument.Team(new TeamId(id), null, List.of(), List.of(), repositories);
    }

    private static OrionDocument.Repository repository(String id) {
        return repository(id, null);
    }

    private static OrionDocument.Repository repository(String id, String displayName) {
        return new OrionDocument.Repository(
                new RepositoryId(id),
                displayName,
                "refs/heads/main",
                RepositoryPolicy.safeDefaults(),
                List.of(),
                List.of(),
                List.of());
    }
}
