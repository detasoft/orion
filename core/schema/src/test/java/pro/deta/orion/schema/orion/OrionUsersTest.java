package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.CredentialType;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionUsersTest {
    @Test
    void sharesUserValuesBetweenSystemAndOrganizationWithoutSharingMutableCollections() {
        List<Credential> credentials = new ArrayList<>(List.of(
                new Credential(CredentialType.ARGON2, "password-verifier"),
                new Credential(
                        CredentialType.OPENSSH_PUBLIC_KEY, "laptop", "ssh-ed25519 AQID")));
        List<Grant> grants = new ArrayList<>(List.of(new Grant(
                "read", List.of(new GrantExpression(
                        GrantKey.REPOSITORY, "acme/platform/api")))));
        User user = new User(
                "alice", "Alice", "Example", "alice@example.test", credentials, List.of(), grants);
        List<User> users = new ArrayList<>(List.of(user));
        OrionDocument document = new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(users, List.of(), List.of())),
                List.of(organization("acme", users), organization("other", users)));

        credentials.clear();
        grants.clear();
        users.clear();

        User member = document.organizations().getFirst().users().getFirst();
        assertThat(member).isEqualTo(document.system().accessControl().users().getFirst());
        assertThat(member).isEqualTo(document.organizations().get(1).users().getFirst());
        assertThat(member.credentials()).hasSize(2);
        assertThat(member.credentials().get(1).keyId()).isEqualTo("laptop");
        assertThat(member.grants()).extracting(Grant::id).containsExactly("read");
        assertThatThrownBy(() -> member.credentials().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> document.organizations().getFirst().users().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void retainsOptionalProfileFieldsAndRequiresCanonicalOrganizationUserIds() {
        User user = new User(
                "alice", null, null, null, List.of(), List.of(), List.of());
        OrionDocument document = document(organization("acme", List.of(user)));

        assertThat(document.organizations().getFirst().users().getFirst()).isEqualTo(user);
        User invalid = new User(
                "../alice", null, null, null, List.of(), List.of(), List.of());
        assertThatThrownBy(() -> document(organization("acme", List.of(invalid))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OrionDocument document(OrionDocument.Organization organization) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()), List.of(organization));
    }

    private static OrionDocument.Organization organization(String id, List<User> users) {
        return new OrionDocument.Organization(
                new OrganizationId(id), null, users, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
