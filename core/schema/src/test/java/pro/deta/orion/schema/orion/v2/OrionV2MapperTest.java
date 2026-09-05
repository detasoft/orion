package pro.deta.orion.schema.orion.v2;

import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.orion.ConfigurationSecretReference;
import pro.deta.orion.schema.orion.GrantAddress;
import pro.deta.orion.schema.orion.GrantId;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrganizationUser;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RemoteAlias;
import pro.deta.orion.schema.orion.RemoteProvider;
import pro.deta.orion.schema.orion.RemoteRefMapping;
import pro.deta.orion.schema.orion.RemoteRole;
import pro.deta.orion.schema.orion.RemoteTrigger;
import pro.deta.orion.schema.orion.RemoteUpdatePolicy;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.schema.orion.RepositoryRemote;
import pro.deta.orion.schema.orion.RoleAddress;
import pro.deta.orion.schema.orion.RoleId;
import pro.deta.orion.schema.orion.ScopedGrant;
import pro.deta.orion.schema.orion.ScopedRole;
import pro.deta.orion.schema.orion.TeamId;
import pro.deta.orion.schema.orion.UserCredential;
import pro.deta.orion.schema.orion.UserId;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
    void retainsOrganizationUsersAndScopedDefinitionsOnTheVersionedDtoBoundary() {
        OrionV2.OrganizationCredential credential = new OrionV2.OrganizationCredential();
        credential.setType(OrionV2.OrganizationCredentialType.ARGON2);
        credential.setValue("password-verifier");
        OrionV2.OrganizationUser user = new OrionV2.OrganizationUser();
        user.setId("alice");
        user.setEnabled(true);
        user.setFirst("Alice");
        user.setLast("Example");
        user.setEmail("alice@example.test");
        user.setCredentials(List.of(credential));
        user.setMemberships(List.of("platform"));
        user.setRoles(List.of("acme/developer"));

        OrionV2.Organization organization = wireOrganization("acme");
        organization.setUsers(List.of(user));
        organization.setGrants(List.of(scopedGrant("read")));
        organization.setRoles(List.of(scopedRole("developer")));
        OrionV2.Team team = wireTeam("platform");
        team.setGrants(List.of(scopedGrant("deploy")));
        team.setRoles(List.of(scopedRole("operator")));
        OrionV2.Repository repository = wireRepository("api");
        repository.setGrants(List.of(scopedGrant("push")));
        repository.setRoles(List.of(scopedRole("maintainer")));
        team.setRepositories(List.of(repository));
        organization.setTeams(List.of(team));

        assertThat(organization.getUsers()).containsExactly(user);
        assertThat(organization.getGrants()).extracting(OrionV2.ScopedGrant::getId)
                .containsExactly("read");
        assertThat(organization.getRoles()).extracting(OrionV2.ScopedRole::getId)
                .containsExactly("developer");
        assertThat(team.getGrants()).extracting(OrionV2.ScopedGrant::getId)
                .containsExactly("deploy");
        assertThat(team.getRoles()).extracting(OrionV2.ScopedRole::getId)
                .containsExactly("operator");
        assertThat(repository.getGrants()).extracting(OrionV2.ScopedGrant::getId)
                .containsExactly("push");
        assertThat(repository.getRoles()).extracting(OrionV2.ScopedRole::getId)
                .containsExactly("maintainer");
        assertThat(user.getCredentials()).containsExactly(credential);
        assertThat(user.getMemberships()).containsExactly("platform");
        assertThat(user.getRoles()).containsExactly("acme/developer");
        assertThat(organization.getRoles().getFirst().getRoleReferences())
                .containsExactly("acme/base");
        assertThat(organization.getRoles().getFirst().getGrantReferences())
                .containsExactly("acme/read");
        assertThat(organization.getGrants().getFirst().getExpressions())
                .extracting(OrionV2.ScopedGrantExpression::getKey)
                .containsExactly(OrionV2.GrantKey.READ);
    }

    @Test
    void declaresCanonicalScopedIdentityXmlShape() throws NoSuchFieldException {
        assertThat(propOrder(OrionV2.Organization.class))
                .containsExactly("displayName", "users", "grants", "roles", "teams");
        assertThat(propOrder(OrionV2.Team.class))
                .containsExactly("displayName", "grants", "roles", "repositories");
        assertThat(propOrder(OrionV2.Repository.class))
                .containsExactly("displayName", "defaultBranch", "policy", "remotes", "grants", "roles");
        assertThat(propOrder(OrionV2.OrganizationUser.class))
                .containsExactly("first", "last", "email", "credentials", "memberships", "roles");
        assertThat(propOrder(OrionV2.ScopedRole.class))
                .containsExactly("roleReferences", "grantReferences");
        assertThat(propOrder(OrionV2.ScopedGrant.class)).containsExactly("expressions");

        assertRequiredAttribute(OrionV2.OrganizationUser.class, "id");
        assertRequiredAttribute(OrionV2.OrganizationUser.class, "enabled");
        assertRequiredAttribute(OrionV2.ScopedRole.class, "id");
        assertRequiredAttribute(OrionV2.ScopedGrant.class, "id");
        assertRequiredAttribute(OrionV2.ScopedGrant.class, "effect");
        assertOptionalWrapper(OrionV2.Organization.class, "users");
        assertOptionalWrapper(OrionV2.Organization.class, "grants");
        assertOptionalWrapper(OrionV2.Organization.class, "roles");
        assertOptionalWrapper(OrionV2.Team.class, "grants");
        assertOptionalWrapper(OrionV2.Team.class, "roles");
        assertOptionalWrapper(OrionV2.Repository.class, "grants");
        assertOptionalWrapper(OrionV2.Repository.class, "roles");
        assertOptionalWrapper(OrionV2.OrganizationUser.class, "credentials");
        assertOptionalWrapper(OrionV2.OrganizationUser.class, "memberships");
        assertElementName(OrionV2.OrganizationUser.class, "memberships", "team");
        assertOptionalWrapper(OrionV2.OrganizationUser.class, "roles");
        assertOptionalWrapper(OrionV2.ScopedRole.class, "roleReferences");
        assertOptionalWrapper(OrionV2.ScopedRole.class, "grantReferences");
        assertOptionalWrapper(OrionV2.ScopedGrant.class, "expressions");

        assertThat(OrionV2.OrganizationCredentialType.values())
                .containsExactly(
                        OrionV2.OrganizationCredentialType.ARGON2,
                        OrionV2.OrganizationCredentialType.SHA1,
                        OrionV2.OrganizationCredentialType.OPENSSH_PUBLIC_KEY);
        assertThat(OrionV2.ScopedGrantEffect.values())
                .containsExactly(OrionV2.ScopedGrantEffect.ALLOW, OrionV2.ScopedGrantEffect.DENY);
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
    void roundTripsOrganizationUsersAndDefinitionsAtEveryScope() {
        OrionDocument document = scopedIdentityDocument();

        OrionV2 wire = OrionV2Mapper.fromCurrent(document);
        OrionDocument restored = OrionV2Mapper.toCurrent(wire);

        assertThat(restored).isEqualTo(document);
        assertThat(wire.getSystem().getAccessControl())
                .isEqualTo(new OrionV2.AccessControl(List.of(), List.of(), List.of()));
        assertThat(wire.getOrganizations()).extracting(OrionV2.Organization::getId)
                .containsExactly("acme", "beta");
        assertThat(wire.getOrganizations()).allSatisfy(organization -> {
            assertThat(organization.getUsers()).extracting(OrionV2.OrganizationUser::getId)
                    .contains("alex");
            assertThat(organization.getRoles()).extracting(OrionV2.ScopedRole::getId)
                    .contains("member");
        });
    }

    @Test
    void writesScopedIdentityCollectionsInCanonicalOrder() {
        OrionV2 first = OrionV2Mapper.fromCurrent(orderingDocument(true));
        OrionV2 second = OrionV2Mapper.fromCurrent(orderingDocument(false));

        assertThat(first).isEqualTo(second);
        OrionV2.Organization organization = first.getOrganizations().getFirst();
        assertThat(organization.getUsers()).extracting(OrionV2.OrganizationUser::getId)
                .containsExactly("a-user", "z-user");
        OrionV2.OrganizationUser user = organization.getUsers().getFirst();
        assertThat(user.getCredentials())
                .extracting(
                        credential -> credential.getType().name(),
                        OrionV2.OrganizationCredential::getKeyId,
                        OrionV2.OrganizationCredential::getValue)
                .containsExactly(
                        tuple("ARGON2", null, "a-verifier"),
                        tuple("ARGON2", null, "z-verifier"),
                        tuple("SHA1", null, "a-verifier"),
                        tuple("OPENSSH_PUBLIC_KEY", null, "ssh-ed25519 AQID"),
                        tuple("OPENSSH_PUBLIC_KEY", "z-key", "ssh-ed25519 BAUG"));
        assertThat(user.getMemberships()).containsExactly("a-team", "z-team");
        assertThat(user.getRoles()).containsExactly("acme/a-role", "acme/z-role");
        assertCanonicalScopedDefinitions(organization.getGrants(), organization.getRoles(), "acme");

        OrionV2.Team team = organization.getTeams().getFirst();
        assertThat(organization.getTeams()).extracting(OrionV2.Team::getId)
                .containsExactly("a-team", "z-team");
        assertCanonicalScopedDefinitions(team.getGrants(), team.getRoles(), "acme/a-team");

        OrionV2.Repository repository = team.getRepositories().getFirst();
        assertThat(team.getRepositories()).extracting(OrionV2.Repository::getId)
                .containsExactly("a-repo", "z-repo");
        assertCanonicalScopedDefinitions(
                repository.getGrants(), repository.getRoles(), "acme/a-team/a-repo");
    }

    @Test
    void rejectsDuplicateScopedIdentityIdsFromTheWire() {
        OrionV2.Organization duplicateUsers = wireOrganization("acme");
        duplicateUsers.setUsers(List.of(wireOrganizationUser("alice"), wireOrganizationUser("alice")));
        assertWireFailure(duplicateUsers, "duplicate user id: alice");

        OrionV2.Organization duplicateRoles = wireOrganization("acme");
        duplicateRoles.setRoles(List.of(emptyScopedRole("member"), emptyScopedRole("member")));
        assertWireFailure(duplicateRoles, "duplicate role id: member");

        OrionV2.Organization duplicateGrants = wireOrganization("acme");
        duplicateGrants.setGrants(List.of(wireScopedGrant("read"), wireScopedGrant("read")));
        assertWireFailure(duplicateGrants, "duplicate grant id: read");
    }

    @Test
    void rejectsInvalidOrganizationCredentialsFromTheWire() {
        OrionV2.OrganizationCredential blankVerifier = wireCredential(
                OrionV2.OrganizationCredentialType.ARGON2, null, " ");
        assertCredentialFailure(blankVerifier, "credential value must not be blank");

        OrionV2.OrganizationCredential malformedPublicKey = wireCredential(
                OrionV2.OrganizationCredentialType.OPENSSH_PUBLIC_KEY, "laptop", "ssh-ed25519 invalid!");
        assertCredentialFailure(
                malformedPublicKey, "credential value must be a canonical OpenSSH public key");

        OrionV2.OrganizationCredential blankKeyId = wireCredential(
                OrionV2.OrganizationCredentialType.OPENSSH_PUBLIC_KEY, " ", "ssh-ed25519 AQID");
        assertCredentialFailure(blankKeyId, "credential key id must not be blank");

        OrionV2.OrganizationCredential passwordKeyId = wireCredential(
                OrionV2.OrganizationCredentialType.ARGON2, "legacy", "argon2-verifier");
        assertCredentialFailure(passwordKeyId, "password credential key id must be absent");

        OrionV2.OrganizationCredential duplicate = wireCredential(
                OrionV2.OrganizationCredentialType.SHA1, null, "sha1-verifier");
        OrionV2.Organization organization = wireOrganization("acme");
        OrionV2.OrganizationUser user = wireOrganizationUser("alice");
        user.setCredentials(List.of(duplicate, duplicate));
        organization.setUsers(List.of(user));
        assertThatThrownBy(() -> OrionV2Mapper.toCurrent(dto(List.of(organization))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("duplicate credential:");
    }

    @Test
    void rejectsMissingScopedIdentityTargetsFromTheWire() {
        OrionV2.Organization missingTeam = wireOrganization("acme");
        OrionV2.OrganizationUser member = wireOrganizationUser("alice");
        member.setMemberships(List.of("missing"));
        missingTeam.setUsers(List.of(member));
        assertWireFailure(missingTeam, "missing team membership: acme/missing");

        OrionV2.Organization missingAssignment = wireOrganization("acme");
        OrionV2.OrganizationUser assigned = wireOrganizationUser("alice");
        assigned.setRoles(List.of("acme/missing"));
        missingAssignment.setUsers(List.of(assigned));
        assertWireFailure(missingAssignment, "missing assigned role: acme/missing");

        OrionV2.Organization missingRoleReference = wireOrganization("acme");
        missingRoleReference.setRoles(List.of(wireScopedRole(
                "member", List.of("acme/missing"), List.of())));
        assertWireFailure(missingRoleReference, "missing role reference: acme/missing");

        OrionV2.Organization missingGrantReference = wireOrganization("acme");
        missingGrantReference.setRoles(List.of(wireScopedRole(
                "member", List.of(), List.of("acme/missing"))));
        assertWireFailure(missingGrantReference, "missing grant reference: acme/missing");
    }

    @Test
    void rejectsEscapingCrossOrganizationAndDownwardReferencesFromTheWire() {
        OrionV2.Organization escaping = wireOrganization("acme");
        escaping.setRoles(List.of(wireScopedRole("member", List.of("../admin"), List.of())));
        assertWireFailure(
                escaping, "organization id must be a canonical lowercase identifier: ..");

        OrionV2.Organization crossOrganization = wireOrganization("acme");
        crossOrganization.setRoles(List.of(wireScopedRole("member", List.of("other/member"), List.of())));
        OrionV2.Organization other = wireOrganization("other");
        other.setRoles(List.of(emptyScopedRole("member")));
        assertWireFailure(
                List.of(crossOrganization, other),
                "role reference outside scope: other/member");

        OrionV2.Organization downward = wireOrganization("acme");
        downward.setRoles(List.of(wireScopedRole(
                "member", List.of(), List.of("acme/platform/write"))));
        OrionV2.Team platform = wireTeam("platform");
        platform.setGrants(List.of(wireScopedGrant("write")));
        downward.setTeams(List.of(platform));
        assertWireFailure(downward, "grant reference outside scope: acme/platform/write");
    }

    @Test
    void rejectsScopedRoleCyclesAfterMappingTheCompleteDocument() {
        OrionV2.Organization organization = wireOrganization("acme");
        organization.setRoles(List.of(
                wireScopedRole("first", List.of("acme/second"), List.of()),
                wireScopedRole("second", List.of("acme/first"), List.of())));

        assertWireFailure(organization, "role cycle closes at: acme/first");
    }

    @Test
    void mapsMissingNestedScopedIdentityWrappersToEmptyCollections() {
        OrionV2.OrganizationUser user = wireOrganizationUser("alice");
        user.setCredentials(null);
        user.setMemberships(null);
        user.setRoles(null);
        OrionV2.ScopedRole role = new OrionV2.ScopedRole("member", null, null);
        OrionV2.ScopedGrant grant = new OrionV2.ScopedGrant(
                "read", OrionV2.ScopedGrantEffect.ALLOW, null);
        OrionV2.Organization organization = wireOrganization("acme");
        organization.setUsers(List.of(user));
        organization.setRoles(List.of(role));
        organization.setGrants(List.of(grant));

        OrionDocument.Organization mapped = OrionV2Mapper.toCurrent(dto(List.of(organization)))
                .organizations().getFirst();

        assertThat(mapped.users().getFirst().credentials()).isEmpty();
        assertThat(mapped.users().getFirst().teamMemberships()).isEmpty();
        assertThat(mapped.users().getFirst().roleAssignments()).isEmpty();
        assertThat(mapped.roles().getFirst().roleReferences()).isEmpty();
        assertThat(mapped.roles().getFirst().grantReferences()).isEmpty();
        assertThat(mapped.grants().getFirst().expressions()).isEmpty();
    }

    @Test
    void mapsRepositoryPolicyAndPrimaryRemoteBothWays() {
        RepositoryRemote upstream = remote(
                "upstream",
                RemoteRole.PRIMARY,
                RemoteProvider.GITHUB,
                List.of(RemoteRefMapping.allBranches()));
        OrionDocument.Repository repository = new OrionDocument.Repository(
                new RepositoryId("api"),
                "API",
                "refs/heads/trunk",
                new RepositoryPolicy(true, false, true),
                List.of(upstream),
                List.of(),
                List.of());
        OrionDocument source = document(new AccessControl(), List.of(
                organization("acme", List.of(team("platform", List.of(repository))))));

        OrionV2 wire = OrionV2Mapper.fromCurrent(source);
        OrionDocument restored = OrionV2Mapper.toCurrent(wire);

        assertThat(restored).isEqualTo(source);
        OrionV2.Repository wireRepository = wire.getOrganizations().getFirst()
                .getTeams().getFirst().getRepositories().getFirst();
        OrionV2.Remote remote = wireRepository.getRemotes().getFirst();
        assertThat(wireRepository.getDefaultBranch()).isEqualTo("refs/heads/trunk");
        assertThat(wireRepository.getPolicy().isAllowForcePushes()).isTrue();
        assertThat(remote.getAlias()).isEqualTo("upstream");
        assertThat(remote.getRole()).isEqualTo(OrionV2.RemoteRole.PRIMARY);
        assertThat(remote.getCredential().getReference()).isEqualTo("github-token");
    }

    @Test
    void suppliesSafeDefaultsForAnOlderMinimalV2Repository() {
        OrionV2.Repository wireRepository = new OrionV2.Repository();
        wireRepository.setId("api");
        OrionV2.Team team = wireTeam("platform");
        team.setRepositories(List.of(wireRepository));
        OrionV2.Organization organization = wireOrganization("acme");
        organization.setTeams(List.of(team));

        OrionDocument mapped = OrionV2Mapper.toCurrent(dto(List.of(organization)));
        OrionDocument.Organization mappedOrganization = mapped.organizations().getFirst();
        OrionDocument.Team mappedTeam = mappedOrganization.teams().getFirst();
        OrionDocument.Repository repository = mappedTeam.repositories().getFirst();

        assertThat(repository.defaultBranch()).isEqualTo("refs/heads/main");
        assertThat(repository.policy()).isEqualTo(RepositoryPolicy.safeDefaults());
        assertThat(repository.remotes()).isEmpty();
        assertThat(mappedOrganization.users()).isEmpty();
        assertThat(mappedOrganization.grants()).isEmpty();
        assertThat(mappedOrganization.roles()).isEmpty();
        assertThat(mappedTeam.grants()).isEmpty();
        assertThat(mappedTeam.roles()).isEmpty();
        assertThat(repository.grants()).isEmpty();
        assertThat(repository.roles()).isEmpty();
    }

    @Test
    void sortsRemoteConfigurationForStableOutput() {
        List<RemoteRefMapping> mappings = List.of(
                new RemoteRefMapping("refs/heads/z", "refs/heads/z"),
                new RemoteRefMapping("refs/heads/a", "refs/heads/a"));
        RepositoryRemote zeta = remote("zeta", RemoteRole.OUTBOUND_ONLY, RemoteProvider.GENERIC, mappings);
        RepositoryRemote alpha = remote("alpha", RemoteRole.OUTBOUND_ONLY, RemoteProvider.GENERIC, mappings);
        OrionDocument.Repository repository = new OrionDocument.Repository(
                new RepositoryId("api"),
                null,
                "refs/heads/main",
                RepositoryPolicy.safeDefaults(),
                List.of(zeta, alpha),
                List.of(),
                List.of());
        OrionDocument source = document(new AccessControl(), List.of(
                organization("acme", List.of(team("platform", List.of(repository))))));

        OrionV2.Repository mapped = OrionV2Mapper.fromCurrent(source).getOrganizations().getFirst()
                .getTeams().getFirst().getRepositories().getFirst();

        assertThat(mapped.getRemotes()).extracting(OrionV2.Remote::getAlias)
                .containsExactly("alpha", "zeta");
        assertThat(mapped.getRemotes().getFirst().getTriggers())
                .containsExactly(OrionV2.RemoteTrigger.LOCAL_REF_UPDATE, OrionV2.RemoteTrigger.PERIODIC_AUDIT);
        assertThat(mapped.getRemotes().getFirst().getRefMappings())
                .extracting(OrionV2.RefMapping::getSource)
                .containsExactly("refs/heads/a", "refs/heads/z");
    }

    @Test
    void roundTripsMultipleRemotesAndMappingsWithoutChangingTheDocumentValue() {
        List<RemoteRefMapping> mappings = List.of(
                new RemoteRefMapping("refs/heads/z", "refs/heads/z"),
                new RemoteRefMapping("refs/heads/a", "refs/heads/a"));
        RepositoryRemote zeta = remote("zeta", RemoteRole.OUTBOUND_ONLY, RemoteProvider.GENERIC, mappings);
        RepositoryRemote alpha = remote("alpha", RemoteRole.OUTBOUND_ONLY, RemoteProvider.GENERIC, mappings);
        OrionDocument.Repository repository = new OrionDocument.Repository(
                new RepositoryId("api"),
                null,
                OrionDocument.Repository.DEFAULT_BRANCH,
                RepositoryPolicy.safeDefaults(),
                List.of(zeta, alpha),
                List.of(),
                List.of());
        OrionDocument source = document(new AccessControl(), List.of(
                organization("acme", List.of(team("platform", List.of(repository))))));

        OrionDocument restored = OrionV2Mapper.toCurrent(OrionV2Mapper.fromCurrent(source));

        assertThat(restored).isEqualTo(source);
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

    private static OrionDocument scopedIdentityDocument() {
        ScopedGrant organizationGrant = domainGrant(
                "access", ScopedGrant.Effect.ALLOW, expression(AccessControl.GrantKey.READ, "true"));
        ScopedRole organizationRole = domainRole(
                "member", List.of(), List.of("acme/access"));
        ScopedGrant teamGrant = domainGrant(
                "deploy", ScopedGrant.Effect.DENY, expression(AccessControl.GrantKey.WRITE, "true"));
        ScopedRole teamRole = domainRole(
                "member", List.of("acme/member"), List.of("acme/access", "acme/platform/deploy"));
        ScopedGrant repositoryGrant = domainGrant(
                "force",
                ScopedGrant.Effect.ALLOW,
                expression(AccessControl.GrantKey.BRANCH, "refs/heads/main"),
                expression(AccessControl.GrantKey.FORCE, "true"));
        ScopedRole repositoryRole = domainRole(
                "member",
                List.of("acme/member", "acme/platform/member"),
                List.of("acme/platform/api/force"));
        OrionDocument.Repository repository = domainRepository(
                "api", List.of(repositoryGrant), List.of(repositoryRole));
        OrionDocument.Team team = domainTeam(
                "platform", List.of(teamGrant), List.of(teamRole), List.of(repository));
        OrganizationUser alex = domainUser(
                "alex",
                true,
                List.of(
                        UserCredential.passwordVerifier(UserCredential.Type.ARGON2, "argon2-verifier"),
                        UserCredential.passwordVerifier(UserCredential.Type.SHA1, "sha1-verifier"),
                        UserCredential.publicKey("workstation", "ssh-ed25519 AQID")),
                List.of("platform"),
                List.of("acme/member", "acme/platform/api/member", "acme/platform/member"));
        OrganizationUser blocked = domainUser("blocked", false, List.of(), List.of(), List.of());
        OrionDocument.Organization acme = domainOrganization(
                "acme",
                List.of(alex, blocked),
                List.of(organizationGrant),
                List.of(organizationRole),
                List.of(team));

        ScopedGrant betaGrant = domainGrant(
                "access", ScopedGrant.Effect.DENY, expression(AccessControl.GrantKey.READ, "true"));
        ScopedRole betaRole = domainRole("member", List.of(), List.of("beta/access"));
        OrganizationUser betaAlex = domainUser(
                "alex", false, List.of(), List.of(), List.of("beta/member"));
        OrionDocument.Organization beta = domainOrganization(
                "beta",
                List.of(betaAlex),
                List.of(betaGrant),
                List.of(betaRole),
                List.of());
        return document(new AccessControl(), List.of(acme, beta));
    }

    private static OrionDocument orderingDocument(boolean reversed) {
        ScopedGrant aOrganizationGrant = orderedGrant("a-grant", reversed);
        ScopedGrant zOrganizationGrant = orderedGrant("z-grant", reversed);
        ScopedRole aOrganizationRole = domainRole("a-role", List.of(), List.of());
        ScopedRole zOrganizationRole = domainRole("z-role", List.of(), List.of());
        ScopedRole organizationRoot = domainRole(
                "root-role",
                addresses(reversed, "acme/a-role", "acme/z-role"),
                addresses(reversed, "acme/a-grant", "acme/z-grant"));

        OrionDocument.Team aTeam = orderingTeam("a-team", reversed);
        OrionDocument.Team zTeam = domainTeam("z-team", List.of(), List.of(), List.of());
        OrganizationUser aUser = orderingUser("a-user", reversed);
        OrganizationUser zUser = orderingUser("z-user", reversed);
        OrionDocument.Organization acme = domainOrganization(
                "acme",
                reversed ? List.of(zUser, aUser) : List.of(aUser, zUser),
                reversed
                        ? List.of(zOrganizationGrant, aOrganizationGrant)
                        : List.of(aOrganizationGrant, zOrganizationGrant),
                reversed
                        ? List.of(zOrganizationRole, organizationRoot, aOrganizationRole)
                        : List.of(aOrganizationRole, organizationRoot, zOrganizationRole),
                reversed ? List.of(zTeam, aTeam) : List.of(aTeam, zTeam));
        OrionDocument.Organization zeta = domainOrganization(
                "zeta", List.of(), List.of(), List.of(), List.of());
        return document(
                new AccessControl(),
                reversed ? List.of(zeta, acme) : List.of(acme, zeta));
    }

    private static OrionDocument.Team orderingTeam(String id, boolean reversed) {
        String scope = "acme/" + id;
        ScopedGrant aGrant = orderedGrant("a-grant", reversed);
        ScopedGrant zGrant = orderedGrant("z-grant", reversed);
        ScopedRole aRole = domainRole("a-role", List.of(), List.of());
        ScopedRole zRole = domainRole("z-role", List.of(), List.of());
        ScopedRole root = domainRole(
                "root-role",
                addresses(reversed, scope + "/a-role", scope + "/z-role"),
                addresses(reversed, scope + "/a-grant", scope + "/z-grant"));
        OrionDocument.Repository aRepository = orderingRepository(id, "a-repo", reversed);
        OrionDocument.Repository zRepository = domainRepository(
                "z-repo", List.of(), List.of());
        return domainTeam(
                id,
                reversed ? List.of(zGrant, aGrant) : List.of(aGrant, zGrant),
                reversed ? List.of(zRole, root, aRole) : List.of(aRole, root, zRole),
                reversed ? List.of(zRepository, aRepository) : List.of(aRepository, zRepository));
    }

    private static OrionDocument.Repository orderingRepository(
            String teamId,
            String repositoryId,
            boolean reversed) {
        String scope = "acme/" + teamId + "/" + repositoryId;
        ScopedGrant aGrant = orderedGrant("a-grant", reversed);
        ScopedGrant zGrant = orderedGrant("z-grant", reversed);
        ScopedRole aRole = domainRole("a-role", List.of(), List.of());
        ScopedRole zRole = domainRole("z-role", List.of(), List.of());
        ScopedRole root = domainRole(
                "root-role",
                addresses(reversed, scope + "/a-role", scope + "/z-role"),
                addresses(reversed, scope + "/a-grant", scope + "/z-grant"));
        return domainRepository(
                repositoryId,
                reversed ? List.of(zGrant, aGrant) : List.of(aGrant, zGrant),
                reversed ? List.of(zRole, root, aRole) : List.of(aRole, root, zRole));
    }

    private static OrganizationUser orderingUser(String id, boolean reversed) {
        UserCredential aArgon2 = UserCredential.passwordVerifier(UserCredential.Type.ARGON2, "a-verifier");
        UserCredential zArgon2 = UserCredential.passwordVerifier(UserCredential.Type.ARGON2, "z-verifier");
        UserCredential sha1 = UserCredential.passwordVerifier(UserCredential.Type.SHA1, "a-verifier");
        UserCredential anonymousKey = UserCredential.publicKey("ssh-ed25519 AQID");
        UserCredential namedKey = UserCredential.publicKey("z-key", "ssh-ed25519 BAUG");
        List<UserCredential> credentials = reversed
                ? List.of(namedKey, anonymousKey, sha1, zArgon2, aArgon2)
                : List.of(aArgon2, zArgon2, sha1, anonymousKey, namedKey);
        return domainUser(
                id,
                true,
                credentials,
                reversed ? List.of("z-team", "a-team") : List.of("a-team", "z-team"),
                addresses(reversed, "acme/a-role", "acme/z-role"));
    }

    private static ScopedGrant orderedGrant(String id, boolean reversed) {
        AccessControl.GrantExpression aRead = expression(AccessControl.GrantKey.READ, "a-read");
        AccessControl.GrantExpression zRead = expression(AccessControl.GrantKey.READ, "z-read");
        AccessControl.GrantExpression write = expression(AccessControl.GrantKey.WRITE, "write");
        return new ScopedGrant(
                new GrantId(id),
                ScopedGrant.Effect.ALLOW,
                reversed ? List.of(write, zRead, aRead) : List.of(aRead, zRead, write));
    }

    private static void assertCanonicalScopedDefinitions(
            List<OrionV2.ScopedGrant> grants,
            List<OrionV2.ScopedRole> roles,
            String scope) {
        assertThat(grants).extracting(OrionV2.ScopedGrant::getId)
                .containsExactly("a-grant", "z-grant");
        assertThat(grants.getFirst().getExpressions())
                .extracting(
                        expression -> expression.getKey().name(),
                        OrionV2.ScopedGrantExpression::getValue)
                .containsExactly(
                        tuple("READ", "a-read"),
                        tuple("READ", "z-read"),
                        tuple("WRITE", "write"));
        assertThat(roles).extracting(OrionV2.ScopedRole::getId)
                .containsExactly("a-role", "root-role", "z-role");
        assertThat(roles.get(1).getRoleReferences())
                .containsExactly(scope + "/a-role", scope + "/z-role");
        assertThat(roles.get(1).getGrantReferences())
                .containsExactly(scope + "/a-grant", scope + "/z-grant");
    }

    private static void assertCredentialFailure(OrionV2.OrganizationCredential credential, String message) {
        OrionV2.Organization organization = wireOrganization("acme");
        OrionV2.OrganizationUser user = wireOrganizationUser("alice");
        user.setCredentials(List.of(credential));
        organization.setUsers(List.of(user));
        assertWireFailure(organization, message);
    }

    private static void assertWireFailure(OrionV2.Organization organization, String message) {
        assertWireFailure(List.of(organization), message);
    }

    private static void assertWireFailure(List<OrionV2.Organization> organizations, String message) {
        assertThatThrownBy(() -> OrionV2Mapper.toCurrent(dto(organizations)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private static OrganizationUser domainUser(
            String id,
            boolean enabled,
            List<UserCredential> credentials,
            List<String> memberships,
            List<String> roles) {
        List<TeamId> teamIds = new ArrayList<>();
        for (String membership : memberships) {
            teamIds.add(new TeamId(membership));
        }
        List<RoleAddress> roleAddresses = new ArrayList<>();
        for (String role : roles) {
            roleAddresses.add(RoleAddress.parse(role));
        }
        return new OrganizationUser(
                new UserId(id), id + " first", id + " last", id + "@example.test", enabled,
                credentials, teamIds, roleAddresses);
    }

    private static OrionDocument.Organization domainOrganization(
            String id,
            List<OrganizationUser> users,
            List<ScopedGrant> grants,
            List<ScopedRole> roles,
            List<OrionDocument.Team> teams) {
        return new OrionDocument.Organization(
                new OrganizationId(id), id + " name", users, grants, roles, teams);
    }

    private static OrionDocument.Team domainTeam(
            String id,
            List<ScopedGrant> grants,
            List<ScopedRole> roles,
            List<OrionDocument.Repository> repositories) {
        return new OrionDocument.Team(new TeamId(id), id + " name", grants, roles, repositories);
    }

    private static OrionDocument.Repository domainRepository(
            String id,
            List<ScopedGrant> grants,
            List<ScopedRole> roles) {
        return new OrionDocument.Repository(
                new RepositoryId(id), id + " name", OrionDocument.Repository.DEFAULT_BRANCH,
                RepositoryPolicy.safeDefaults(), List.of(), grants, roles);
    }

    private static ScopedRole domainRole(
            String id,
            List<String> roleReferences,
            List<String> grantReferences) {
        List<RoleAddress> roleAddresses = new ArrayList<>();
        for (String reference : roleReferences) {
            roleAddresses.add(RoleAddress.parse(reference));
        }
        List<GrantAddress> grantAddresses = new ArrayList<>();
        for (String reference : grantReferences) {
            grantAddresses.add(GrantAddress.parse(reference));
        }
        return new ScopedRole(new RoleId(id), roleAddresses, grantAddresses);
    }

    private static ScopedGrant domainGrant(
            String id,
            ScopedGrant.Effect effect,
            AccessControl.GrantExpression... expressions) {
        return new ScopedGrant(new GrantId(id), effect, List.of(expressions));
    }

    private static AccessControl.GrantExpression expression(AccessControl.GrantKey key, String value) {
        return new AccessControl.GrantExpression(key, value);
    }

    private static List<String> addresses(boolean reversed, String first, String second) {
        return reversed ? List.of(second, first) : List.of(first, second);
    }

    private static OrionDocument document(
            AccessControl accessControl,
            List<OrionDocument.Organization> organizations) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(accessControl), organizations);
    }

    private static OrionDocument.Organization organization(String id, List<OrionDocument.Team> teams) {
        return new OrionDocument.Organization(
                new OrganizationId(id), id + " name", List.of(), List.of(), List.of(), teams);
    }

    private static OrionDocument.Team team(String id, List<OrionDocument.Repository> repositories) {
        return new OrionDocument.Team(new TeamId(id), id + " name", List.of(), List.of(), repositories);
    }

    private static OrionDocument.Repository repository(String id) {
        return new OrionDocument.Repository(
                new RepositoryId(id),
                id + " name",
                "refs/heads/main",
                RepositoryPolicy.safeDefaults(),
                List.of(),
                List.of(),
                List.of());
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
        return new OrionV2.Organization(id, null, null, null, null, List.of());
    }

    private static OrionV2.OrganizationUser wireOrganizationUser(String id) {
        return new OrionV2.OrganizationUser(
                id, true, null, null, null, List.of(), List.of(), List.of());
    }

    private static OrionV2.OrganizationCredential wireCredential(
            OrionV2.OrganizationCredentialType type,
            String keyId,
            String value) {
        return new OrionV2.OrganizationCredential(type, keyId, value);
    }

    private static OrionV2.Team wireTeam(String id) {
        return new OrionV2.Team(id, null, null, null, List.of());
    }

    private static OrionV2.Repository wireRepository(String id) {
        OrionV2.Repository repository = new OrionV2.Repository();
        repository.setId(id);
        return repository;
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

    private static OrionV2.ScopedRole scopedRole(String id) {
        OrionV2.ScopedRole role = new OrionV2.ScopedRole();
        role.setId(id);
        role.setRoleReferences(List.of("acme/base"));
        role.setGrantReferences(List.of("acme/read"));
        return role;
    }

    private static OrionV2.ScopedRole emptyScopedRole(String id) {
        return wireScopedRole(id, List.of(), List.of());
    }

    private static OrionV2.ScopedRole wireScopedRole(
            String id,
            List<String> roleReferences,
            List<String> grantReferences) {
        return new OrionV2.ScopedRole(id, roleReferences, grantReferences);
    }

    private static OrionV2.ScopedGrant wireScopedGrant(String id) {
        return new OrionV2.ScopedGrant(
                id, OrionV2.ScopedGrantEffect.ALLOW, List.of());
    }

    private static OrionV2.ScopedGrant scopedGrant(String id) {
        OrionV2.ScopedGrantExpression expression = new OrionV2.ScopedGrantExpression();
        expression.setKey(OrionV2.GrantKey.READ);
        expression.setValue("true");
        OrionV2.ScopedGrant grant = new OrionV2.ScopedGrant();
        grant.setId(id);
        grant.setEffect(OrionV2.ScopedGrantEffect.ALLOW);
        grant.setExpressions(List.of(expression));
        return grant;
    }

    private static String[] propOrder(Class<?> type) {
        return type.getAnnotation(XmlType.class).propOrder();
    }

    private static void assertRequiredAttribute(Class<?> type, String fieldName) throws NoSuchFieldException {
        XmlAttribute attribute = type.getDeclaredField(fieldName).getAnnotation(XmlAttribute.class);

        assertThat(attribute).isNotNull();
        assertThat(attribute.required()).isTrue();
    }

    private static void assertOptionalWrapper(Class<?> type, String fieldName) throws NoSuchFieldException {
        XmlElementWrapper wrapper = type.getDeclaredField(fieldName).getAnnotation(XmlElementWrapper.class);

        assertThat(wrapper).isNotNull();
        assertThat(wrapper.required()).isFalse();
    }

    private static void assertElementName(Class<?> type, String fieldName, String elementName)
            throws NoSuchFieldException {
        XmlElement element = type.getDeclaredField(fieldName).getAnnotation(XmlElement.class);

        assertThat(element).isNotNull();
        assertThat(element.name()).isEqualTo(elementName);
    }

    private static RepositoryRemote remote(
            String alias,
            RemoteRole role,
            RemoteProvider provider,
            List<RemoteRefMapping> mappings) {
        Set<RemoteTrigger> triggers = new LinkedHashSet<>();
        triggers.add(RemoteTrigger.PERIODIC_AUDIT);
        triggers.add(RemoteTrigger.LOCAL_REF_UPDATE);
        return new RepositoryRemote(
                new RemoteAlias(alias),
                role,
                provider,
                URI.create("https://github.com/acme/project.git"),
                new ConfigurationSecretReference(
                        ConfigurationSecretReference.Scope.REPOSITORY,
                        "github-token"),
                triggers,
                mappings,
                RemoteUpdatePolicy.fastForwardOnly());
    }
}
