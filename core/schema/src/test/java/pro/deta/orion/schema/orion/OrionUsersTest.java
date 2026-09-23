package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionUsersTest {
    @Test
    void sharesUserValuesBetweenSystemAndOrganizationWithoutSharingMutableCollections() {
        List<AccessControl.Credential> credentials = new ArrayList<>(List.of(
                new AccessControl.Credential(AccessControl.CredentialType.ARGON2, "password-verifier"),
                new AccessControl.Credential(
                        AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, "laptop", "ssh-ed25519 AQID")));
        List<AccessControl.Grant> grants = new ArrayList<>(List.of(new AccessControl.Grant(
                "read", List.of(new AccessControl.GrantExpression(
                        AccessControl.GrantKey.REPOSITORY, "acme/platform/api")))));
        AccessControl.User user = new AccessControl.User(
                "alice", "Alice", "Example", "alice@example.test", credentials, List.of(), grants);
        List<AccessControl.User> users = new ArrayList<>(List.of(user));
        OrionDocument document = new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(users, List.of(), List.of())),
                List.of(organization("acme", users), organization("other", users)));

        credentials.clear();
        grants.clear();
        users.clear();

        AccessControl.User member = document.organizations().getFirst().users().getFirst();
        assertThat(member).isEqualTo(document.system().accessControl().getUsers().getFirst());
        assertThat(member).isEqualTo(document.organizations().get(1).users().getFirst());
        assertThat(member.getCredentials()).hasSize(2);
        assertThat(member.getCredentials().get(1).getKeyId()).isEqualTo("laptop");
        assertThat(member.getGrants()).extracting(AccessControl.Grant::getId).containsExactly("read");
        assertThatThrownBy(() -> member.getCredentials().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> document.organizations().getFirst().users().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void retainsOptionalProfileFieldsAndRequiresCanonicalOrganizationUserIds() {
        AccessControl.User user = new AccessControl.User(
                "alice", null, null, null, List.of(), List.of(), List.of());
        OrionDocument document = document(organization("acme", List.of(user)));

        assertThat(document.organizations().getFirst().users().getFirst()).isEqualTo(user);
        AccessControl.User invalid = new AccessControl.User(
                "../alice", null, null, null, List.of(), List.of(), List.of());
        assertThatThrownBy(() -> document(organization("acme", List.of(invalid))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OrionDocument document(OrionDocument.Organization organization) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()), List.of(organization));
    }

    private static OrionDocument.Organization organization(String id, List<AccessControl.User> users) {
        return new OrionDocument.Organization(
                new OrganizationId(id), null, users, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
