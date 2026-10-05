package pro.deta.orion.schema.acl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SettingsTest {
    @Test
    public void accessControlListsAreImmutableSnapshots() {
        List<Credential> credentials = new ArrayList<>();
        credentials.add(new Credential(CredentialType.ARGON2, "hash"));
        List<User> users = new ArrayList<>();
        users.add(new User("root", null, null, "root@orion.pro",
                credentials, List.of(), List.of()));
        AccessControl accessControl = new AccessControl(users, List.of(), List.of());
        credentials.add(new Credential(
                CredentialType.OPENSSH_PUBLIC_KEY, "public-key"));
        users.add(new User("other", null, null, "other@example.test",
                List.of(), List.of(), List.of()));

        assertThat(accessControl.users()).hasSize(1);
        assertThat(accessControl.users().getFirst().credentials()).hasSize(1);
        assertThatThrownBy(() -> accessControl.users().add(users.getLast()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> accessControl.users().getFirst().credentials()
                .add(new Credential(CredentialType.PLAIN, "plain")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void newAccessControlCanBeCreatedFromPreviousOneWithoutMutatingIt() {
        AccessControl accessControl = new AccessControl();
        User root = new User(
                "root", null, null, "root@orion.pro", List.of(), List.of(), List.of());
        AccessControl changed = new AccessControl(
                List.of(root), accessControl.roles(), accessControl.grants());

        assertThat(accessControl.users()).isEmpty();
        assertThat(changed.users()).extracting(User::id).containsExactly("root");
    }
}
