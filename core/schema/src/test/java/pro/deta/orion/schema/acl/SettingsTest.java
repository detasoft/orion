package pro.deta.orion.schema.acl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SettingsTest {
    @Test
    public void accessControlListsAreImmutableSnapshots() {
        List<AccessControl.Credential> credentials = new ArrayList<>();
        credentials.add(new AccessControl.Credential(AccessControl.CredentialType.ARGON2, "hash"));
        List<AccessControl.User> users = new ArrayList<>();
        users.add(new AccessControl.User("root", null, null, "root@orion.pro",
                credentials, List.of(), List.of()));
        AccessControl accessControl = new AccessControl(users, List.of(), List.of());
        credentials.add(new AccessControl.Credential(
                AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, "public-key"));
        users.add(new AccessControl.User("other", null, null, "other@example.test",
                List.of(), List.of(), List.of()));

        assertThat(accessControl.getUsers()).hasSize(1);
        assertThat(accessControl.getUsers().getFirst().getCredentials()).hasSize(1);
        assertThatThrownBy(() -> accessControl.getUsers().add(users.getLast()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> accessControl.getUsers().getFirst().getCredentials()
                .add(new AccessControl.Credential(AccessControl.CredentialType.PLAIN, "plain")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void newAccessControlCanBeCreatedFromPreviousOneWithoutMutatingIt() {
        AccessControl accessControl = new AccessControl();
        AccessControl.User root = new AccessControl.User(
                "root", null, null, "root@orion.pro", List.of(), List.of(), List.of());
        AccessControl changed = new AccessControl(
                List.of(root), accessControl.getRoles(), accessControl.getGrants());

        assertThat(accessControl.getUsers()).isEmpty();
        assertThat(changed.getUsers()).extracting(AccessControl.User::getId).containsExactly("root");
    }
}
