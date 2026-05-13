package pro.deta.orion.acl.schema;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SettingsTest {
    @Test
    public void accessControlListsAreImmutableSnapshots() {
        AccessControlDraft draft = new AccessControlDraft();
        AccessControlDraft.User user = ACLUtil.createUser("root", "root@orion.pro")
                .addCredential(AccessControl.CredentialType.ARGON2, "hash");
        draft.getUsers().add(user);

        AccessControl accessControl = draft.toAccessControl();
        user.addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, "public-key");

        assertThat(accessControl.getUsers()).hasSize(1);
        assertThat(accessControl.getUsers().getFirst().getCredentials()).hasSize(1);
        assertThatThrownBy(() -> accessControl.getUsers().add(ACLUtil.createUser("other", "other@example.test").toAccessControl()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> accessControl.getUsers().getFirst().getCredentials()
                .add(new AccessControl.Credential(AccessControl.CredentialType.PLAIN, "plain")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void draftCanBeCreatedFromImmutableAccessControlAndChangedIndependently() {
        AccessControl accessControl = new AccessControl(
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>());

        AccessControlDraft draft = accessControl.toDraft();
        draft.getUsers().add(ACLUtil.createUser("root", "root@orion.pro"));
        AccessControl changed = draft.toAccessControl();

        assertThat(accessControl.getUsers()).isEmpty();
        assertThat(changed.getUsers()).extracting(AccessControl.User::getId).containsExactly("root");
    }
}
