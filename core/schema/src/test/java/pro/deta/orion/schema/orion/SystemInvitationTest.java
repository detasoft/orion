package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.SystemInvitation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemInvitationTest {
    @Test
    void roundTripsPendingInvitationsAndRetainsThemWhenAccessControlChanges() throws Exception {
        SystemInvitation invitation = new SystemInvitation("a".repeat(64), "Alice@example.test", 1800000000,
                "corporate", List.of(new SystemInvitation.RepositoryAccess("project", true),
                        new SystemInvitation.RepositoryAccess("team/docs", false)));
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                Optional.empty(), List.of(), List.of(), List.of(), List.of(), List.of(invitation)), List.of());
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        OrionXml.write(document, xml);
        OrionDocument restored = OrionXml.read(new ByteArrayInputStream(xml.toByteArray()));
        assertThat(restored.system().invitations()).containsExactly(invitation);
        assertThat(restored.replaceAccessControl(new AccessControl()).system().invitations())
                .containsExactly(invitation);
        assertThat(invitation.email()).isEqualTo("alice@example.test");
    }

    @Test
    void rejectsPatternsDuplicateRepositoriesAndInvalidInvitationMetadata() {
        for (String repository : List.of("**", "project/*", "../project", "Project", "project.git")) {
            assertThatThrownBy(() -> new SystemInvitation.RepositoryAccess(repository, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        SystemInvitation.RepositoryAccess access = new SystemInvitation.RepositoryAccess("project", true);
        assertThatThrownBy(() -> new SystemInvitation("a".repeat(64), "alice@example.test", 1800000000,
                "corporate", List.of(access, access))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SystemInvitation("bad", "alice@example.test", 1800000000,
                "corporate", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SystemInvitation("a".repeat(64), "alice@example.test", 1800000000,
                "", List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
