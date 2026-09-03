package pro.deta.orion.schema.orion.v2;

import jakarta.xml.bind.annotation.XmlRootElement;
import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.TeamId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionV2MapperTest {
    @Test
    void keepsJaxbAnnotationsOnTheVersionedDtoBoundary() {
        XmlRootElement root = OrionV2.class.getAnnotation(XmlRootElement.class);

        assertThat(root).isNotNull();
        assertThat(root.name()).isEqualTo("orion");
        assertThat(OrionDocument.class.getAnnotation(XmlRootElement.class)).isNull();
        assertThat(OrionV2.class.getPackageName()).endsWith(".schema.orion.v2");
    }

    @Test
    void roundTripsTheCurrentDocument() {
        AccessControlDraft acl = new AccessControlDraft();
        acl.getUsers().add(new AccessControlDraft.User(
                "root",
                "Root",
                "User",
                "root@example.test",
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()));
        OrionDocument document = document(acl.toAccessControl(), List.of(
                organization("acme", List.of(team("platform", List.of(repository("api")))))));

        OrionV2 dto = OrionV2Mapper.fromCurrent(document);
        OrionDocument mapped = OrionV2Mapper.toCurrent(dto);

        assertThat(dto.getSchemaVersion()).isEqualTo(OrionV2.SchemaVersion.V2);
        assertThat(mapped).isEqualTo(document);
    }

    @Test
    void sortsIdentifierAddressedCollectionsForStableOutput() {
        AccessControlDraft acl = new AccessControlDraft();
        acl.getUsers().add(user("z-user"));
        acl.getUsers().add(user("a-user"));
        acl.getRoles().add(role("z-role"));
        acl.getRoles().add(role("a-role"));
        acl.getGrants().add(grant("z-grant"));
        acl.getGrants().add(grant("a-grant"));
        OrionDocument document = document(acl.toAccessControl(), List.of(
                organization("z-org", List.of()),
                organization("a-org", List.of(
                        team("z-team", List.of()),
                        team("a-team", List.of(repository("z-repo"), repository("a-repo")))))));

        OrionV2 dto = OrionV2Mapper.fromCurrent(document);

        assertThat(dto.getOrganizations()).extracting(OrionV2.Organization::getId)
                .containsExactly("a-org", "z-org");
        assertThat(dto.getOrganizations().getFirst().getTeams()).extracting(OrionV2.Team::getId)
                .containsExactly("a-team", "z-team");
        assertThat(dto.getOrganizations().getFirst().getTeams().getFirst().getRepositories())
                .extracting(OrionV2.Repository::getId)
                .containsExactly("a-repo", "z-repo");
        assertThat(dto.getSystem().getAccessControl().getUsers()).extracting(OrionV2.User::getId)
                .containsExactly("a-user", "z-user");
        assertThat(dto.getSystem().getAccessControl().getRoles()).extracting(OrionV2.Role::getId)
                .containsExactly("a-role", "z-role");
        assertThat(dto.getSystem().getAccessControl().getGrants()).extracting(OrionV2.Grant::getId)
                .containsExactly("a-grant", "z-grant");
    }

    @Test
    void sortsNestedAclCollectionsForStableOutput() {
        AccessControl.Credential ssh = new AccessControl.Credential(
                AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, "z-key", "z-value");
        AccessControl.Credential password = new AccessControl.Credential(
                AccessControl.CredentialType.ARGON2, "a-key", "a-value");
        AccessControl.Grant zGrant = new AccessControl.Grant("z-grant", List.of());
        AccessControl.Grant aGrant = new AccessControl.Grant("a-grant", List.of());
        AccessControl.User user = new AccessControl.User(
                "root",
                null,
                null,
                null,
                List.of(ssh, password),
                List.of("z-role", "a-role"),
                List.of(zGrant, aGrant));
        AccessControl.Role role = new AccessControl.Role(
                "root-role",
                List.of(zGrant, aGrant),
                List.of("z-grant", "a-grant"));
        AccessControl.Grant expressions = new AccessControl.Grant(
                "expressions",
                List.of(
                        new AccessControl.GrantExpression(AccessControl.GrantKey.WRITE, "z"),
                        new AccessControl.GrantExpression(AccessControl.GrantKey.READ, "a")));
        OrionDocument document = document(
                new AccessControl(List.of(user), List.of(role), List.of(expressions)),
                List.of());

        OrionV2.AccessControl mapped = OrionV2Mapper.fromCurrent(document).getSystem().getAccessControl();

        assertThat(mapped.getUsers().getFirst().getCredentials())
                .extracting(credential -> credential.getType().name())
                .containsExactly("ARGON2", "OPENSSH_PUBLIC_KEY");
        assertThat(mapped.getUsers().getFirst().getRoles()).containsExactly("a-role", "z-role");
        assertThat(mapped.getUsers().getFirst().getGrants()).extracting(OrionV2.Grant::getId)
                .containsExactly("a-grant", "z-grant");
        assertThat(mapped.getRoles().getFirst().getGrants()).extracting(OrionV2.Grant::getId)
                .containsExactly("a-grant", "z-grant");
        assertThat(mapped.getRoles().getFirst().getGrantReferences())
                .containsExactly("a-grant", "z-grant");
        assertThat(mapped.getGrants().getFirst().getInfo())
                .extracting(expression -> expression.getKey().name())
                .containsExactly("READ", "WRITE");
    }

    @Test
    void rejectsDuplicateHierarchyIdsFromTheWire() {
        OrionV2 duplicateOrganizations = dto(List.of(wireOrganization("acme"), wireOrganization("acme")));
        assertThatThrownBy(() -> OrionV2Mapper.toCurrent(duplicateOrganizations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate organization id: acme");

        OrionV2.Organization organization = wireOrganization("acme");
        organization.setTeams(List.of(wireTeam("platform"), wireTeam("platform")));
        assertThatThrownBy(() -> OrionV2Mapper.toCurrent(dto(List.of(organization))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate team id: platform");

        OrionV2.Team team = wireTeam("platform");
        team.setRepositories(List.of(wireRepository("api"), wireRepository("api")));
        organization.setTeams(List.of(team));
        assertThatThrownBy(() -> OrionV2Mapper.toCurrent(dto(List.of(organization))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate repository id: api");
    }

    @Test
    void rejectsDuplicateAclIdsFromTheWire() {
        assertDuplicateAclIds(
                new OrionV2.AccessControl(
                        List.of(wireUser("root"), wireUser("root")), List.of(), List.of()),
                "duplicate ACL user id: root");
        assertDuplicateAclIds(
                new OrionV2.AccessControl(
                        List.of(), List.of(wireRole("admin"), wireRole("admin")), List.of()),
                "duplicate ACL role id: admin");
        assertDuplicateAclIds(
                new OrionV2.AccessControl(
                        List.of(), List.of(), List.of(wireGrant("read"), wireGrant("read"))),
                "duplicate ACL grant id: read");
    }

    private static void assertDuplicateAclIds(OrionV2.AccessControl accessControl, String message) {
        OrionV2 dto = dto(List.of());
        dto.getSystem().setAccessControl(accessControl);

        assertThatThrownBy(() -> OrionV2Mapper.toCurrent(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static OrionDocument document(
            AccessControl accessControl,
            List<OrionDocument.Organization> organizations) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(accessControl), organizations);
    }

    private static OrionDocument.Organization organization(String id, List<OrionDocument.Team> teams) {
        return new OrionDocument.Organization(new OrganizationId(id), id + " name", teams);
    }

    private static OrionDocument.Team team(String id, List<OrionDocument.Repository> repositories) {
        return new OrionDocument.Team(new TeamId(id), id + " name", repositories);
    }

    private static OrionDocument.Repository repository(String id) {
        return new OrionDocument.Repository(new RepositoryId(id), id + " name");
    }

    private static AccessControlDraft.User user(String id) {
        return new AccessControlDraft.User(
                id, null, null, null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private static AccessControlDraft.Role role(String id) {
        return new AccessControlDraft.Role(id, new ArrayList<>(), new ArrayList<>());
    }

    private static AccessControlDraft.Grant grant(String id) {
        return new AccessControlDraft.Grant(id, new ArrayList<>());
    }

    private static OrionV2 dto(List<OrionV2.Organization> organizations) {
        return new OrionV2(
                OrionV2.SchemaVersion.V2,
                new OrionV2.SystemConfiguration(new OrionV2.AccessControl(List.of(), List.of(), List.of())),
                organizations);
    }

    private static OrionV2.Organization wireOrganization(String id) {
        return new OrionV2.Organization(id, null, List.of());
    }

    private static OrionV2.Team wireTeam(String id) {
        return new OrionV2.Team(id, null, List.of());
    }

    private static OrionV2.Repository wireRepository(String id) {
        return new OrionV2.Repository(id, null);
    }

    private static OrionV2.User wireUser(String id) {
        return new OrionV2.User(id, null, null, null, List.of(), List.of(), List.of());
    }

    private static OrionV2.Role wireRole(String id) {
        return new OrionV2.Role(id, List.of(), List.of());
    }

    private static OrionV2.Grant wireGrant(String id) {
        return new OrionV2.Grant(id, List.of());
    }
}
