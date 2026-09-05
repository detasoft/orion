package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationUserTest {
    private static final String ARGON2 = "$argon2id$v=19$m=65536,t=3,p=1$...";
    private static final String OPENSSH_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAea3dh/09YLAW6avBUEmRywMDHCoHMsVIkmk73QuwtO";

    @Test
    void representsEnabledAndDisabledUsers() {
        OrganizationUser enabled = user(true, List.of(), List.of(), List.of());
        OrganizationUser disabled = user(false, List.of(), List.of(), List.of());

        assertThat(enabled.enabled()).isTrue();
        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void carriesTeamMembershipsAndRoleAssignments() {
        OrganizationUser user = user(
                true,
                List.of(),
                List.of(new TeamId("platform")),
                List.of(RoleAddress.parse("acme/developer")));

        assertThat(user.teamMemberships()).containsExactly(new TeamId("platform"));
        assertThat(user.roleAssignments()).containsExactly(RoleAddress.parse("acme/developer"));
    }

    @Test
    void defensivelyCopiesCollections() {
        List<UserCredential> credentials = new ArrayList<>();
        List<TeamId> memberships = new ArrayList<>();
        List<RoleAddress> roles = new ArrayList<>();
        credentials.add(UserCredential.passwordVerifier(UserCredential.Type.ARGON2, ARGON2));
        memberships.add(new TeamId("platform"));
        roles.add(RoleAddress.parse("acme/developer"));

        OrganizationUser user = user(true, credentials, memberships, roles);
        credentials.clear();
        memberships.clear();
        roles.clear();

        assertThat(user.credentials()).hasSize(1);
        assertThat(user.teamMemberships()).containsExactly(new TeamId("platform"));
        assertThat(user.roleAssignments()).containsExactly(RoleAddress.parse("acme/developer"));
        assertThatThrownBy(() -> user.credentials().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> user.teamMemberships().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> user.roleAssignments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void canonicalizesCollectionsForOrderingIndependentValueSemantics() {
        UserCredential password = UserCredential.passwordVerifier(UserCredential.Type.ARGON2, ARGON2);
        UserCredential publicKey = UserCredential.publicKey("workstation", OPENSSH_KEY);
        OrganizationUser first = user(
                true,
                List.of(publicKey, password),
                List.of(new TeamId("security"), new TeamId("platform")),
                List.of(RoleAddress.parse("acme/platform/maintainer"), RoleAddress.parse("acme/developer")));
        OrganizationUser second = user(
                true,
                List.of(password, publicKey),
                List.of(new TeamId("platform"), new TeamId("security")),
                List.of(RoleAddress.parse("acme/developer"), RoleAddress.parse("acme/platform/maintainer")));

        assertThat(first).isEqualTo(second);
        assertThat(first.credentials()).containsExactly(password, publicKey);
        assertThat(first.teamMemberships())
                .containsExactly(new TeamId("platform"), new TeamId("security"));
        assertThat(first.roleAssignments())
                .containsExactly(
                        RoleAddress.parse("acme/developer"),
                        RoleAddress.parse("acme/platform/maintainer"));
    }

    @Test
    void rejectsDuplicateCollectionValues() {
        UserCredential password = UserCredential.passwordVerifier(UserCredential.Type.ARGON2, ARGON2);
        TeamId platform = new TeamId("platform");
        RoleAddress developer = RoleAddress.parse("acme/developer");

        assertThatThrownBy(() -> user(true, List.of(password, password), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate credential");
        assertThatThrownBy(() -> user(true, List.of(), List.of(platform, platform), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate team membership");
        assertThatThrownBy(() -> user(true, List.of(), List.of(), List.of(developer, developer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate role assignment");
    }

    @Test
    void rejectsNullCollectionValues() {
        List<UserCredential> credentials = Collections.singletonList(null);
        List<TeamId> memberships = Collections.singletonList(null);
        List<RoleAddress> roles = Collections.singletonList(null);

        assertThatThrownBy(() -> user(true, credentials, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("credential");
        assertThatThrownBy(() -> user(true, List.of(), memberships, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("team membership");
        assertThatThrownBy(() -> user(true, List.of(), List.of(), roles))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("role assignment");
    }

    @Test
    void createsOnlyVerifierAndOpenSshCredentialTypes() {
        UserCredential argon2 = UserCredential.passwordVerifier(UserCredential.Type.ARGON2, ARGON2);
        UserCredential sha1 = UserCredential.passwordVerifier(UserCredential.Type.SHA1, "sha1-verifier");
        UserCredential publicKey = UserCredential.publicKey(OPENSSH_KEY);

        assertThat(UserCredential.Type.values()).containsExactly(
                UserCredential.Type.ARGON2,
                UserCredential.Type.SHA1,
                UserCredential.Type.OPENSSH_PUBLIC_KEY);
        assertThat(argon2.keyId()).isNull();
        assertThat(sha1.type()).isEqualTo(UserCredential.Type.SHA1);
        assertThat(publicKey.type()).isEqualTo(UserCredential.Type.OPENSSH_PUBLIC_KEY);
        assertThatThrownBy(
                () -> UserCredential.passwordVerifier(UserCredential.Type.OPENSSH_PUBLIC_KEY, OPENSSH_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password verifier");
    }

    @Test
    void rejectsBlankCredentialValuesAndKeyIdentifiers() {
        assertThatThrownBy(() -> UserCredential.passwordVerifier(UserCredential.Type.ARGON2, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
        assertThatThrownBy(() -> UserCredential.publicKey(" ", OPENSSH_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key id");
    }

    @Test
    void validatesCanonicalOpenSshPublicKeys() {
        assertThat(UserCredential.publicKey("workstation", OPENSSH_KEY).value()).isEqualTo(OPENSSH_KEY);

        for (String invalid : List.of(
                "unknown-key AAAA",
                "ssh-ed25519 not-base64!",
                OPENSSH_KEY + " workstation")) {
            assertThatThrownBy(() -> UserCredential.publicKey(invalid))
                    .as("OpenSSH key %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OpenSSH public key");
        }
    }

    @Test
    void allowsNullableProfileFields() {
        OrganizationUser user = new OrganizationUser(
                new UserId("alice"), null, null, null, true, List.of(), List.of(), List.of());

        assertThat(user.first()).isNull();
        assertThat(user.last()).isNull();
        assertThat(user.email()).isNull();
    }

    private static OrganizationUser user(
            boolean enabled,
            List<UserCredential> credentials,
            List<TeamId> memberships,
            List<RoleAddress> roles) {
        return new OrganizationUser(
                new UserId("alice"),
                "Alice",
                "Operator",
                "alice@example.test",
                enabled,
                credentials,
                memberships,
                roles);
    }
}
